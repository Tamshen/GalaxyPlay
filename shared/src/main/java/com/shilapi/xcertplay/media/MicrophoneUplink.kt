package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val audioRouting: L7AudioRouting? = null,
    private val report: (String) -> Unit = {},
    private val callProcessingEnabled: Boolean = true,
    private val factorySource: Int? = null,
    private val onEnded: () -> Unit = {},
) : Closeable {
    @Volatile private var routeBinding: L7AudioRouting.Binding? = null
    private var packetsSent = 0L
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private var thread: Thread? = null
    private var effects: TelephonyAudioEffects? = null
    private val ended = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        ended.set(false)

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            release()
            return false
        }

        val source = when (config.audioType.lowercase()) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            release()
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // 博越配置优先厂商输入源；权限拒绝、初始化或启动失败后释放，再尝试标准源。
        val nextRecorder = listOfNotNull(factorySource, source).distinct().firstNotNullOfOrNull { candidate ->
            var built: AudioRecord? = null
            var stage = MicrophoneFailureStage.RECORDER_CREATION
            try {
                val record = AudioRecord.Builder().setAudioSource(candidate)
                    .setAudioFormat(AndroidAudioFormat.Builder().setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate).setChannelMask(channelMask).build())
                    .setBufferSizeInBytes(bufferSize).build()
                built = record
                stage = MicrophoneFailureStage.RECORDER_INITIALIZATION
                check(record.state == AudioRecord.STATE_INITIALIZED)
                stage = MicrophoneFailureStage.RECORDING
                if (callProcessingEnabled && config.audioType.equals("telephony", true)) {
                    effects = TelephonyAudioEffects(record.audioSessionId, report)
                }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                runCatching { report("Audio: microphone source=$candidate factory=${candidate == factorySource}") }
                record
            } catch (error: Exception) {
                stats.failure(stage, error)
                effects?.close(); effects = null
                runCatching { built?.release() }
                null
            }
        }
        if (nextRecorder == null) {
            nextEncoder?.close()
            release()
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            effects?.close(); effects = null
            nextRecorder.release()
            nextEncoder?.close()
            release()
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            val channel = when (config.audioType.lowercase()) {
                "telephony" -> AudioChannel.PHONE
                "speechrecognition" -> AudioChannel.ASSISTANT
                else -> AudioChannel.NAVIGATION
            }
            routeBinding = audioRouting?.bind(nextRecorder, channel, true, config.sampleRate, config.channels)
            stats.started(runCatching { nextRecorder.routedDevice?.type }.getOrNull())
            routeBinding?.reportActual()
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels} " +
                    "frameMs=${config.frameMillis} port=${config.port}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        val signal = MicrophoneSignalStats()
        var bytesRead = 0L
        var windowStart = System.nanoTime()
        fun logStats(ended: Boolean = false) {
            routeBinding?.reportActual()
            val line = "Audio: microphone type=${config.audioType} rate=${config.sampleRate} " +
                "channels=${config.channels} recordingState=${recorder.recordingState} " +
                "bytes=$bytesRead samples=${signal.samples} zeroPercent=${signal.zeroPercent} " +
                "rms=${signal.rms} peak=${signal.peak} packetsSent=$packetsSent ended=$ended"
            Log.i(TAG, line)
            runCatching { report(line) }
            bytesRead = 0
            signal.resetWindow()
            windowStart = System.nanoTime()
        }
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                stats.flush { recorder.routedDevice?.type }
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    if (System.nanoTime() - windowStart >= STATS_WINDOW_NS) logStats()
                    continue
                }
                signal.add(readBuffer, count)
                bytesRead += count
                if (System.nanoTime() - windowStart >= STATS_WINDOW_NS) logStats()
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            runCatching { logStats(ended = true) }
            stats.flush(ended = true) { recorder.routedDevice?.type }
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            packetsSent++
            stats.sent()
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) { stats.sendFailed(); throw error }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        effects?.close()
        effects = null
        runCatching { routeBinding?.close() }
        routeBinding = null
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        runCatching { currentEncoder?.close() }
        if (ended.compareAndSet(false, true)) runCatching { onEnded() }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val STATS_WINDOW_NS = 5_000_000_000L
    }
}
