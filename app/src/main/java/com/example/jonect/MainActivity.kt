/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

// TODO: Current implementation will not work if multiple
//       activities are started. Use a service to store logic thread.

// UiState will be empty after app process is killed.
object UiState {
    var initDone = false
    lateinit var address: String
    lateinit var status: String
    lateinit var logicThread: LogicThread;

    fun init() {
        this.initDone = true
        this.address = "10.0.2.2"
        this.status = ""
        this.logicThread = LogicThread();
        this.logicThread.start()
        println("Logic start")
    }

    fun quit() {
        UiState.logicThread.sendQuitRequest()
        UiState.logicThread.join()
        UiState.initDone = false
        println("Logic quit ready")
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button = this.findViewById<Button>(R.id.button_connect)
        button.setOnClickListener {
            this.handleConnectButtonOnClick()
        }

        this.address = this.findViewById(R.id.edit_text_server_address)
        this.status = this.findViewById(R.id.text_view_status)

        if (!UiState.initDone) {
            UiState.init()
        }

        this.address.text.clear()
        this.address.text.append(UiState.address)
        this.status.text = UiState.status
    }

    private fun handleConnectButtonOnClick() {
        val address = this.address.text.toString()
        this.status.text = address
        UiState.logicThread.sendConnectMessage(address)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Store activity UI data for activity recreation.
        UiState.address = this.address.text.toString()
        UiState.status = this.status.text.toString()

        if (this.isFinishing) {
            UiState.quit()
        }
    }
}