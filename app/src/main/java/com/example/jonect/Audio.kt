package com.example.jonect

import android.media.*
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.lang.Exception
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Marker type for messages which can be sent to the AudioThread.
 */
interface IAudioMessage

/**
 * Type for storing information about the audio stream which AudioThread will play.
 *
 * @param address IP address of the server.
 */
data class AudioStreamInfo(val address: String, val message: PlayAudioStream)

/**
 * Handle to AudioThread.
 */
class AudioThread: Thread {
    private val handle: LogicMessageHandle
    private val streamInfo: AudioStreamInfo

    private val audioMessages: BlockingQueue<IAudioMessage> =
        ArrayBlockingQueue(32)
    private var messageNotificationPipe: Pipe
    private var messageNotificationSink: Pipe.SinkChannel

    private var requestQuitPipe: Pipe
    private var requestQuitSink: Pipe.SinkChannel

    // Buffer for sending notifications. One byte represents one notification.
    private val notificationByte = ByteBuffer.allocate(1)


    constructor(handle: LogicMessageHandle, streamInfo: AudioStreamInfo): super() {
        this.handle = handle
        this.streamInfo = streamInfo

        this.requestQuitPipe = SelectorProvider.provider().openPipe()
        this.requestQuitSink = this.requestQuitPipe.sink()

        this.messageNotificationPipe = SelectorProvider.provider().openPipe()
        this.messageNotificationSink = this.messageNotificationPipe.sink()
    }

    /**
     * This code runs in a new thread.
     */
    override fun run() {
        val audioPlayer = AudioPlayer(
            this.handle,
            this.streamInfo,
            this.audioMessages,
            this.messageNotificationPipe.source(),
            this.requestQuitPipe.source(),
        )
        audioPlayer.start()
    }

    /**
     * Send quit request to AudioPlayer.
     */
    private fun sendQuitRequest() {
        this.notificationByte.clear()
        this.requestQuitSink.write(this.notificationByte)
    }

    /**
     * Wait until AudioThread is closed.
     */
    fun runQuit() {
        this.sendQuitRequest()
        this.join()
        this.requestQuitSink.close()
        this.messageNotificationSink.close()
    }

    /**
     * Send message to AudioThread
     */
    fun sendAudioMessage(message: IAudioMessage) {
        this.audioMessages.put(message)
        this.notificationByte.clear()
        this.messageNotificationSink.write(this.notificationByte)
    }
}

/**
 * Component for playing audio. This component has two inbound communication "channels".
 * The first is for messages (created with BlockingQueue and Pipe) and the second is for
 * quit request notification (created with Pipe).
 *
 * Java's select system does not work with BlockingQueue so Pipe is used for notifications
 * about new messages waiting in the BlockingQueue.
 */
