/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

/**
 * Handle service connection events.
 */
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

/**
 * Class that will check IP address in EditText widget.
 */
class IpAddressCheck(private val connectButton: Button): TextWatcher {
    override fun afterTextChanged(text: Editable?) {
        if (text == null) {
            return
        }
        this.connectButton.isEnabled = text.startsWith("192.168.") ||
                text.startsWith("10.") ||
                text.startsWith("127.")
    }

    override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
}

/**
 * App's Activity.
 *
 * Previously used IP address and automatic connecting setting is stored to SharedPreferences.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var connectAutomaticallyCheckBox: CheckBox

    private var preferenceAddress: String? = null

    /**
     * If true then connect automatically to the server when the LogicService connects.
     */
    private var connectAutomatically = false

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
        this.address.addTextChangedListener(IpAddressCheck(this.connectButton))

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

        // Setup automatic connecting and restore previously used IP address if there is one.

        val sharedPreferences = this.getPreferences(Context.MODE_PRIVATE)

        this.connectAutomaticallyCheckBox = this.findViewById(R.id.checkbox_connect_automatically)
        this.connectAutomaticallyCheckBox.isChecked = sharedPreferences.getBoolean(CONNECT_AUTOMATICALLY_KEY, false)
        this.connectAutomaticallyCheckBox.setOnClickListener {
            sharedPreferences
                .edit()
                .putBoolean(CONNECT_AUTOMATICALLY_KEY, this.connectAutomaticallyCheckBox.isChecked)
                .apply()
        }

        val previousAddress = sharedPreferences.getString(ADDRESS_KEY, null)
        if (previousAddress != null) {
            this.preferenceAddress = previousAddress

            if (savedInstanceState == null) {
                this.address.setText(previousAddress)
                if (this.connectAutomaticallyCheckBox.isChecked) {
                    this.connectAutomatically = true
                }
            }
        }

        // Connect to LogicService

        val intent = Intent(this, LogicService::class.java)
        this.startService(intent)
        val status = this.bindService(intent, serviceConnectionHandler, BIND_AUTO_CREATE)

        if (!status) {
            throw Exception("Activity: bindService() failed")
        }
    }

    /**
     * Run this code when LogicService connects to the MainActivity. App will connect to
     * the server if automatic connection is requested.
     */
    fun handleServiceConnect() {
        this.service?.also {
            this.status.text = it.getStatus()
            this.serviceServerConnectedUpdate(it.getServerConnected())
            it.setCurrentConnectedActivity(this)

            if (this.connectAutomatically) {
                // Connect to server automatically.
                this.handleConnectButtonOnClick()
                this.connectAutomatically = false
            }
        }
    }

    /**
     * LogicService will run this method when server connection state changes.
     *
     * @param serverConnected True when server is connected.
     */
    fun serviceServerConnectedUpdate(serverConnected: Boolean) {
        if (serverConnected) {
            this.connectButton.text = "Disconnect"
            this.address.isEnabled = false
        } else {
            this.connectButton.text = "Connect"
            this.address.isEnabled = true
        }
    }

    /**
     * LogicService will run this method when it's status text is updated.
     */
    fun serviceStatusUpdate(newStatus: String) {
        this.status.text = newStatus
        println("Status update: $newStatus")
    }

    /**
     * Handler for connect button.
     */
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

        /*
        Only run quit code when activity is not going to be recreated because of configuration
        change like device rotation.
         */
        if (this.isFinishing) {
            this.quitStarted = true
            val intent = Intent(this, LogicService::class.java)
            this.service?.also {
                it.disconnectActivity(this)
                this.unbindService(this.serviceConnectionHandler)
            }
            this.stopService(intent)

            val currentAddress = this.address.text.toString()
            if (this.preferenceAddress == null ||
                currentAddress != this.preferenceAddress) {
                val sharedPreferences = this.getPreferences(Context.MODE_PRIVATE)
                sharedPreferences.edit().putString(ADDRESS_KEY, currentAddress).apply()
            }
        }
    }

    /**
     * Save state text so it can be restored in onCreate method.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEXT, this.status.text.toString())

        super.onSaveInstanceState(outState)
    }

    companion object {
        /**
         * Key for storing state text to the Bundle.
         */
        const val STATE_TEXT = "status"

        /**
         * Key for storing IP address to the SharedPreferences.
         */
        const val ADDRESS_KEY = "address"

        /**
         * Key for storing automatic connecting setting to the SharedPreferences.
         */
        const val CONNECT_AUTOMATICALLY_KEY = "connect_automatically"
    }

}