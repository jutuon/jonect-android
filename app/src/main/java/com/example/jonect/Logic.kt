/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.os.Handler
import android.os.Looper
import android.os.Message

/**
 * Event from LogicService to Logic. Start connecting to the server.
 *
 * @param address Server IP address.
 */
data class ConnectEvent(val address: String)

/**
 * Event from LogicService to Logic. Logic quit request.
 */
class QuitRequestEvent

/**
 * Event from LogicService to Logic. Disconnect from the server.
 */
class DisconnectEvent


/**
 * Event from Connection to Logic. Server is now connected.
 */
class ConnectedEvent: ILogicStatusEvent {
    override fun toString(): String {
        return "Connected"
    }
}

/**
 * Event from Connection to Logic. Server is now disconnected.
 */
class DisconnectedEvent: ILogicStatusEvent {
    override fun toString(): String {
        return "Disconnected"
    }
}

/**
 * Event from Connection to Logic. There was some connection error.
 */
class ConnectionError: ILogicStatusEvent {
    override fun toString(): String {
        return "Connection error"
    }
}

/**
 * Event from Connection to Logic. Server sent JSON message.
 */
class ConnectionMessage(val message: ProtocolMessage) {
    override fun toString(): String {
        return "Connection message: $message"
    }
}

/**
 * Event from AudioPlayer to Logic. There was some audio stream error.
 */
class AudioStreamError(private val message: String): ILogicStatusEvent {
    override fun toString(): String {
        return "AudioStreamError: $message"
    }
}

/**
 * Marker for events which are used for updating LogicService's logic status information.
 */
interface ILogicStatusEvent

/**
 * Handle for thread which manages app's logic.
 */
class LogicThread(private val serviceHandle: ServiceHandle) : Thread() {
    private lateinit var messageHandler: Handler
    private lateinit var logic: Logic

    /**
     * This code runs in a new thread.
     */
    override fun run() {
        Looper.prepare()
        this.logic = Logic(this.serviceHandle)

        messageHandler = Handler(Looper.myLooper()!!) {
            // Handle message in this thread.
            this.logic.handleMessage(it)
        }

        this.logic.initLogicMessageHandle(LogicMessageHandle(this.messageHandler))

        Looper.loop()
    }

    /**
     * Send some type to the Logic.
     */
    private fun sendMessage(messageData: Any) {
        val message = this.messageHandler.obtainMessage()
        message.obj = messageData
        this.messageHandler.sendMessage(message)
    }

    /**
     * Send ConnectEvent to the Logic.
     *
     * @param address Server IP address.
     */
    fun sendConnectMessage(address: String) {
        this.sendMessage(ConnectEvent(address))
    }

    /**
     * Send QuitRequestEvent to the Logic.
     */
    fun sendQuitRequest() {
        this.sendMessage(QuitRequestEvent())
    }

    /**
     * Send DisconnectEvent to the Logic.
     */
    fun sendDisconnectMessage() {
        this.sendMessage(DisconnectEvent())
    }
}

/**
 * Handle for sending messages to LogicThread.
 */
class LogicMessageHandle(private val messageHandler: Handler) {
    /**
     * Send some object to LogicThread.
     */
    private fun sendMessage(messageData: Any) {
        val message = this.messageHandler.obtainMessage()
        message.obj = messageData
        this.messageHandler.sendMessage(message)
    }

    /**
     * Send ConnectedEvent to LogicThread.
     */
    fun sendConnectedNotification() {
        this.sendMessage(ConnectedEvent())
    }

    /**
     * Send ConnectionError to LogicThread.
     */
    fun sendConnectionError() {
        this.sendMessage(ConnectionError())
    }

    /**
     * Send ProtocolMessage to LogicThread.
     */
    fun sendConnectionMessage(message: ProtocolMessage) {
        this.sendMessage(ConnectionMessage(message))
    }

    /**
     * Send AudioStreamError to LogicThread.
     *
     * @param error Error message.
     */
    fun sendAudioStreamError(error: String) {
        this.sendMessage(AudioStreamError(error))
    }
}


/**
 * Component which handles server connections. Call initLogicMessageHandle after
 * creating Logic component.
 */
class Logic(private val serviceHandle: ServiceHandle) {
    private lateinit var logicMessageHandle: LogicMessageHandle


    private var connection: ConnectionThread? = null
    private var audio: AudioThread? = null
    private var connectionAddress: String? = null

    /**
     * Init handle for sending messages to the logic thread. Call this after creating
     * Logic component.
     */
    fun initLogicMessageHandle(handle: LogicMessageHandle) {
        this.logicMessageHandle = handle
    }

    /**
     * Handle messages which other components send to the Logic component.
     *
     * Returning false will continue message handling. Returning true will stop message handling.
     */
    fun handleMessage(m: Message): Boolean {
        if (m.obj == null) {
            // Continue message handling.
            return false
        }

        when (val event = m.obj) {
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
                this.connection?.sendProtocolMessage(ClientInfo("0.1", "Test client", AudioPlayer.getNativeSampleRate()))
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

    /**
     * Handle JSON message from the server.
     */
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

    /**
     * Update LogicService's status text which will be displayed in the app UI.
     */
    private fun updateServiceStatus(statusEvent: ILogicStatusEvent) {
        this.serviceHandle.updateStatus(statusEvent)
    }

}