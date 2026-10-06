package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
) {
    CARD(
        isOverlay = false,
        textPrimary = Color.rgb(241, 245, 252),
        textSecondary = Color.rgb(168, 182, 202),
        accent = Color.rgb(166, 200, 255),
        accentTrack = Color.rgb(0x32, 0x58, 0x8c),
        trackOff = Color.rgb(42, 56, 75),
        buttonText = Color.rgb(12, 17, 27),
    ),
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.WHITE,
        textSecondary = Color.rgb(170, 180, 190),
        accent = Color.rgb(127, 205, 154),
        accentTrack = Color.rgb(78, 143, 102),
        trackOff = Color.rgb(64, 74, 80),
        buttonText = Color.rgb(8, 17, 11),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