class AudioPlayer(
    private val handle: LogicMessageHandle,
    private val streamInfo: AudioStreamInfo,
    private val messages: BlockingQueue<IAudioMessage>,
    private val messageNotifications: Pipe.SourceChannel,
    private val quitRequested: Pipe.SourceChannel,
) {
    // Buffer for reading notifications. One notification is one byte.
    private val notificationBuffer = ByteBuffer.allocate(1)
    private var audioTrackPlaying = false
    private var underrunCount = 0
    private var initialBufferingCounter = 0

    private var opusDecoder: OpusDecoder? = null

    fun start() {
        println("AudioPlayer: start")

        // Set thread priority.

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: SecurityException) {
            val error = if (e.message != null) {
                e.message
            } else {
                "no error message."
            }

            println("Setting thread priority failed: $error")
        }

        val threadPriority = Process.getThreadPriority(Process.myTid());
        println("Current thread priority: $threadPriority")

        // Setup audio playing and possible Opus decoding.

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val audioFormat = AudioFormat.Builder()

        when (val channelCount = this.streamInfo.message.channels.toInt()) {
            1 -> audioFormat.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            2 -> audioFormat.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            else -> {
                // Send error and wait quit.
                this.handle.sendAudioStreamError("Unsupported channel count $channelCount")
                this.waitQuitRequest()
                return
            }
        }

        audioFormat.setSampleRate(this.streamInfo.message.rate)

        when (this.streamInfo.message.format) {
            "pcm-s16le" -> {
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                    // Send error and wait quit.
                    this.handle.sendAudioStreamError("ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN")
                    this.waitQuitRequest()
                    return
                } else {
                    audioFormat.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                }
            }
            "opus" -> {
                this.opusDecoder = OpusDecoder()
                this.opusDecoder!!.startDecodingThread(streamInfo.address, streamInfo.message.port)
                // TODO: It is possible that Rust code has not yet created the server socket
                //  for sending PCM data to here.
                audioFormat.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            }
            else -> {
                // Send error and wait quit.
                this.handle.sendAudioStreamError("Unsupported audio format: ${this.streamInfo.message.format}")
                this.waitQuitRequest()
                return
            }
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            this.streamInfo.message.rate,
            this.streamInfo.message.channels.toInt(),
            AudioFormat.ENCODING_PCM_16BIT,
        )

        println("AudioTrack min buffer size is $minBufferSize")

        val nativeSampleRate = AudioPlayer.getNativeSampleRate()

        println("AudioTrack native sample rate is $nativeSampleRate")

        var audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)

            if (nativeSampleRate == this.streamInfo.message.rate) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                println("AudioTrack.PERFORMANCE_MODE_LOW_LATENCY is enabled")
            }
            builder.build()
        } else {
            AudioTrack(
                audioAttributes,
                audioFormat.build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }

        // Create the socket for reading PCM data.

        var address = this.streamInfo.address
        var port = this.streamInfo.message.port

        if (this.opusDecoder != null) {
            // Connect to Opus decoder thread.
            address = "127.0.0.1"
            port = 12345
        }

        val inetAddress = InetSocketAddress(address, port)
        val socket: SocketChannel = try {
            SocketChannel.open(inetAddress)
        } catch (e: ConnectException) {
            // Send error and wait quit.
            this.handle.sendAudioStreamError("Audio stream connect error")
            this.waitQuitRequest()
            return
        }

        // Buffer for reading the PCM data socket.
        // Use buffer which matches current framesize: buffer size % (bytes per sample * channels) == 0
        val socketBufferSize = 32
        var currentAudioBuffer = ByteBuffer.allocate(socketBufferSize)

        // Configure Java's select system.

        this.messageNotifications.configureBlocking(false)
        this.quitRequested.configureBlocking(false)
        socket.configureBlocking(false)

        val selector = Selector.open()
        val messageNotificationKey = this.messageNotifications.register(selector, SelectionKey.OP_READ)
        val quitRequestedKey = this.quitRequested.register(selector, SelectionKey.OP_READ)
        val socketKey = socket.register(selector, SelectionKey.OP_READ)

        while (true) {
            val result = selector.select()

            if (result == 0) {
                continue
            }

            if (selector.selectedKeys().remove(socketKey)) {
                if (socketKey.isReadable) {
                    // PCM audio stream
                    val result = socket.read(currentAudioBuffer)

                    if (result == -1) {
                        this.handle.sendAudioStreamError("Audio stream EOF")
                        break
                    } else if (result != 0) {
                        if (!currentAudioBuffer.hasRemaining()) {

                            // When buffer is full of data write it to AudioTrack.
                            if (this.handleData(currentAudioBuffer, audioTrack) is Quit) {
                                this.handle.sendAudioStreamError("AudioTrack error")
                                break
                            }

                            currentAudioBuffer.clear()
                        }
                    }
                }
            }

            if (selector.selectedKeys().remove(messageNotificationKey)) {
                this.tryReadMessage()?.also {
                    // There is not currently any IAudioMessages implemented.
                }
            }

            if (selector.selectedKeys().remove(quitRequestedKey)) {
                if (this.checkQuitRequest()) {
                    break
                }
            }
        }

        // Close AudioPlayer.

        audioTrack.pause()
        audioTrack.release()
        selector.close()
        socket.close()

        if (this.opusDecoder != null) {
            this.opusDecoder!!.quit()
        }

        this.messageNotifications.close()
        this.quitRequested.close()

        println("AudioPlayer: quit")
    }

    companion object {
        /**
         * Get native sample rate of AudioFormat.ENCODING_PCM_16BIT.
         */
        fun getNativeSampleRate(): Int {
            return AudioTrack.getNativeOutputSampleRate(AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    private fun handleData(buffer: ByteBuffer, audioTrack: AudioTrack): Quit? {
        // Make sure that buffer read position is at the beginning.
        buffer.rewind()

        val writeCount = buffer.remaining()
        val result = audioTrack.write(
            buffer,
            writeCount,
            AudioTrack.WRITE_BLOCKING)

        if (result < 0) {
            println("AudioTrack write error $result detected")
            return Quit()
        }

        // TODO: Handle ERROR_DEAD_OBJECT.

        if (result < writeCount) {
            println("AudioTrack did not write all bytes")
            return Quit()
        }

        // Do some buffering before playing so there should be less buffer underruns
        // when audio starts playing.
        if (this.initialBufferingCounter < 4) {
            this.initialBufferingCounter += 1
            return null
        }

        if (!this.audioTrackPlaying) {
            println("AudioTrack: play")
            audioTrack.play()
            this.audioTrackPlaying = true
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (audioTrack.underrunCount > this.underrunCount) {
                    this.underrunCount = audioTrack.underrunCount
                    println("Underrun count: ${this.underrunCount}")
                }
            }
        }

        return null
    }

    /**
     * Return true if quit request was received.
     */
    private fun checkQuitRequest(): Boolean {
        this.notificationBuffer.clear()
        val result = this.quitRequested.read(this.notificationBuffer)
        if (result == -1) {
            // EOF
            throw Exception("AudioPlayer: unexpected EOF")
        } else if (result == 0) {
            // There was no byte available in the socket.
            return false
        }

        // One byte was received so quit is requested.
        return true
    }

    /**
     * Blocks until quit request is received.
     */
    private fun waitQuitRequest() {
        while (true) {
            this.notificationBuffer.clear()
            val result = this.quitRequested.read(this.notificationBuffer)
            if (result == -1) {
                // EOF
                throw Exception("AudioPlayer: unexpected EOF")
            } else if (result == 0) {
                continue
            }
            break
        }
    }

    /**
     * Try to read next IAudioMessage. If there is no message available then return null.
     */
    private fun tryReadMessage(): IAudioMessage? {
        this.notificationBuffer.clear()
        val result = this.messageNotifications.read(this.notificationBuffer)

        if (result == -1) {
            // EOF
            throw Exception("AudioPlayer: unexpected EOF")
        } else if (result == 0) {
            return null
        }

        // One byte was received so there is new message available.
        return this.messages.take()
    }
}

/**
 * If this object is returned close AudioPlayer.
 */
class Quit


/**
 * Opus decoder library interface.
 *
 * This class is not thread safe. Use only single instance of this class
 * and only from one thread.
 */
private class OpusDecoder {
    init {
        System.loadLibrary("jonect_android_rust")
    }

    /**
     * Start decoding thread.
     *
     * Thread opens local TCP port 12345.
     *
     * @param address Jonect server IP address.
     * @param port Jonect server Opus audio data port.
     */
    external fun startDecodingThread(address: String, port: Int)
    external fun quit()
}