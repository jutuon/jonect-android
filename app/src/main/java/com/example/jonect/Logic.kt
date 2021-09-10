/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.os.Handler
import android.os.Looper
import android.os.Message

data class ConnectEvent(val address: String)
class QuitRequestEvent
class DisconnectEvent
class ConnectedEvent: ILogicStatusEvent {
    override fun toString(): String {
        return "Connected"
    }
}
class DisconnectedEvent: ILogicStatusEvent {
    override fun toString(): String {
        return "Disconnected"
    }
}
class ConnectionError: ILogicStatusEvent {
    override fun toString(): String {
        return "Connection error"
    }
}

interface ILogicStatusEvent

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

    private fun sendMessage(messageData: Any) {
        var message = this.messageHandler.obtainMessage()
        message.obj = messageData
        this.messageHandler.sendMessage(message)
    }

    fun sendConnectMessage(address: String) {
        this.sendMessage(ConnectEvent(address))
    }

    fun sendQuitRequest() {
        this.sendMessage(QuitRequestEvent())
    }

    fun sendDisconnectMessage() {
        this.sendMessage(DisconnectEvent())
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
            is DisconnectEvent -> {
                this.connection?.also {
                    it.sendQuitRequest()
                    it.join()

                    this.connection = null
                    this.updateServiceStatus(DisconnectedEvent())
                }
            }
            is ConnectedEvent -> {
                this.updateServiceStatus(event)
            }
            is ConnectionError -> {
                this.updateServiceStatus(event)
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

    fun updateServiceStatus(statusEvent: ILogicStatusEvent) {
        this.serviceHandle.updateStatus(statusEvent)
    }

}