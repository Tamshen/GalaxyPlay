package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager

/** 采集当前固件与窗口的真实值；不采集序列号、蓝牙名称或任意设备地址。 */
internal object L7DeviceDiagnostic {
    @Suppress("DEPRECATION")
    fun capture(context: Context): List<String> {
        val metrics = context.applicationContext.resources.displayMetrics
        val config = context.applicationContext.resources.configuration
        val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay
        val real = Point()
        val realAvailable = runCatching { display?.getRealSize(real); display != null }.getOrDefault(false)
        val decor = (context as? Activity)?.window?.decorView
        val insets = decor?.rootWindowInsets
        val lines = mutableListOf(
            "设备：manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} brand=${Build.BRAND} " +
                "device=${Build.DEVICE} product=${Build.PRODUCT} board=${Build.BOARD} hardware=${Build.HARDWARE}",
            "固件：Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT} " +
                "securityPatch=${Build.VERSION.SECURITY_PATCH} build=${Build.DISPLAY} ABI=${Build.SUPPORTED_ABIS.joinToString(",")}",
            "显示：displayId=${display?.displayId} realPixels=${if (realAvailable) "${real.x}x${real.y}" else "unavailable"} " +
                "resourcePixels=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi} " +
                "density=${metrics.density} fontScale=${config.fontScale} " +
                "resourceDp=${config.screenWidthDp}x${config.screenHeightDp} orientation=${config.orientation}",
            "面板驱动报告：xdpi=${metrics.xdpi} ydpi=${metrics.ydpi}（不是物理尺寸实测）",
            "应用界面：uiDensityDpi=${L7UiDensity.value(context)}（仅界面缩放，独立于系统与投屏）",
            "当前窗口：decorPixels=${decor?.width}x${decor?.height} " +
                "systemInsets=${insets?.systemWindowInsetLeft},${insets?.systemWindowInsetTop}," +
                "${insets?.systemWindowInsetRight},${insets?.systemWindowInsetBottom}",
        )
        runCatching {
            context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_ALL)
                ?.take(32)?.forEach { device ->
                    // 只保留车机总线标识；个人蓝牙设备名称和 MAC 地址不进入报告。
                    val bus = device.address.takeIf { it.matches(Regex("BUS[0-9]{2}_[A-Za-z0-9_]+")) }
                    lines += "音频设备：id=${device.id} type=${device.type} source=${device.isSource} sink=${device.isSink} " +
                        "bus=${bus ?: "unreported"} rates=${device.sampleRates.joinToString(",")} " +
                        "channels=${device.channelCounts.joinToString(",")} encodings=${device.encodings.joinToString(",")}"
                }
        }.onFailure { lines += "音频设备查询失败：${it.javaClass.simpleName}" }
        return lines.mapNotNull(DiagnosticRedactor::redact)
    }
}
