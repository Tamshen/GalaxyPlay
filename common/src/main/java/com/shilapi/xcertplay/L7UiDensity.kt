package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** 仅覆盖应用界面的资源密度，系统密度、视频像素与物理屏幕参数保持独立。 */
internal object L7UiDensity {
    val presets = listOf(240, 280, 320)
    const val DEFAULT = 280
    const val MIN = 160
    const val MAX = 480
    private const val PREFS = "l7_ui"

    fun value(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("density", DEFAULT).takeIf { it in MIN..MAX } ?: DEFAULT

    fun save(context: Context, dpi: Int) {
        require(dpi in MIN..MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("density", dpi).apply()
    }

    fun wrap(context: Context): Context {
        if (!context.resources.getBoolean(R.bool.config_l7_product_ui)) return context
        val dpi = value(context)
        if (context.resources.configuration.densityDpi == dpi) return context
        return ContextThemeWrapper(context, R.style.Theme_Xcertplay).apply {
            applyOverrideConfiguration(configurationFor(context.resources.configuration, dpi))
        }
    }

    internal fun configurationFor(base: Configuration, dpi: Int) = Configuration().apply {
        // Android 的嵌套资源覆盖不会自动合并前一层的语言，需保留应用语言。
        setLocales(base.locales)
        densityDpi = dpi
        // 窗口像素不变，同时换算 dp 宽高，保证窄屏/大字分支与实际布局一致。
        val ratio = base.densityDpi.toFloat() / dpi
        screenWidthDp = (base.screenWidthDp * ratio).roundToInt()
        screenHeightDp = (base.screenHeightDp * ratio).roundToInt()
        smallestScreenWidthDp = (base.smallestScreenWidthDp * ratio).roundToInt()
    }
}
