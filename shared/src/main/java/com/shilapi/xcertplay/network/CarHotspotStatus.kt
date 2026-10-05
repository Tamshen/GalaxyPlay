package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    private const val WIFI_AP_STATE_ENABLED = 13

    /** 仅返回系统 AP 状态原值，不用网络接口存在与否推断已开启。 */
    fun state(context: Context): Int? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int
    }.getOrNull()

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return runCatching {
            val state = state(context) ?: throw NoSuchMethodException()
            if (state !in 10..14) return null
            state == WIFI_AP_STATE_ENABLED
        }.recoverCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.getOrNull()
    }
}
