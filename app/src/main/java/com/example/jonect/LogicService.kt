/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.*
import java.lang.NumberFormatException


/**
 * Binder for LogicService. Used when Activity connects to the LogicService.
 */
class LogicServiceBinder(private var logicService: LogicService): Binder() {
    fun getService(): LogicService {
        return this.logicService
    }
}

class UsbDisconnectedReceiver(private var service: LogicService): BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        println("USB disconnected")
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

    private lateinit var usbLogic: UsbLogic
    private lateinit var usbDisconnectedReceiver: BroadcastReceiver

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        const val SERVICE_NOTIFICATION_CHANNEL_ID = "Jonect"
        const val SERVICE_NOTIFICATION_CHANNEL_NAME = "Jonect notifications"
    }

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

        val usbManager = this.getSystemService(Context.USB_SERVICE) as UsbManager
        this.usbLogic = UsbLogic(usbManager)

        this.usbDisconnectedReceiver = UsbDisconnectedReceiver(this)
        this.registerReceiver(this.usbDisconnectedReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED))

        println("Service: created")
    }

    /**
     * Service's onStartCommand method. Check Android's documentation for more information about this method.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = Notification.Builder(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_NOTIFICATION_CHANNEL_ID,
                SERVICE_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW)
            channel.description = "Jonect notifications"

            val notificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            builder.setChannelId(channel.id)
        }

        val notification = builder.setPriority(Notification.PRIORITY_LOW)
            .setContentTitle("Jonect")
            .setContentText("Jonect service")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setTicker("Jonect service started")
            .build()

        this.startForeground(SERVICE_NOTIFICATION_ID, notification)

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
        this.unregisterReceiver(this.usbDisconnectedReceiver)

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

    fun connectUsbAccessory() {
        println("connectUsbAccessory")
        requestUsbAccessory()
    }

    fun requestUsbAccessory() {
        val fd = this.usbLogic.connectAccessory()

        if (fd != null) {
            this.logicThread.sendUsbAccessoryFileDescriptor(fd)
        } else {
            this.logicThread.sendUsbAccessoryFileDescriptor(-1)
        }
    }

    /**
     * Set logic status information.
     */
    fun setStatus(statusEvent: ILogicStatusEvent) {
        when (statusEvent) {
            is DeviceConnectionEstablished -> {
                this.serverConnected = true
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
            is DeviceConnectionDisconnectedWithError -> {
                this.serverConnected = false
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
            is DeviceConnectionDisconnected -> {
                this.serverConnected = false
                this.serverConnectDisconnectRunning = false
                this.currentConnectedActivity?.also {
                    it.serviceServerConnectedUpdate(this.serverConnected)
                }
            }
            is RustLogicConnectionError -> {
                this.serverConnected = false
                this.serverConnectDisconnectRunning = true
                this.logicThread.disableNonQuitMessageSending()
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

    fun sendAudioInfoToLogic(audioInfo: AudioInfo) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRateString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)

        // TODO: Display errors in UI?

        val sampleRate = if (sampleRateString != null) {
            try {
                Integer.parseInt(sampleRateString)
            } catch (e: NumberFormatException) {
                println("Error in sendAudioInfoToLogic when parsing sampleRateString: $e")
                return
            }
        } else {
            println("Error: AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE is null.")
            return
        }

        val framesPerBufferString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        val framesPerBuffer = if (framesPerBufferString != null) {
            try {
                Integer.parseInt(framesPerBufferString)
            } catch (e: NumberFormatException) {
                println("Error in sendAudioInfoToLogic when parsing framesPerBufferString: $e")
                return
            }
        } else {
            println("Error: AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER is null.")
            return
        }

        audioInfo.native_sample_rate = sampleRate
        audioInfo.frames_per_burst = framesPerBuffer

        this.logicThread.sendAudioInfo(audioInfo)
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

    fun requestAudioInfo(audioInfo: AudioInfo) {
        Handler(Looper.getMainLooper()).post {
            this.service.sendAudioInfoToLogic(audioInfo)
        }
    }

    fun requestUsbAccessory() {
        Handler(Looper.getMainLooper()).post {
            this.service.requestUsbAccessory()
        }
    }
}