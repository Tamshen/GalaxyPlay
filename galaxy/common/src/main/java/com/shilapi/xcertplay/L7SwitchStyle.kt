package com.shilapi.xcertplay

import android.widget.Switch
import com.shilapi.xcertplay.host.R

/** 按标题 20/32 的比例适配 Flyme 原生 92×48 dp 轨道；整行触控与 Android 开关语义保留。 */
internal object L7SwitchStyle {
    fun apply(control: Switch) {
        control.showText = false
        control.splitTrack = false
        control.switchMinWidth = L7Components.dp(control.context, 58)
        control.minimumHeight = L7Components.dp(control.context, 64)
        L7Ui.bind(control) {
            control.background = null
            control.thumbTintList = null
            control.trackTintList = null
            control.thumbDrawable = control.context.getDrawable(R.drawable.galaxy_switch_thumb)
            control.trackDrawable = control.context.getDrawable(R.drawable.galaxy_switch_track)
        }
    }
}
