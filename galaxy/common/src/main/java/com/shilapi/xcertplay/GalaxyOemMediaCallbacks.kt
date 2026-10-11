package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.reflect.InvocationHandler

/** 原厂渠道确认来源通知；通知不能再次播放，旧客户端不能确认或向手机发命令。 */
internal class GalaxyOemMediaCallbacks(
    private val channel: GalaxyMediaReportingChannel.Mode,
    private val source: Int,
    private val active: () -> Boolean,
    private val snapshot: () -> CarPlayNowPlaying,
    private val info: () -> Any?,
    private val command: (Int) -> Boolean,
    private val focus: (String?) -> Unit,
    private val selected: (Int) -> Boolean,
) : InvocationHandler {
    override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
        val current = active()
        return when (method.name) {
            "onCustomAction" -> { if (current) VehicleSteeringInputLog.customAction(args?.firstOrNull() as? android.os.Bundle); null }
            "onPlay" -> current && command(CarPlayMediaButton.PLAY)
            "onPause" -> current && command(CarPlayMediaButton.PAUSE)
            "onNext" -> current && command(CarPlayMediaButton.NEXT)
            "onPrevious" -> current && command(CarPlayMediaButton.PREVIOUS)
            "onMediaCenterFocusChanged" -> { if (current) focus(args?.firstOrNull() as? String); null }
            "onSourceSelected" -> current && selected(args?.firstOrNull() as? Int ?: -1)
            "onSourceChanged" -> {
                if (current) L7DebugLog.record("MediaCenter: sourceChanged source=${args?.firstOrNull() as? Int ?: -1} channel=${channel.name}")
                current && channel.acknowledgeSourceChange
            }
            "getCurrentSourceType" -> if (current) source else -1
            "getMediaSourceTypeList" -> if (current) intArrayOf(source) else intArrayOf()
            "getCurrentProgress" -> if (current) snapshot().elapsedMillis ?: 0L else 0L
            "getMusicPlaybackInfo" -> if (current) info() else null
            else -> null
        }
    }
}
