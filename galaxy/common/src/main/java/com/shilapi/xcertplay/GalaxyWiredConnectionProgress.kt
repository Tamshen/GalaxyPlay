package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** 根据控制器实际阶段提示手机侧操作；不能从 USB 返回值推断锁屏或旧配对状态。 */
internal object GalaxyWiredConnectionProgress {
    fun describe(context: Context, status: CarPlayStatus, detail: String): String {
        val resource = when (status) {
            CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone -> R.string.galaxy_usb_stage_find
            CarPlayStatus.RequestingIphonePermission -> R.string.galaxy_usb_stage_permission
            CarPlayStatus.SelectingConfiguration -> R.string.galaxy_usb_stage_switch
            CarPlayStatus.WaitingForReenumeration -> R.string.galaxy_usb_stage_reenumeration
            CarPlayStatus.OpeningDataPaths, CarPlayStatus.Pairing -> R.string.galaxy_usb_stage_pairing
            CarPlayStatus.ConnectingControl, CarPlayStatus.AttachingNetwork,
            CarPlayStatus.RunningControl -> R.string.galaxy_usb_stage_session
            else -> return detail
        }
        return context.getString(resource)
    }
}
