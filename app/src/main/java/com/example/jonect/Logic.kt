/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.media.AudioFormat
import android.media.AudioTrack
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
 * Event from Connection to Logic. Rust logic is now connected.
 */
class RustLogicConnectedEvent: ILogicStatusEvent {
    override fun toString(): String {
        return "RustLogicConnectedEvent"
    }
}


/**
 * Event from Connection to Logic. Rust logic is now disconnected.
 */
class RustLogicDisconnectedEvent

/**
 * Event from Connection to Logic. There was some connection error.
 */
class RustLogicConnectionError: ILogicStatusEvent {
    override fun toString(): String {
        return "Rust logic connection error. Restart the app."
    }
}

/**
 * Event from Connection to Logic. Rust logic sent UI JSON message.
 */
class ConnectionMessage(val message: ProtocolMessage) {
    override fun toString(): String {
        return "Connection message: $message"
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
    private var disableMessageSending: Boolean = false

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
        if (this.disableMessageSending && messageData !is QuitRequestEvent ) {
            return
        }

        val message = this.messageHandler.obtainMessage()
        message.obj = messageData
        this.messageHandler.sendMessage(message)
    }

    fun disableNonQuitMessageSending() {
        this.disableMessageSending = true
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
        this.sendMessage(RustLogicConnectedEvent())
    }

    /**
     * Send ConnectionError to LogicThread.
     */
    fun sendConnectionError() {
        this.sendMessage(RustLogicConnectionError())
    }

    /**
     * Send ProtocolMessage to LogicThread.
     */
    fun sendConnectionMessage(message: ProtocolMessage) {
        this.sendMessage(ConnectionMessage(message))
    }
}


/**
 * Component which handles server connections. Call initLogicMessageHandle after
 * creating Logic component.
 */
class Logic(private val serviceHandle: ServiceHandle) {
    private lateinit var logicMessageHandle: LogicMessageHandle
    private lateinit var connection: ConnectionThread

    private var connectionAddress: String? = null


    /**
     * Init handle for sending messages to the logic thread. Call this after creating
     * Logic component.
     */
    fun initLogicMessageHandle(handle: LogicMessageHandle) {
        this.logicMessageHandle = handle

        this.connection = ConnectionThread(logicMessageHandle)
        this.connection.start()
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

        println(m)

        when (val event = m.obj) {
            is ConnectEvent -> {
                println(event.address)

                this.connectionAddress = event.address
                this.connection.sendProtocolMessage(ConnectTo(event.address))
            }
            is DisconnectEvent -> {
                this.connection.sendProtocolMessage(DisconnectDevice)
            }
            is RustLogicConnectedEvent -> {
                println("Rust logic connected.")
            }
            is RustLogicConnectionError -> {
                this.updateServiceStatus(RustLogicConnectionError())
            }
            is ConnectionMessage -> {
                this.handleProtocolMessage(event.message)
            }
            is QuitRequestEvent -> {
                this.connection.runQuit()

                // Quit message loop.
                Looper.myLooper()!!.quit()
                return true
            }
        }

        // Continue message handling.
        return false
    }

    /**
     * Handle UI JSON message from the Rust logic.
     */
    private fun handleProtocolMessage(message: ProtocolMessage) {
        when (message) {
            is AndroidGetNativeSampleRate -> {
                println("AndroidGetNativeSampleRate received")
                val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioFormat.ENCODING_PCM_16BIT)
                this.connection.sendProtocolMessage(AndroidNativeSampleRate(sampleRate))
            }
            is DeviceConnectionEstablished -> {
                this.updateServiceStatus(message)
            }
            is DeviceConnectionDisconnected -> {
                this.updateServiceStatus(message)
            }
            is DeviceConnectionDisconnectedWithError -> {
                this.updateServiceStatus(message)
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