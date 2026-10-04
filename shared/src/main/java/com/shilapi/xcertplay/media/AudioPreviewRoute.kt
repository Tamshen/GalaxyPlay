package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioTrack
import java.io.Closeable

/** 试听复用会话的设备选择；默认交给系统，显式开启后才尝试 BUS。 */
class AudioPreviewRoute(context: Context, track: AudioTrack, role: AudioOutputRole,
                        sampleRate: Int, channels: Int, choice: Int,
                        preferBus: Boolean = false, report: (String) -> Unit) : Closeable {
    private val routing = L7AudioRouting(context.applicationContext, preferBus, report)
    private val binding = routing.bind(track,
        AudioOutputPolicy.routingChannel(role.channel, choice),
        false, sampleRate, channels, useBus = !AudioOutputPolicy.isLegacy(choice))

    fun reportActual() = binding.reportActual()
    override fun close() = routing.close()
}
