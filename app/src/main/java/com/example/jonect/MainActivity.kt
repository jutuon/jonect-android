/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button = findViewById<Button>(R.id.button_connect)
        button.setOnClickListener {
            this.handleConnectButtonOnClick()
        }

        this.address = findViewById(R.id.edit_text_server_address)
        this.status = findViewById(R.id.text_view_status)
    }

    private fun handleConnectButtonOnClick() {
        val address = this.address.text.toString()
        println(address)
        this.status.text = address
    }
}