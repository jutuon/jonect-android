package com.example.jonect

import android.media.*
import android.os.Build
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

interface IAudioMessage

data class AudioStreamInfo(val address: String, val message: PlayAudioStream)

class AudioThread: Thread {
    private val handle: LogicMessageHandle
    private val streamInfo: AudioStreamInfo

    private val audioMessages: BlockingQueue<IAudioMessage> =
        ArrayBlockingQueue(32)
    private var messageNotificationPipe: Pipe
    private var messageNotificationSink: Pipe.SinkChannel

    private var requestQuitPipe: Pipe
    private var requestQuitSink: Pipe.SinkChannel

    private val notificationByte = ByteBuffer.allocate(1)

    constructor(handle: LogicMessageHandle, streamInfo: AudioStreamInfo): super() {
        this.handle = handle
        this.streamInfo = streamInfo

        this.requestQuitPipe = SelectorProvider.provider().openPipe()
        this.requestQuitSink = this.requestQuitPipe.sink()

        this.messageNotificationPipe = SelectorProvider.provider().openPipe()
        this.messageNotificationSink = this.messageNotificationPipe.sink()
    }

    override fun run() {
        val audioPlayer = AudioPlayer(
            this.handle,
            this.streamInfo,
            this.audioMessages,
            this.messageNotificationPipe.source(),
            this.requestQuitPipe.source(),
        )
        audioPlayer.start()
        audioPlayer.closeSystemResources()
    }

    private fun sendQuitRequest() {
        this.notificationByte.clear()
        this.requestQuitSink.write(this.notificationByte)
    }

    fun runQuit() {
        this.sendQuitRequest()
        this.join()
        this.requestQuitSink.close()
        this.messageNotificationSink.close()
    }

    fun sendAudioMessage(message: IAudioMessage) {
        this.audioMessages.put(message)
        this.notificationByte.clear()
        this.messageNotificationSink.write(this.notificationByte)
    }
}

class AudioPlayer(
    private val handle: LogicMessageHandle,
    private val streamInfo: AudioStreamInfo,
    private val messages: BlockingQueue<IAudioMessage>,
    private val messageNotifications: Pipe.SourceChannel,
    private val quitRequested: Pipe.SourceChannel,
) {
    private val notificationBuffer = ByteBuffer.allocate(1)
    private var audioTrackPlaying = false

    fun start() {
        println("AudioPlayer: start")

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
            else -> {
                // Send error and wait quit.
                this.handle.sendAudioStreamError("Unsupported audio format: ${this.streamInfo.message.format}")
                this.waitQuitRequest()
                return
            }
        }

        val audioTrack = AudioTrack(
            audioAttributes,
            audioFormat.build(),
            4096,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )

        val inetAddress = InetSocketAddress(this.streamInfo.address, this.streamInfo.message.port)
        val socket: SocketChannel = try {
            SocketChannel.open(inetAddress)
        } catch (e: ConnectException) {
            // Send error and wait quit.
            this.handle.sendAudioStreamError("Audio stream connect error")
            this.waitQuitRequest()
            return
        }

        this.messageNotifications.configureBlocking(false)
        this.quitRequested.configureBlocking(false)
        socket.configureBlocking(false)

        val selector = Selector.open()
        val messageNotificationKey = this.messageNotifications.register(selector, SelectionKey.OP_READ)
        val quitRequestedKey = this.quitRequested.register(selector, SelectionKey.OP_READ)
        val socketKey = socket.register(selector, SelectionKey.OP_READ)

        // Use buffer which matches current framesize: buffer size % (bytes per sample * channels) == 0
        var currentAudioBuffer = ByteBuffer.allocate(512)

        while (true) {
            val result = selector.select()

            if (result == 0) {
                continue
            }

            if (selector.selectedKeys().remove(socketKey)) {
                if (socketKey.isReadable) {
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

                }
            }

            if (selector.selectedKeys().remove(quitRequestedKey)) {
                if (this.checkQuitRequest()) {
                    break
                }
            }
        }

        audioTrack.pause()
        audioTrack.release()
        selector.close()
        socket.close()

        println("AudioPlayer: quit")
    }

    fun closeSystemResources() {
        this.messageNotifications.close()
        this.quitRequested.close()
    }

    private fun handleData(buffer: ByteBuffer, audioTrack: AudioTrack): Quit? {
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

        if (!this.audioTrackPlaying) {
            audioTrack.play()
            this.audioTrackPlaying = true
        }

        return null
    }

    private fun checkQuitRequest(): Boolean {
        this.notificationBuffer.clear()
        val result = this.quitRequested.read(this.notificationBuffer)
        if (result == -1) {
            // EOF
            throw Exception("AudioPlayer: unexpected EOF")
        } else if (result == 0) {
            return false
        }

        return true
    }

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

    private fun tryReadMessage(): IAudioMessage? {
        this.notificationBuffer.clear()
        val result = this.messageNotifications.read(this.notificationBuffer)

        if (result == -1) {
            // EOF
            throw Exception("AudioPlayer: unexpected EOF")
        } else if (result == 0) {
            return null
        }

        return this.messages.take()
    }
}

private class Quit