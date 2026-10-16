package com.shilapi.xcertplay.network

import android.os.IBinder
import android.os.Process
import android.util.Base64
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** 参考 DiPlay #769 的 shell 读取方式；只读，不包含热点写入或开启命令。 */
object GalaxyHotspotReadMain {
    internal const val HEADER = "GALAXYPLAY_HOTSPOT_READ_V1"

    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(10_000); exitProcess(2) }, "hotspot-read-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            require(Process.myUid() in setOf(0, 2000) && args.isEmpty())
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "wifi") as? IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val config = wifi?.javaClass?.getMethod("getSoftApConfiguration")
                ?.apply { isAccessible = true }?.invoke(wifi)
            if (config == null) "UNAVAILABLE" else {
                val type = config.javaClass
                val ssid = type.getMethod("getSsid").invoke(config) as? String ?: ""
                val password = type.getMethod("getPassphrase").invoke(config) as? String ?: ""
                val security = type.getMethod("getSecurityType").invoke(config) as Int
                "OK|${encoded(ssid)}|${encoded(password)}|$security"
            }
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            if (cause is SecurityException) "PERMISSION" else "UNAVAILABLE"
        }
        // 返回值仅经过私有 ADB 通道，调用者不得将正文写进诊断日志。
        println("$HEADER|$result")
        exitProcess(0)
    }

    private fun encoded(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
