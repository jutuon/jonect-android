/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.math.min

interface IConnectionMessage

class ConnectionQuitRequestEvent: IConnectionMessage

class ConnectionThread: Thread {
    private val handle: LogicMessageHandle
    private val address: String

    private val connectionMessages: BlockingQueue<IConnectionMessage> =
        ArrayBlockingQueue(32)
    private var pipe: Pipe
    private var sendRequestQuit: Pipe.SinkChannel

    constructor(handle: LogicMessageHandle, address: String): super() {
        this.handle = handle
        this.address = address

        this.pipe = SelectorProvider.provider().openPipe()
        this.sendRequestQuit = this.pipe.sink()
    }

    override fun run() {
        val connection = Connection(
            this.handle,
            this.address,
            this.connectionMessages,
            this.pipe.source(),
        )
        connection.start()
    }

    fun sendQuitRequest() {
        this.connectionMessages.put(ConnectionQuitRequestEvent())

        val data = ByteBuffer.allocate(1)
        this.sendRequestQuit.write(data)
    }
}



class Connection(
    private val handle: LogicMessageHandle,
    private val address: String,
    private val messages: BlockingQueue<IConnectionMessage>,
    private val messageNotifications: Pipe.SourceChannel,
) {

    fun start() {
        println("Connection: start")
        handle.sendConnectedNotification()

        val buffer = ByteBuffer.allocate(1)
        while (true) {
            buffer.clear()
            val result = messageNotifications.read(buffer)
            if (result == -1) {
                // EOF
                return
            } else if (result == 0) {
                continue
            }

            val message = messages.take()
            when (message) {
                is ConnectionQuitRequestEvent -> {
                    println("Connection: quit")
                    break
                }
            }
        }
    }

    fun connect() {
        var socket = Socket(this.address, 8080)

        var input = socket.getInputStream()

        val dataStream = DataInputStream(input)

        var messageBuffer = ByteArrayOutputStream()
        var readBuffer = ByteArray(1024);
        while (true) {
            messageBuffer.reset()

            val messageSize = dataStream.readInt()
            var messageBytesMissing = messageSize
            var nextReadSize = min(readBuffer.size, messageBytesMissing)
            while (true) {
                val readResult = dataStream.read(readBuffer, 0, nextReadSize)

                if (readResult == -1) {
                    // EOF
                    return
                } else {
                    messageBuffer.write(readBuffer, 0, readResult)
                    messageBytesMissing -= readResult
                    nextReadSize = min(readBuffer.size, messageBytesMissing)
                }

                if (nextReadSize == 0) {
                    val message = messageBuffer.toString("UTF-8")
                    println(message)
                    break
                }
            }
        }
    }
}

