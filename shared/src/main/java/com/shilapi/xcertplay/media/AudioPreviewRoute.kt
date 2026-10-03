package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioTrack
import java.io.Closeable

/** 试听复用会话的设备选择，不能把系统默认输出冒充自动 BUS 路由。 */
class AudioPreviewRoute(context: Context, track: AudioTrack, role: AudioOutputRole,
                        sampleRate: Int, channels: Int, choice: Int,
                        report: (String) -> Unit) : Closeable {
    private val routing = L7AudioRouting(context.applicationContext, report)
    private val binding = routing.bind(track,
        AudioOutputPolicy.routingChannel(role.channel, choice),
        false, sampleRate, channels, useBus = !AudioOutputPolicy.isLegacy(choice))

    fun reportActual() = binding.reportActual()
    override fun close() = routing.close()
}
