package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.io.Closeable
import java.util.WeakHashMap

/** 通信模式在进程内共享资源所有权，旧会话释放不能恢复仍被新会话使用的模式。 */
internal class TelephonyAudioMode(private val manager: AudioManager?, private val report: (String) -> Unit) : Closeable {
    private val streams = mutableSetOf<AudioStreamId>()
    private var closed = false
    private class Lease { val owners = mutableSetOf<Any>(); var owned = false }

    @Synchronized fun acquire(id: AudioStreamId) {
        if (closed || !streams.add(id) || streams.size > 1) return
        val audio = manager ?: return
        synchronized(leases) {
            val lease = leases.getOrPut(audio) { Lease() }
            lease.owners.add(this)
            try {
                val previous = audio.mode
                if (lease.owned && previous == AudioManager.MODE_IN_COMMUNICATION) return
                if (previous != AudioManager.MODE_NORMAL) {
                    lease.owned = false
                    emit("Audio: call mode unchanged current=$previous owned=false")
                    return
                }
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                lease.owned = audio.mode == AudioManager.MODE_IN_COMMUNICATION
                emit("Audio: call mode entered current=${audio.mode} owned=${lease.owned}")
            } catch (error: RuntimeException) { emit("Audio: call mode unavailable error=${error.javaClass.simpleName}") }
        }
    }

    @Synchronized fun release(id: AudioStreamId): Boolean {
        streams.remove(id)
        if (streams.isNotEmpty()) return false
        return restore()
    }

    private fun restore(): Boolean {
        val audio = manager ?: return true
        return synchronized(leases) {
            val lease = leases.getOrPut(audio) { Lease() }
            lease.owners.remove(this)
            if (lease.owners.isNotEmpty()) return@synchronized false
            try {
                val current = audio.mode
                if (lease.owned && current == AudioManager.MODE_IN_COMMUNICATION) {
                    audio.mode = AudioManager.MODE_NORMAL
                    if (audio.mode != AudioManager.MODE_NORMAL) {
                        emit("Audio: call mode restore pending current=${audio.mode}")
                        return@synchronized false
                    }
                    lease.owned = false
                    emit("Audio: call mode restored current=${audio.mode}")
                } else {
                    // 原车改变模式后放弃所有权，不覆盖其他通话；普通模式才能恢复本地音乐。
                    lease.owned = false
                    emit("Audio: call mode restore skipped current=$current")
                }
                audio.mode == AudioManager.MODE_NORMAL
            } catch (error: RuntimeException) {
                emit("Audio: call mode restore failed error=${error.javaClass.simpleName}")
                false
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        streams.clear()
        restore()
    }
    private fun emit(message: String) { runCatching { report(message) } }
    private companion object { val leases = WeakHashMap<AudioManager, Lease>() }
}
