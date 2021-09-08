/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import kotlin.math.min

class ConnectionThread: Thread {

    constructor(): super() {

    }

    override fun run() {



    }

}



class Connection {

    fun connect(address: String) {
        var socket = Socket(address, 8080)

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

