/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class ServiceConnectionHandler(private val activity: MainActivity): ServiceConnection {
    override fun onServiceConnected(p0: ComponentName?, serviceBinder: IBinder?) {
        this.activity.service = (serviceBinder as LogicServiceBinder).getService()
        this.activity.handleServiceConnect()
        println("Activity: service connected")
    }

    override fun onServiceDisconnected(p0: ComponentName?) {
        // This should only happen when this.activity.unbindService() is called
        // and this.activity.onDestroy() is running.

        // If check is just limited to this.activity.isFinishing
        // because it is not clear when this method runs after unbindService() is called.
        if (!this.activity.quitStarted) {
            throw Exception("Activity: Service disconnected")
        }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button

    var service: LogicService? = null
    var quitStarted = false

    private val serviceConnectionHandler = ServiceConnectionHandler(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.connectButton = this.findViewById<Button>(R.id.button_connect)
        this.connectButton.setOnClickListener {
            this.handleConnectButtonOnClick()
        }

        this.address = this.findViewById(R.id.edit_text_server_address)
        this.status = this.findViewById(R.id.text_view_status)

        // Update status text before binding service to
        // avoid setting previous text after UI is drawn before
        // service is connected. Method this.handleServiceConnect()
        // will also update the text.
        savedInstanceState?.also { bundle ->
            bundle.getString(STATE_TEXT)?.also {
                this.status.text = it
            }
        }

        val intent = Intent(this, LogicService::class.java)
        this.startService(intent)
        val status = this.bindService(intent, serviceConnectionHandler, BIND_AUTO_CREATE)

        if (!status) {
            throw Exception("Activity: bindService() failed")
        }
    }

    fun handleServiceConnect() {
        this.service?.also {
            this.status.text = it.getStatus()
            this.serviceServerConnectedUpdate(it.getServerConnected())
            it.setCurrentConnectedActivity(this)

            // Connect to server automatically.
            this.handleConnectButtonOnClick()
        }
    }

    fun serviceServerConnectedUpdate(serverConnected: Boolean) {
        if (serverConnected) {
            this.connectButton.text = "Disconnect"
            this.address.isEnabled = false
        } else {
            this.connectButton.text = "Connect"
            this.address.isEnabled = true
        }
    }

    fun serviceStatusUpdate(newStatus: String) {
        this.status.text = newStatus
        println("Status update: $newStatus")
    }

    private fun handleConnectButtonOnClick() {
        this.service?.also {
            if (it.getServerConnectDisconnectRunning()) {
                return
            }

            if (it.getServerConnected()) {
                it.sendDisconnectMessage()
            } else {
                val address = this.address.text.toString()
                it.sendConnectMessage(address)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (this.isFinishing) {
            this.quitStarted = true
            val intent = Intent(this, LogicService::class.java)
            this.service?.also {
                it.disconnectActivity(this)
                this.unbindService(this.serviceConnectionHandler)
            }
            this.stopService(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEXT, this.status.text.toString())

        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_TEXT = "status"
    }

}