/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.Exception
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class ConnectionThread: Thread {
    private val handle: LogicMessageHandle
    private val address: String

    private val connectionMessages: BlockingQueue<ProtocolMessage> =
        ArrayBlockingQueue(32)
    private var messageNotificationPipe: Pipe
    private var messageNotificationSink: Pipe.SinkChannel

    private var requestQuitPipe: Pipe
    private var requestQuitSink: Pipe.SinkChannel

    private val notificationByte = ByteBuffer.allocate(1)

    constructor(handle: LogicMessageHandle, address: String): super() {
        this.handle = handle
        this.address = address

        this.requestQuitPipe = SelectorProvider.provider().openPipe()
        this.requestQuitSink = this.requestQuitPipe.sink()

        this.messageNotificationPipe = SelectorProvider.provider().openPipe()
        this.messageNotificationSink = this.messageNotificationPipe.sink()
    }

    override fun run() {
        val connection = Connection(
            this.handle,
            this.address,
            this.connectionMessages,
            this.messageNotificationPipe.source(),
            this.requestQuitPipe.source(),
        )
        connection.start()
        connection.closeSystemResources()
    }

    private fun sendQuitRequest() {
        this.notificationByte.clear()
        this.requestQuitSink.write(this.notificationByte)
    }

    fun runQuit() {
        this.sendQuitRequest()
        this.join()
        this.requestQuitSink.close()
        this.messageNotificationSink.close()
    }

    fun sendProtocolMessage(message: ProtocolMessage) {
        this.connectionMessages.put(message)
        this.notificationByte.clear()
        this.messageNotificationSink.write(this.notificationByte)
    }
}

private class DisableWriting

class Connection(
        private val handle: LogicMessageHandle,
        private val address: String,
        private val messages: BlockingQueue<ProtocolMessage>,
        private val messageNotifications: Pipe.SourceChannel,
        private val quitRequested: Pipe.SourceChannel,
) {

    private val notificationBuffer = ByteBuffer.allocate(1)
    private val socketReader = SocketReader()
    private lateinit var socketWriter: SocketWriter

    fun start() {
        println("Connection: start")

        val inetAddress = InetSocketAddress(this.address, 8080)
        val socket: SocketChannel = try {
            SocketChannel.open(inetAddress)
        } catch (e: ConnectException) {
            // Send error and wait quit.
            this.handle.sendConnectionError()
            this.waitQuitRequest()
            return
        }

        this.handle.sendConnectedNotification()

        this.messageNotifications.configureBlocking(false)
        this.quitRequested.configureBlocking(false)
        socket.configureBlocking(false)

        val selector = Selector.open()
        val messageNotificationKey = this.messageNotifications.register(selector, SelectionKey.OP_READ)
        val quitRequestedKey = this.quitRequested.register(selector, SelectionKey.OP_READ)
        val socketKey = socket.register(selector, SelectionKey.OP_READ)

        while (true) {
            val result = selector.select()

            if (result == 0) {
                continue
            }

            if (selector.selectedKeys().remove(socketKey)) {
                if (socketKey.isReadable) {
                    try {
                        this.socketReader.handleSocketRead(socket)?.also {
                            this.parseAndSendMessage(it)
                        }
                    } catch (e: ConnectionQuitException) {
                        this.handle.sendConnectionError()
                        break
                    }
                }

                if (socketKey.isWritable) {
                    if (this.socketWriter.handleSocketWrite(socket) is DisableWriting) {
                        // Disable socket writing.
                        socketKey.interestOps(SelectionKey.OP_READ)
                        // Enable message reading.
                        messageNotificationKey.interestOps(SelectionKey.OP_READ)
                    }
                }
            }

            if (selector.selectedKeys().remove(messageNotificationKey)) {
                this.tryReadMessage()?.also {
                    this.socketWriter = SocketWriter(it)
                    // Enable socket writing.
                    socketKey.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                    // Disable message reading.
                    messageNotificationKey.interestOps(0)
                }
            }

            if (selector.selectedKeys().remove(quitRequestedKey)) {
                if (this.checkQuitRequest()) {
                    break
                }
            }
        }

        selector.close()
        socket.close()
        println("Connection: quit")
    }

    private fun checkQuitRequest(): Boolean {
        this.notificationBuffer.clear()
        val result = this.quitRequested.read(this.notificationBuffer)
        if (result == -1) {
            // EOF
            throw Exception("Connection: unexpected EOF")
        } else if (result == 0) {
            return false
        }

        return true
    }

    private fun waitQuitRequest() {
        while (true) {
            this.notificationBuffer.clear()
            val result = this.quitRequested.read(this.notificationBuffer)
            if (result == -1) {
                // EOF
                throw Exception("Connection: unexpected EOF")
            } else if (result == 0) {
                continue
            }
            break
        }
    }

    private fun tryReadMessage(): String? {
        this.notificationBuffer.clear()
        val result = this.messageNotifications.read(this.notificationBuffer)

        if (result == -1) {
            // EOF
            throw Exception("Connection: unexpected EOF")
        } else if (result == 0) {
            return null
        }

        return Json.encodeToString(this.messages.take())
    }

    private fun parseAndSendMessage(message: String) {
        try {
            val protocolMessage = Json.decodeFromString<ProtocolMessage>(message)
            this.handle.sendConnectionMessage(protocolMessage)
        } catch (e: SerializationException) {
            println("Unknown message: $message")
        }
    }

    fun closeSystemResources() {
        this.messageNotifications.close()
        this.quitRequested.close()
    }
}

class ConnectionQuitException: Exception()

enum class ReadMode {
    MESSAGE_LENGTH,
    MESSAGE,
}

private class SocketReader {
    var readMode: ReadMode = ReadMode.MESSAGE_LENGTH
    val messageLengthBytes: ByteBuffer = ByteBuffer.allocate(4)
    lateinit var messageBytes: ByteBuffer

    fun handleSocketRead(socket: SocketChannel): String? {
        val bytes = when (this.readMode) {
            ReadMode.MESSAGE_LENGTH -> {
                this.messageLengthBytes
            }
            ReadMode.MESSAGE -> {
                this.messageBytes
            }
        }

        while (true) {
            val readCount = socket.read(bytes)
            if (readCount == -1) {
                // EOF
                throw ConnectionQuitException()
            } else if (readCount == 0) {
                if (bytes.hasRemaining()) {
                    return null
                } else {
                    break
                }
            }
        }

        when (this.readMode) {
            ReadMode.MESSAGE_LENGTH -> {
                this.messageBytes = ByteBuffer.allocate(this.messageLengthBytes.getInt(0))
                this.readMode = ReadMode.MESSAGE
            }
            ReadMode.MESSAGE -> {
                val message = this.messageBytes.array().decodeToString()
                this.readMode = ReadMode.MESSAGE_LENGTH
                this.messageLengthBytes.clear()
                return message
            }
        }

        return null
    }
}

private class SocketWriter {
    val bytes: ByteBuffer

    constructor(message: String) {
        val messageByteArray = message.encodeToByteArray()
        // TODO: Check integer overflow.
        this.bytes = ByteBuffer.allocate(messageByteArray.size + 4)
        this.bytes.putInt(messageByteArray.size)
        this.bytes.put(messageByteArray)
        this.bytes.rewind()
    }

    fun handleSocketWrite(socket: SocketChannel): DisableWriting? {
        while (true) {
            val writeCount = socket.write(this.bytes)

            if (writeCount == 0) {
                return if (this.bytes.hasRemaining()) {
                    null
                } else {
                    DisableWriting()
                }
            }
        }
    }
}
