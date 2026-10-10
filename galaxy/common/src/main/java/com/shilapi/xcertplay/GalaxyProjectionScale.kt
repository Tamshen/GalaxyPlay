package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** 车型分辨率与独立图标比例分别计算；不改变其他宿主的上游默认行为。 */
internal object GalaxyProjectionScale {
    fun enabled(context: Context) = context.resources.getBoolean(R.bool.config_l7_product_ui)
    fun sanitize(percent: Int) = percent.takeIf { it in 50..200 } ?: CarPlayUiScale.DEFAULT
    fun label(percent: Int) = if (percent in CarPlayUiScale.presets) CarPlayUiScale.label(percent) else "$percent%"
    fun resolution(context: Context, display: AirPlayDisplayConfig, legacyTenths: Int): AirPlayDisplayConfig =
        if (enabled(context)) CarPlayDisplayScale.applyPercent(display,
            AirPlayPersistence.loadDisplayScalePercent(context).coerceIn(30, 100))
        else CarPlayDisplayScale.apply(display, legacyTenths)

    fun icons(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig {
        val scale = sanitize(percent)
        if (scale in CarPlayUiScale.presets) return CarPlayUiScale.apply(display, scale)
        require(display.widthPixels > 0 && display.heightPixels > 0)
        fun pixels(value: Int) = ((value.toLong() * 100 / scale + 1) / 2 * 2).toInt()
        val width = pixels(display.widthPixels)
        val height = pixels(display.heightPixels)
        // 小图标扩大画布时仍服从既有 4K 上限；解码器支持由宿主继续检查。
        if (scale < 100 && (maxOf(width, height) > 3840 || minOf(width, height) > 2160)) return display
        fun insets(value: AirPlayInsets?) = value?.let {
            it.copy(top = (it.top * height.toDouble() / display.heightPixels).roundToInt(),
                bottom = (it.bottom * height.toDouble() / display.heightPixels).roundToInt(),
                left = (it.left * width.toDouble() / display.widthPixels).roundToInt(),
                right = (it.right * width.toDouble() / display.widthPixels).roundToInt())
        }
        return display.copy(widthPixels = width, heightPixels = height,
            viewArea = insets(display.viewArea), safeArea = insets(display.safeArea))
    }
}
