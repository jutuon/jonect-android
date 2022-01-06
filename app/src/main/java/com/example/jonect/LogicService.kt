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


/**
 * Binder for LogicService. Used when Activity connects to the LogicService.
 */
class LogicServiceBinder(private var logicService: LogicService): Binder() {
    fun getService(): LogicService {
        return this.logicService
    }
}

/**
 * App's service object. This will run in a same thread as app's main Activity.
 */
class LogicService: Service() {
    private lateinit var serviceBinder: LogicServiceBinder
    private lateinit var logicThread: LogicThread

    private lateinit var status: String
    private var currentConnectedActivity: MainActivity? = null
    private var serverConnected = false
    private var serverConnectDisconnectRunning = false

    /**
     * Service's onCreate method. Check Android's documentation when this is called.
     */
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

    /**
     * Service's onStartCommand method. Check Android's documentation for more information about this method.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    /**
     * Service's onBind method. Check Android's documentation for more information about this method.
     */
    override fun onBind(p0: Intent?): IBinder {
        return this.serviceBinder
    }

    /**
     * Service's onDestroy method. Check Android's documentation for more information about this method.
     */
    override fun onDestroy() {
        this.logicThread.sendQuitRequest()
        this.logicThread.join()
        println("Service: quit ready")
    }

    /**
     * Get current status text of LogicService.
     */
    fun getStatus(): String {
        return this.status
    }

    /**
     * If true is returned then server is connected.
     * If false is returned then server is not connected.
     */
    fun getServerConnected(): Boolean {
        return this.serverConnected
    }

    /**
     * If true is returned then connecting to the server or disconnecting from it is currently ongoing.
     */
    fun getServerConnectDisconnectRunning(): Boolean {
        return this.serverConnectDisconnectRunning
    }

    /**
     * Connect to the server.
     *
     * @param address Server IP address.
     */
    fun sendConnectMessage(address: String)  {
        this.logicThread.sendConnectMessage(address)
        this.serverConnectDisconnectRunning = true
    }

    /**
     * Disconnect from the server.
     */
    fun sendDisconnectMessage() {
        this.logicThread.sendDisconnectMessage()
        this.serverConnectDisconnectRunning = true
    }

    /**
     * Set logic status information.
     */
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
        if (this.currentConnectedActivity === activity) {
            this.currentConnectedActivity = null
        }
    }
}

/**
 * Handle to Android service object. Sends events to service object thread which
 * is app's main thread.
 */
class ServiceHandle(private val service: LogicService) {
    /**
     * Update status text which is displayed in the UI.
     */
    fun updateStatus(statusEvent: ILogicStatusEvent) {
        Handler(Looper.getMainLooper()).post {
            this.service.setStatus(statusEvent)
        }
    }
}