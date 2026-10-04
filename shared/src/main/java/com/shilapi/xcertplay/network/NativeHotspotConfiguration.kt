package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.security.SecureRandom

/** 密码不参与 toString；仅用于本地系统配置与 CarPlay 建链。 */
class NativeHotspotCredentials(val ssid: String, val password: String, val security: ManualHotspotSecurity) {
    fun valid(): Boolean = ManualHotspotValidation.error(ssid, password) == null &&
        ssid != "<unknown ssid>" && (security == ManualHotspotSecurity.OPEN ||
        (password.isNotEmpty() && !password.all { it == '*' })) &&
        (security != ManualHotspotSecurity.OPEN || password.isEmpty())

    fun matches(other: NativeHotspotCredentials) =
        ssid == other.ssid && password == other.password && security == other.security

    companion object {
        /** MD5 只缩短匿名设备编号；密码单独使用安全随机数，与设备编号无关。 */
        fun generate(deviceId: String, random: SecureRandom = SecureRandom()): NativeHotspotCredentials {
            val suffix = MessageDigest.getInstance("MD5").digest(deviceId.toByteArray(Charsets.UTF_8))
                .takeLast(2).joinToString("") { "%02X".format(it.toInt() and 255) }
            val groups = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnpqrstuvwxyz", "23456789")
            val alphabet = groups.joinToString("")
            val chars = groups.map { it[random.nextInt(it.length)] }.toMutableList()
            repeat(13) { chars += alphabet[random.nextInt(alphabet.length)] }
            for (i in chars.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val old = chars[i]; chars[i] = chars[j]; chars[j] = old
            }
            return NativeHotspotCredentials("CarPlay_$suffix", chars.joinToString(""), ManualHotspotSecurity.WPA2)
        }
    }
}

enum class NativeHotspotProblem { PERMISSION, UNSUPPORTED, INVALID, FAILED, ACTIVE }
class NativeHotspotRead(val credentials: NativeHotspotCredentials? = null, val problem: NativeHotspotProblem? = null)

/** 隐藏接口按能力调用；固件拒绝时交回原生设置，不绕过系统授权。 */
class NativeHotspotConfiguration(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)

    fun read(): NativeHotspotRead {
        wifi ?: return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
        var modernError: NativeHotspotProblem? = null
        if (Build.VERSION.SDK_INT >= 30) try {
            val config = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi) as SoftApConfiguration
            val security = when (config.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> ManualHotspotSecurity.OPEN
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> ManualHotspotSecurity.WPA2
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> ManualHotspotSecurity.WPA3_TRANSITION
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> ManualHotspotSecurity.WPA3
                else -> return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
            }
            return checked(NativeHotspotCredentials(config.ssid.orEmpty(), config.passphrase.orEmpty(), security))
        } catch (error: Exception) { modernError = problem(error) }
        return try {
            val config = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
                ?: return NativeHotspotRead(problem = modernError ?: NativeHotspotProblem.INVALID)
            val key = config.allowedKeyManagement
            val security = when {
                key.get(WifiConfiguration.KeyMgmt.SAE) && key.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> ManualHotspotSecurity.WPA3_TRANSITION
                key.get(WifiConfiguration.KeyMgmt.SAE) -> ManualHotspotSecurity.WPA3
                key.get(WifiConfiguration.KeyMgmt.WPA2_PSK) || key.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> ManualHotspotSecurity.WPA2
                key.get(WifiConfiguration.KeyMgmt.NONE) -> ManualHotspotSecurity.OPEN
                else -> return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
            }
            checked(NativeHotspotCredentials(config.SSID.orEmpty().removeSurrounding("\""),
                config.preSharedKey.orEmpty().removeSurrounding("\""), security))
        } catch (error: Exception) {
            NativeHotspotRead(problem = if (modernError == NativeHotspotProblem.PERMISSION) modernError else problem(error))
        }
    }

    /** 仅在热点关闭时替换配置；不为了改名而关闭已连接的设备。 */
    fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem? {
        if (!credentials.valid() || credentials.security != ManualHotspotSecurity.WPA2) return NativeHotspotProblem.INVALID
        when (CarHotspotStatus.isEnabled(app)) {
            true -> return NativeHotspotProblem.ACTIVE
            null -> return NativeHotspotProblem.UNSUPPORTED
            false -> Unit
        }
        wifi ?: return NativeHotspotProblem.UNSUPPORTED
        return try {
            val accepted = if (Build.VERSION.SDK_INT >= 30) {
                // Android 11 的 setSsid 是系统 API，新 SDK 已移出公开桩；按目标固件反射调用。
                val builder = SoftApConfiguration.Builder()
                builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, credentials.ssid)
                builder.setPassphrase(credentials.password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                val config = builder.build()
                wifi.javaClass.getMethod("setSoftApConfiguration", SoftApConfiguration::class.java).invoke(wifi, config) == true
            } else {
                val config = WifiConfiguration().apply {
                    SSID = credentials.ssid; preSharedKey = credentials.password
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                }
                wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, config) == true
            }
            if (!accepted) NativeHotspotProblem.FAILED else {
                val actual = read().credentials
                if (actual != null && !credentials.matches(actual)) NativeHotspotProblem.FAILED else null
            }
        } catch (error: Exception) { problem(error) }
    }

    private fun checked(value: NativeHotspotCredentials) = if (value.valid()) NativeHotspotRead(value)
        else NativeHotspotRead(problem = NativeHotspotProblem.INVALID)

    private fun problem(error: Exception): NativeHotspotProblem = when (
        if (error is InvocationTargetException) error.targetException else error) {
        is SecurityException -> NativeHotspotProblem.PERMISSION
        is ReflectiveOperationException, is UnsupportedOperationException -> NativeHotspotProblem.UNSUPPORTED
        else -> NativeHotspotProblem.FAILED
    }
}
