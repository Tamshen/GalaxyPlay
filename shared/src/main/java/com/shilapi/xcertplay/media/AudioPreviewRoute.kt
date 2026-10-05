package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioTrack
import java.io.Closeable

/** 试听复用会话的设备选择；默认交给系统，显式开启后才尝试 BUS。 */
class AudioPreviewRoute(context: Context, track: AudioTrack, role: AudioOutputRole,
                        sampleRate: Int, channels: Int, choice: Int,
                        preferBus: Boolean = false, focusEnabled: Boolean = true, template: AudioRoutingTemplate? = null, report: (String) -> Unit) : Closeable {
    private val routing = L7AudioRouting(context.applicationContext, template?.preferBus ?: preferBus, template, report)
    private val binding = routing.bind(track,
        AudioOutputPolicy.routingChannel(role.channel, choice),
        false, sampleRate, channels, useBus = !AudioOutputPolicy.isLegacy(choice))

    private val focus = AudioFocusCoordinator(context, focusEnabled, report, factoryRouting = true, template = template)
    init { focus.acquire(track, role.channel, track.audioAttributes) }

    fun reportActual() = binding.reportActual()
    override fun close() { focus.close(); routing.close() }
}
