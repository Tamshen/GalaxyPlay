package com.shilapi.xcertplay

import android.content.Context

/** 浮动入口的位置按可移动范围保存，窗口变化后仍保持可点击。 */
internal object L7FloatingNavigationPreferences {
    private const val PREFS = "l7_floating_navigation"
    val transparencyPresets = listOf(0, 25, 50, 75)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun transparency(context: Context): Int = prefs(context).getInt("transparency", 50)
        .takeIf { it in transparencyPresets } ?: 50
    fun saveTransparency(context: Context, percent: Int) {
        require(percent in transparencyPresets)
        prefs(context).edit().putInt("transparency", percent).apply()
    }
    fun position(context: Context): Pair<Float, Float> =
        fraction(prefs(context).getFloat("x", 0f)) to fraction(prefs(context).getFloat("y", .45f))
    fun savePosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat("x", fraction(x)).putFloat("y", fraction(y)).apply()
    }
    private fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
