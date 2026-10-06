package com.shilapi.xcertplay

import android.graphics.Color

/** The screen before CarPlay's video follows CarPlay's day/night mode, so it does not glare at night. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = if (night) {
            WaitingScreenColors(Color.rgb(12, 17, 27), Color.rgb(241, 245, 252), Color.rgb(168, 182, 202))
        } else {
            WaitingScreenColors(Color.rgb(233, 238, 246), Color.rgb(28, 28, 30), Color.rgb(90, 100, 116))
        }
    }
}
