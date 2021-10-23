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
class ConnectionMessage(val message: ProtocolMessage): ILogicStatusEvent {
    override fun toString(): String {
        return "Connection message: $message"
    }
}

class AudioStreamError(private val message: String): ILogicStatusEvent {
    override fun toString(): String {
        return "AudioStreamError: $message"
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
    private fun sendMessage(messageData: Any) {
        var message = this.messageHandler.obtainMessage()
        message.obj = messageData
        this.messageHandler.sendMessage(message)
    }

    fun sendConnectedNotification() {
        this.sendMessage(ConnectedEvent())
    }

    fun sendConnectionError() {
        this.sendMessage(ConnectionError())
    }

    fun sendConnectionMessage(message: ProtocolMessage) {
        this.sendMessage(ConnectionMessage(message))
    }

    fun sendAudioStreamError(error: String) {
        this.sendMessage(AudioStreamError(error))
    }
}


class Logic(val serviceHandle: ServiceHandle) {
    private lateinit var logicMessageHandle: LogicMessageHandle


    private var connection: ConnectionThread? = null
    private var audio: AudioThread? = null
    private var connectionAddress: String? = null

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
                this.connectionAddress = event.address
            }
            is DisconnectEvent -> {
                this.audio?.also {
                    it.runQuit()
                    this.audio = null
                }
                this.connection?.also {
                    it.runQuit()
                    this.connection = null
                    this.updateServiceStatus(DisconnectedEvent())
                }
            }
            is ConnectedEvent -> {
                this.updateServiceStatus(event)
                this.connection?.sendProtocolMessage(ClientInfo("0.1", "Test client"))
            }
            is ConnectionError -> {
                this.audio?.also {
                    it.runQuit()
                    this.audio = null
                }
                this.connection?.also {
                    it.runQuit()
                    this.connection = null
                    this.updateServiceStatus(ConnectionError())
                }
            }
            is AudioStreamError -> {
                println(event.toString())
                this.updateServiceStatus(event)
                this.audio?.also {
                    it.runQuit()
                    this.audio = null
                }
            }
            is ConnectionMessage -> {
                this.handleProtocolMessage(event.message)
            }
            is QuitRequestEvent -> {
                this.audio?.run {
                    runQuit()
                }

                this.connection?.run {
                    runQuit()
                }

                // Quit message loop.
                Looper.myLooper()!!.quit()
                return true
            }
        }

        // Continue message handling.
        return false
    }

    private fun handleProtocolMessage(message: ProtocolMessage) {
        when (message) {
            is ServerInfo -> {
                println("Connected to $message")
            }
            is Ping -> {
                println("Ping received")
                this.connection?.sendProtocolMessage(PingResponse)
            }
            is PlayAudioStream -> {

                println("Audio stream play request received. Message: $message")
                if (this.audio == null) {
                    val info = AudioStreamInfo(this.connectionAddress!!, message)
                    this.audio = AudioThread(this.logicMessageHandle, info)
                    this.audio!!.start()
                }
            }
            else -> {
                println("Unsupported message $message received.")
            }
        }
    }

    fun updateServiceStatus(statusEvent: ILogicStatusEvent) {
        this.serviceHandle.updateStatus(statusEvent)
    }

}