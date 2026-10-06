package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.network.NativeHotspotCredentials
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.lang.reflect.InvocationTargetException

/** 三条只读路径独立执行；配置仅在内存校验，报告不持有名称、密码或异常正文。 */
internal class L7HotspotProbe(
    private val api: Int,
    private val query: (String) -> Any?,
) {
    constructor(context: Context) : this(Build.VERSION.SDK_INT, { method ->
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw ServiceUnavailable()
        wifi.javaClass.getMethod(method).invoke(wifi)
    })

    fun inspect(id: String): L7ProbeItem {
        val method = methods.getValue(id)
        val facts = mutableMapOf<String, String?>("method" to method, "effectiveCall" to "QUERY_ONLY")
        fun item(result: L7ProbeOutcome, reason: String) = L7ProbeItem(id, method, "HOTSPOT", result, reason, facts)
        if (method == "getSoftApConfiguration" && api < 30) {
            facts["effectiveCall"] = "NOT_RUN"
            return item(L7ProbeOutcome.NOT_APPLICABLE, "API_NOT_APPLICABLE")
        }
        return try {
            val value = query(method)
            if (method == "getWifiApState") {
                val state = value as? Int
                facts["apState"] = state?.toString()
                facts["hotspotEnabled"] = state?.takeIf { it in 10..14 }?.let { (it == 13).toString() }
                if (state != null && state in 10..14) item(L7ProbeOutcome.VERIFIED, "HOTSPOT_STATE_READ")
                else item(L7ProbeOutcome.UNKNOWN, "INVALID_RESPONSE")
            } else {
                if (value == null) return item(L7ProbeOutcome.NOT_OBSERVED, "EMPTY_CONFIG")
                val credentials = credentials(value)
                    ?: return item(L7ProbeOutcome.UNKNOWN, "INVALID_RESPONSE")
                facts["ssidPresent"] = credentials.ssid.isNotEmpty().toString()
                facts["passwordPresent"] = credentials.password.isNotEmpty().toString()
                facts["security"] = credentials.security.name
                facts["configValid"] = credentials.valid().toString()
                val masked = credentials.password.isNotEmpty() && credentials.password.all { it == '*' }
                facts["passwordMasked"] = masked.toString()
                when {
                    masked -> item(L7ProbeOutcome.NOT_OBSERVED, "MASKED_PASSWORD")
                    credentials.valid() -> item(L7ProbeOutcome.VERIFIED, "VALID_CONFIG")
                    else -> item(L7ProbeOutcome.NOT_OBSERVED, "INVALID_CONFIG")
                }
            }
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            facts["exceptionType"] = cause.javaClass.simpleName
            when (cause) {
                is SecurityException -> item(L7ProbeOutcome.DENIED, "HOTSPOT_DENIED")
                is ServiceUnavailable, is android.os.DeadObjectException -> item(L7ProbeOutcome.UNKNOWN, "SERVICE_UNAVAILABLE")
                is NoSuchMethodException, is IllegalAccessException, is UnsupportedOperationException ->
                    item(L7ProbeOutcome.UNKNOWN, "INTERFACE_NOT_VISIBLE")
                else -> item(L7ProbeOutcome.UNKNOWN, "QUERY_FAILED")
            }
        }
    }

    private fun credentials(value: Any): NativeHotspotCredentials? {
        val security: ManualHotspotSecurity
        val ssid: String
        val password: String
        when {
            api >= 30 && value is SoftApConfiguration -> {
                security = when (value.securityType) {
                    SoftApConfiguration.SECURITY_TYPE_OPEN -> ManualHotspotSecurity.OPEN
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> ManualHotspotSecurity.WPA2
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> ManualHotspotSecurity.WPA3_TRANSITION
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> ManualHotspotSecurity.WPA3
                    else -> return null
                }
                ssid = value.ssid.orEmpty()
                password = value.passphrase.orEmpty()
            }
            value is WifiConfiguration -> {
                val keys = value.allowedKeyManagement
                security = when {
                    keys[WifiConfiguration.KeyMgmt.SAE] && keys[WifiConfiguration.KeyMgmt.WPA2_PSK] -> ManualHotspotSecurity.WPA3_TRANSITION
                    keys[WifiConfiguration.KeyMgmt.SAE] -> ManualHotspotSecurity.WPA3
                    keys[WifiConfiguration.KeyMgmt.WPA2_PSK] || keys[WifiConfiguration.KeyMgmt.WPA_PSK] -> ManualHotspotSecurity.WPA2
                    keys[WifiConfiguration.KeyMgmt.NONE] -> ManualHotspotSecurity.OPEN
                    else -> return null
                }
                ssid = value.SSID.orEmpty().removeSurrounding("\"")
                password = value.preSharedKey.orEmpty().removeSurrounding("\"")
            }
            else -> return null
        }
        return NativeHotspotCredentials(ssid, password, security)
    }

    internal class ServiceUnavailable : IllegalStateException()
    companion object {
        val methods = linkedMapOf("HOTSPOT-STATE" to "getWifiApState", "HOTSPOT-CONFIG" to "getSoftApConfiguration",
            "HOTSPOT-LEGACY" to "getWifiApConfiguration")
    }
}
