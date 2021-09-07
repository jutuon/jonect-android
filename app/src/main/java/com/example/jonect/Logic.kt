package com.example.jonect

import android.os.Handler
import android.os.Looper
import android.os.Message

data class ConnectEvent(val address: String)
class QuitRequestEvent


class LogicThread: Thread {
    private lateinit var messageHandler: Handler
    private lateinit var logic: Logic

    constructor(): super() {}

    override fun run() {
        // This code runs in a new thread.

        Looper.prepare()
        this.logic = Logic()

        messageHandler = Handler(Looper.myLooper()!!) {
            // Handle message in this thread.
            this.logic.handleMessage(it)
        }

        Looper.loop()
    }

    fun sendConnectMessage(address: String) {
        var message = this.messageHandler.obtainMessage()
        message.obj = ConnectEvent(address)
        messageHandler.sendMessage(message)
    }

    fun sendQuitRequest() {
        var message = this.messageHandler.obtainMessage()
        message.obj = QuitRequestEvent()
        messageHandler.sendMessage(message)
    }
}


class Logic {
    constructor() {


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
            }
            is QuitRequestEvent -> {
                // Quit message loop.
                Looper.myLooper()!!.quit()
                return true
            }
        }

        // Continue message handling.
        return false
    }

}