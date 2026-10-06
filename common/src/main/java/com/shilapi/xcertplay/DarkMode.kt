package com.shilapi.xcertplay

import android.content.res.Configuration

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun nightModeOrNull(uiMode: Int): Boolean? = when (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
    android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
    android.content.res.Configuration.UI_MODE_NIGHT_NO -> false
    else -> null
}
