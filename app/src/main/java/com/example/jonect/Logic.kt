/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.os.Handler
import android.os.Looper
import android.os.Message

data class ConnectEvent(val address: String)
class QuitRequestEvent
class ConnectedEvent
class ConnectionError


class LogicThread: Thread {
    private val serviceHandle: ServiceHandle
    private lateinit var messageHandler: Handler
    private lateinit var logic: Logic

    constructor(serviceHandle: ServiceHandle): super() {
        this.serviceHandle = serviceHandle
    }

    override fun run() {
        // This code runs in a new thread.

        Looper.prepare()
        this.logic = Logic(this.serviceHandle)

        messageHandler = Handler(Looper.myLooper()!!) {
            // Handle message in this thread.
            this.logic.handleMessage(it)
        }

        this.logic.initLogicMessageHandle(LogicMessageHandle(this.messageHandler))

        Looper.loop()
    }

    fun sendConnectMessage(address: String) {
        var message = this.messageHandler.obtainMessage()
        message.obj = ConnectEvent(address)
        this.messageHandler.sendMessage(message)
    }

    fun sendQuitRequest() {
        var message = this.messageHandler.obtainMessage()
        message.obj = QuitRequestEvent()
        this.messageHandler.sendMessage(message)
    }
}

class LogicMessageHandle(private val messageHandler: Handler) {
    fun sendConnectedNotification() {
        var message = this.messageHandler.obtainMessage()
        message.obj = ConnectedEvent()
        this.messageHandler.sendMessage(message)
    }
}


class Logic(val serviceHandle: ServiceHandle) {
    private lateinit var logicMessageHandle: LogicMessageHandle


    private var connection: ConnectionThread? = null

    fun initLogicMessageHandle(handle: LogicMessageHandle) {
        this.logicMessageHandle = handle
    }

    fun handleMessage(m: Message): Boolean {
        if (m.obj == null) {
            // Continue message handling.
            return false
        }

        var event = m.obj

        when (event) {
            is ConnectEvent -> {
                println(event.address)

                val connection = ConnectionThread(logicMessageHandle, event.address)
                connection.start()

                this.connection = connection
            }
            is ConnectedEvent -> {
                this.updateServiceStatus("Connected")
            }
            is ConnectionError -> {
                this.updateServiceStatus("Connection error")
                this.connection?.run {
                    sendQuitRequest()
                    join()
                }
            }
            is QuitRequestEvent -> {
                this.connection?.run {
                    sendQuitRequest()
                    join()
                }

                // Quit message loop.
                Looper.myLooper()!!.quit()
                return true
            }
        }

        // Continue message handling.
        return false
    }

    fun updateServiceStatus(status: String) {
        this.serviceHandle.updateStatus(status)
    }

}