/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder


class LogicServiceBinder(var logicService: LogicService): Binder() {
    fun getService(): LogicService {
        return this.logicService
    }
}

class LogicService: Service() {
    private val serviceBinder = LogicServiceBinder(this)

    private val logicThread = LogicThread()
    private var status = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onCreate() {
        logicThread.start()
        println("Service: created")
    }

    override fun onBind(p0: Intent?): IBinder? {
        return this.serviceBinder
    }

    override fun onDestroy() {
        this.logicThread.sendQuitRequest()
        this.logicThread.join()
        println("Service: quit ready")
    }

    fun getStatus(): String {
        return this.status
    }

    fun sendConnectMessage(address: String)  {
        this.logicThread.sendConnectMessage(address)
        this.status = address
    }

}