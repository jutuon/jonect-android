/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper


class LogicServiceBinder(var logicService: LogicService): Binder() {
    fun getService(): LogicService {
        return this.logicService
    }
}

class LogicService: Service() {
    private lateinit var serviceBinder: LogicServiceBinder
    private lateinit var logicThread: LogicThread

    private lateinit var status: String
    private var currentConnectedActivity: MainActivity? = null
    private var serverConnected = false
    private var serverConnectDisconnectRunning = false

    override fun onCreate() {
        this.serviceBinder = LogicServiceBinder(this)
        this.logicThread = LogicThread(ServiceHandle(this))

        this.status = ""
        this.currentConnectedActivity = null
        this.serverConnected = false
        this.serverConnectDisconnectRunning = false

        this.logicThread.start()
        println("Service: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
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

    fun getServerConnected(): Boolean {
        return this.serverConnected
    }

    fun getServerConnectDisconnectRunning(): Boolean {
        return this.serverConnectDisconnectRunning
    }

    fun sendConnectMessage(address: String)  {
        this.logicThread.sendConnectMessage(address)
        this.serverConnectDisconnectRunning = true
    }

    fun sendDisconnectMessage() {
        this.logicThread.sendDisconnectMessage()
        this.serverConnectDisconnectRunning = true
    }

    fun setStatus(statusEvent: ILogicStatusEvent) {
        when (statusEvent) {
            is ConnectedEvent -> {
                this.serverConnected = true
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
            is ConnectionError -> {
                this.serverConnected = false
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
            is DisconnectedEvent -> {
                this.serverConnected = false
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
        }

        this.status = statusEvent.toString()
        this.currentConnectedActivity?.also {
            it.serviceStatusUpdate(status)
        }
    }

    /**
     * Sets current connected activity. Connected activity will get
     * service status updates.
     */
    fun setCurrentConnectedActivity(activity: MainActivity) {
        this.currentConnectedActivity = activity
    }

    /**
     * Disconnects current connected activity if argument activity is
     * the current connected activity.
     */
    fun disconnectActivity(activity: MainActivity) {
        if (this.currentConnectedActivity == activity) {
            this.currentConnectedActivity = null
        }
    }
}

class ServiceHandle(private val service: LogicService) {
    fun updateStatus(statusEvent: ILogicStatusEvent) {
        Handler(Looper.getMainLooper()).post {
            this.service.setStatus(statusEvent)
        }
    }
}