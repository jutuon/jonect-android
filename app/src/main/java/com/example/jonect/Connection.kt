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

/**
 * Handle to ConnectionThread. Use start method to start the thread.
 *
 * This thread will do JSON UI messaging to the Rust logic code.
 *
 */
class ConnectionThread(
    private val handle: LogicMessageHandle,
) : Thread() {
    private val connectionMessages: BlockingQueue<ProtocolMessage> =
        ArrayBlockingQueue(32)
    private var messageNotificationPipe: Pipe
    private var messageNotificationSink: Pipe.SinkChannel

    private var requestQuitPipe: Pipe
    private var requestQuitSink: Pipe.SinkChannel

    // One notification is one byte.
    private val notificationByte = ByteBuffer.allocate(1)

    init {
        this.requestQuitPipe = SelectorProvider.provider().openPipe()
        this.requestQuitSink = this.requestQuitPipe.sink()

        this.messageNotificationPipe = SelectorProvider.provider().openPipe()
        this.messageNotificationSink = this.messageNotificationPipe.sink()
    }

    /**
     * This code runs in a new thread.
     */
    override fun run() {
        val connection = Connection(
            this.handle,
            this.connectionMessages,
            this.messageNotificationPipe.source(),
            this.requestQuitPipe.source(),
        )
        connection.start()
    }

    /**
     * Send quit message to the thread.
     */
    private fun sendQuitRequest() {
        this.notificationByte.clear()
        this.requestQuitSink.write(this.notificationByte)
    }

    /**
     * Close ConnectionThread. This method will block.
     */
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

/**
 * If this object is returned then disable socket writing notifications which select method would
 * return.
 */
private class DisableWriting

/**
 * Component for reading and writing Jonect UI JSON messages from TCP socket.
 *
 * Logic component will send messages to this component using two "channels". The first channel
 * is implemented with BlockingQueue and Pipe (Java's select system does not support
 * BlockingQueue as event source). The second channel is implemented with Pipe and is used
 * only for quit request notification.
 */
class Connection(
        private val handle: LogicMessageHandle,
        private val messages: BlockingQueue<ProtocolMessage>,
        private val messageNotifications: Pipe.SourceChannel,
        private val quitRequested: Pipe.SourceChannel,
) {

    // One notification is one byte.
    private val notificationBuffer = ByteBuffer.allocate(1)
    private val socketReader = SocketReader()

    // This is initialized only when
    private lateinit var socketWriter: SocketWriter

    fun start() {
        println("Connection: start")

        val rustLogic = RustLogic()
        rustLogic.startLogicThread()

        // Connect to Rust logic's UI JSON TCP port.

        val inetAddress = InetSocketAddress("127.0.0.1", 8081)
        var socket: SocketChannel

        var reconnectCount = 0

        while (true) {
            try {
                socket = SocketChannel.open(inetAddress)
                break
            } catch (e: ConnectException) {
                println(e)
                reconnectCount++

                if (reconnectCount >= 100) {
                    // Send error and wait quit.
                    this.handle.sendConnectionError()
                    rustLogic.quitLogicThread()
                    this.waitQuitRequest()
                    this.messageNotifications.close()
                    this.quitRequested.close()
                    return
                } else {
                    Thread.sleep(10)
                }
            }
        }

        this.handle.sendConnectedNotification()

        // Configure Selector.

        this.messageNotifications.configureBlocking(false)
        this.quitRequested.configureBlocking(false)
        socket.configureBlocking(false)

        val selector = Selector.open()
        val messageNotificationKey = this.messageNotifications.register(selector, SelectionKey.OP_READ)
        val quitRequestedKey = this.quitRequested.register(selector, SelectionKey.OP_READ)

        // There is not yet messages to send to the server so only register read events.
        val socketKey = socket.register(selector, SelectionKey.OP_READ)

        while (true) {
            val result = selector.select()

            if (result == 0) {
                continue
            }

            if (selector.selectedKeys().remove(socketKey)) {
                if (socketKey.isReadable) {
                    // Handle socket reading.
                    try {
                        this.socketReader.handleSocketRead(socket)?.also {
                            this.parseAndSendMessage(it)
                        }
                    } catch (e: ConnectionQuitException) {
                        this.handle.sendConnectionError()
                        this.waitQuitRequest()
                        break
                    }
                }

                if (socketKey.isWritable) {
                    /*
                    Handle socket writing. This code only runs when there is something
                    to write to the socket.
                     */

                    if (this.socketWriter.handleSocketWrite(socket) is DisableWriting) {
                        // JSON message is writing is complete.

                        // Disable socket writing.
                        socketKey.interestOps(SelectionKey.OP_READ)
                        // Enable message reading.
                        messageNotificationKey.interestOps(SelectionKey.OP_READ)
                    }
                }
            }

            if (selector.selectedKeys().remove(messageNotificationKey)) {
                /*
                Kotlin Logic sent new message for processing. This code runs only when there is no
                message writing happening currently.
                */

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

        // Quit connection.

        selector.close()
        socket.close()

        this.messageNotifications.close()
        this.quitRequested.close()

        RustLogic().quitLogicThread()

        println("Connection: quit")
    }

    /**
     * Return true if quit is requested.
     */
    private fun checkQuitRequest(): Boolean {
        this.notificationBuffer.clear()
        val result = this.quitRequested.read(this.notificationBuffer)
        if (result == -1) {
            // EOF
            throw Exception("Connection: unexpected EOF")
        } else if (result == 0) {
            return false
        }

        // One byte received so quit is requested.
        return true
    }

    /**
     * Block until quit is requested.
     */
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

    /**
     * Read message from Logic. This message will be sent to the Rust logic code.
     * Returns null if there was no message available.
     */
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

    /**
     * Parse and send message from the server to the Logic.
     */
    private fun parseAndSendMessage(message: String) {
        try {
            val protocolMessage = Json.decodeFromString<ProtocolMessage>(message)
            this.handle.sendConnectionMessage(protocolMessage)
        } catch (e: SerializationException) {
            println("Unknown message: $message")
        }
    }
}

/**
 * Server disconnected.
 */
class ConnectionQuitException: Exception()

/**
 * Server socket reading state.
 */
enum class ReadMode {
    MESSAGE_LENGTH,
    MESSAGE,
}

/**
 * Socket reading logic.
 */
private class SocketReader {
    var readMode: ReadMode = ReadMode.MESSAGE_LENGTH
    val messageLengthBytes: ByteBuffer = ByteBuffer.allocate(4)
    lateinit var messageBytes: ByteBuffer

    /**
     * Read JSON message from the socket. Handles socket reading events.
     *
     * Returns JSON message as String if message transfer is complete.
     *
     * Throws ConnectionQuitException if the server disconnects.
     */
    fun handleSocketRead(socket: SocketChannel): String? {
        // Change reading buffer depending on the current reading mode.
        val bytes = when (this.readMode) {
            ReadMode.MESSAGE_LENGTH -> {
                this.messageLengthBytes
            }
            ReadMode.MESSAGE -> {
                this.messageBytes
            }
        }

        while (true) {
            // Non blocking socket mode should be enabled.

            // TODO: Handle IOException?
            val readCount = socket.read(bytes)
            if (readCount == -1) {
                // EOF, server disconnected.
                throw ConnectionQuitException()
            } else if (readCount == 0) {
                // Received zero bytes from the socket.

                if (bytes.hasRemaining()) {
                    // Message length or message transfer is not complete. Continue next time.
                    return null
                } else {
                    // Message length or message transfer is complete.
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

/**
 * Socket writing logic.
 */
private class SocketWriter {
    val bytes: ByteBuffer

    /**
     * Create new writer for writing one JSON message.
     *
     * @param message JSON message as String.
     */
    constructor(message: String) {
        val messageByteArray = message.encodeToByteArray()
        // TODO: Check integer overflow.
        this.bytes = ByteBuffer.allocate(messageByteArray.size + 4)
        this.bytes.putInt(messageByteArray.size)
        this.bytes.put(messageByteArray)
        this.bytes.rewind()
    }

    /**
     * Handle socket write event.
     */
    fun handleSocketWrite(socket: SocketChannel): DisableWriting? {
        while (true) {
            // Socket should be in non blocking mode.

            // TODO: Handle IOException?
            val writeCount = socket.write(this.bytes)

            if (writeCount == 0) {
                return if (this.bytes.hasRemaining()) {
                    // Message writing is not complete. Continue next time.
                    null
                } else {
                    // Message writing is complete. Disable socket writing events.
                    DisableWriting()
                }
            }
        }
    }
}

private class RustLogic {
    init {
        System.loadLibrary("jonect_android_rust")
    }

    external fun startLogicThread()
    external fun quitLogicThread()
}