package com.shilapi.xcertplay.media

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.Closeable

/** 仅为通话录音会话开启系统 AEC/NS；驱动不支持或拒绝时继续使用原录音路径。 */
internal class TelephonyAudioEffects(sessionId: Int, private val report: (String) -> Unit) : Closeable {
    private var effects = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    emit("microphone effect=$name enabled=true")
                    return effect
                }
                emit("microphone effect=$name could not be enabled status=$status")
            } else emit("microphone effect=$name unavailable")
        } catch (error: RuntimeException) {
            emit("microphone effect=$name unavailable error=${error.javaClass.simpleName}")
        }
        effect?.let { runCatching { it.release() } }
        return null
    }

    @Synchronized override fun close() {
        val current = effects
        effects = emptyList()
        current.forEach { runCatching { it.release() } }
    }

    private fun emit(message: String) {
        Log.i("xcertplay-usb", message)
        runCatching { report("Audio: $message") }
    }
}
