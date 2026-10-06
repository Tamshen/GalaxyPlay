package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.security.MessageDigest

/** 保留上游启动与身份入口，产品准备交给独立 Galaxy 策略。 */
internal object DiPlayBootstrap {
    fun ensure(context: Context, mfiTarget: com.shilapi.xcertplay.orchestration.MfiTarget) = ensure(context)
    fun ensure(context: Context) = GalaxyStartupPolicy.ensure(context)
    fun reload(context: Context) = GalaxyStartupPolicy.reload(context)

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        GalaxyStartupPolicy.saveAutoConnect(context, value)
    }
}
