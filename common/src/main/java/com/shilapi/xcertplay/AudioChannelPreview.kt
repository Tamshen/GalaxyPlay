package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AudioPreviewRoute
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioOutputPolicy
import com.shilapi.xcertplay.media.L7FactoryAudioProfile
import com.shilapi.xcertplay.media.LegacyAudioFallback
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** 独立试听，不改持久化设置；关闭、停止或换流时取消旧任务并释放输出。 */
internal class AudioChannelPreview(
    private val onUnavailable: (Int) -> Unit,
    private val context: Context? = null,
    private val onResult: (Int, Int) -> Unit = { _, _ -> },
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-channel-preview").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private var pending: Future<*>? = null
    @Volatile private var closed = false

    fun play(channel: Int, navigation: Boolean) = play(channel,
        if (navigation) AudioOutputRole.NAVIGATION else AudioOutputRole.MEDIA)

    fun play(channel: Int, role: AudioOutputRole) {
        if (closed) return
        require(AudioOutputPolicy.valid(channel))
        val request = generation.incrementAndGet()
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
        pending = worker.submit {
            var track: AudioTrack? = null
            var route: AudioPreviewRoute? = null
            try {
                if (closed || generation.get() != request) return@submit
                val channels = role.channels
                val pcm = tone(channels)
                val built = createTrack(channel, role)
                track = built
                check(built.state == AudioTrack.STATE_INITIALIZED) { "Audio output did not initialize" }
                if (closed || generation.get() != request) return@submit
                activeTrack.set(built)
                built.setVolume(0.6f)
                context?.let {
                    route = AudioPreviewRoute(it, built, role, SAMPLE_RATE, channels, channel,
                        preferBus = AirPlayPersistence.loadL7AudioBusEnabled(it),
                        focusEnabled = AirPlayPersistence.loadAudioFocusEnabled(it)) { line ->
                        L7DebugLog.record("Audio preview stream=$channel $line")
                    }
                }
                built.play()
                var written = 0
                while (written < pcm.size && !closed && generation.get() == request) {
                    val count = built.write(
                        pcm, written, minOf(4096, pcm.size - written), AudioTrack.WRITE_BLOCKING,
                    )
                    check(count > 0) { "Could not write preview tone" }
                    written += count
                }
                if (!closed && generation.get() == request && written == pcm.size) {
                    route?.reportActual()
                    val device = runCatching { built.routedDevice }.getOrNull()
                    Log.i(TAG, "Preview sent channel=$channel role=$role usage=${built.audioAttributes.usage} deviceId=${device?.id ?: -1} type=${device?.type ?: -1}")
                    L7DebugLog.record("Audio preview sent stream=$channel role=$role usage=${built.audioAttributes.usage} deviceId=${device?.id ?: -1} type=${device?.type ?: -1}")
                    mainHandler.post {
                        if (!closed && generation.get() == request) onResult(device?.id ?: -1, device?.type ?: -1)
                    }
                }
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Log.w(TAG, "Channel preview unavailable channel=$channel", error)
                L7DebugLog.record("Audio preview failed stream=$channel error=${error.javaClass.simpleName}")
                mainHandler.post {
                    if (!closed && generation.get() == request) onUnavailable(channel)
                }
            } finally {
                activeTrack.compareAndSet(track, null)
                route?.close()
                track?.let { runCatching { it.stop() }; it.release() }
            }
        }
    }

    private fun createTrack(channel: Int, role: AudioOutputRole): AudioTrack {
        val attributes = L7FactoryAudioProfile.load().attributes(role, channel)
        val channels = role.channels
        val mask = if (role.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "No PCM output buffer is available" }
        val bufferSize = maxOf(minimum, SAMPLE_RATE / 10 * channels * 2)
        fun usageTrack() = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferSize).build()
        return if (AudioOutputPolicy.isLegacy(channel)) LegacyAudioFallback.build(
            createLegacy = { AudioTrack(channel, SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM) },
            isInitialized = { it.state == AudioTrack.STATE_INITIALIZED }, release = { it.release() },
            createFallback = {
                L7DebugLog.record("Audio preview stream=$channel rejected; fallback=usage ${attributes.usage}")
                usageTrack()
            },
        ) else usageTrack()
    }

    fun stop() {
        generation.incrementAndGet()
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
    }

    override fun close() {
        closed = true
        stop()
        worker.shutdownNow()
    }

    private fun tone(channels: Int): ByteArray {
        val sampleCount = SAMPLE_RATE * TONE_MILLIS / 1000
        val fadeSamples = SAMPLE_RATE / 100
        return ByteArray(sampleCount * channels * 2).also { pcm ->
            for (index in 0 until sampleCount) {
                val fade = minOf(1.0, index.toDouble() / fadeSamples,
                    (sampleCount - index - 1).toDouble() / fadeSamples).coerceAtLeast(0.0)
                val sample = (sin(2.0 * Math.PI * 880.0 * index / SAMPLE_RATE) *
                    fade * Short.MAX_VALUE * 0.45).toInt()
                repeat(channels) { channel ->
                    val offset = (index * channels + channel) * 2
                    pcm[offset] = sample.toByte()
                    pcm[offset + 1] = (sample ushr 8).toByte()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "DiPlayAudioPreview"
        private const val SAMPLE_RATE = 48_000
        private const val TONE_MILLIS = 2_000
    }
}
