package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.network.WirelessStartupFailure

/** 只依据真实阶段显示步骤；失败保留出错步骤，重试从准备阶段重新开始。 */
internal class GalaxyWirelessConnectionProgress {
    private var step = 1

    fun restarting(context: Context): String {
        step = 1
        return context.getString(R.string.galaxy_wireless_step, step,
            context.getString(R.string.galaxy_wireless_releasing))
    }

    fun describe(context: Context, status: CarPlayStatus, detail: String): String {
        val next = when (status) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission, CarPlayStatus.MfiReady,
            CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
            CarPlayStatus.AttachingNetwork, CarPlayStatus.WaitingForPairedIphone -> 1
            CarPlayStatus.ConnectingBluetooth -> 2
            CarPlayStatus.RunningWireless -> 3
            is CarPlayStatus.WaitingForWirelessNetwork, CarPlayStatus.NegotiatingWireless -> 4
            CarPlayStatus.PreparingWirelessVideo, CarPlayStatus.WirelessActive,
            CarPlayStatus.WirelessActiveFallback -> 5
            is CarPlayStatus.Failed -> step
            else -> return detail
        }
        step = next
        val text = when {
            status == CarPlayStatus.RunningWireless -> context.getString(R.string.galaxy_wireless_authenticating)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT ->
                context.getString(R.string.galaxy_wireless_network_timeout)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.AIRPLAY_PROTOCOL_TIMEOUT ->
                context.getString(R.string.galaxy_wireless_protocol_timeout)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION &&
                status.message.startsWith("Wi-Fi Direct") -> context.getString(R.string.galaxy_p2p_configuration_changed)
            else -> detail
        }
        return context.getString(R.string.galaxy_wireless_step, step, text)
    }
}
