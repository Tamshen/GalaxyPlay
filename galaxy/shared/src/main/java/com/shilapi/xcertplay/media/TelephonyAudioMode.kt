package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.io.Closeable

/** 只从普通模式进入通信模式；不覆盖原车通话，也不恢复已被其他来源改变的模式。 */
internal class TelephonyAudioMode(
    private val manager: AudioManager?,
    private val report: (String) -> Unit,
) : Closeable {
    private val streams = mutableSetOf<AudioStreamId>()
    private var owned = false
    private var closed = false

    @Synchronized fun acquire(id: AudioStreamId) {
        if (closed || !streams.add(id) || streams.size > 1) return
        val audio = manager ?: return
        try {
            val previous = audio.mode
            if (previous != AudioManager.MODE_NORMAL) {
                emit("Audio: call mode unchanged current=$previous owned=false")
                return
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            owned = true
            emit("Audio: call mode entered current=${audio.mode} owned=true")
        } catch (error: RuntimeException) {
            emit("Audio: call mode unavailable error=${error.javaClass.simpleName}")
        }
    }

    @Synchronized fun release(id: AudioStreamId) {
        if (!streams.remove(id) || streams.isNotEmpty()) return
        restore()
    }

    private fun restore() {
        if (!owned) return
        owned = false
        val audio = manager ?: return
        try {
            if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audio.mode = AudioManager.MODE_NORMAL
                emit("Audio: call mode restored current=${audio.mode}")
            } else emit("Audio: call mode restore skipped current=${audio.mode}")
        } catch (error: RuntimeException) {
            emit("Audio: call mode restore failed error=${error.javaClass.simpleName}")
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        streams.clear()
        restore()
    }

    private fun emit(message: String) { runCatching { report(message) } }
}
