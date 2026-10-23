package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * 部分车机固件不提供 VPN 授权对话框，有线 CarPlay 因此永远无法开始。Android 把被允许的
 * ACTIVATE_VPN 应用操作视为该授权，车机自身的 adb 可以直接放行。
 *
 * 这里只使用驾驶员已经批准过的 adb 密钥（[LocalAdb.connect] 传 mayAsk = false），因此不会在行驶中
 * 弹出"允许调试吗"对话框；密钥未获批准时静默失败，调用方按原路径继续提示用户。
 *
 * 复刻自 DiPlay 的同名机制（其 issue #604：车机无 VPN 授权对话框时有线连接无法启动）。
 */
internal object VpnPreConsent {
    /** 放行本应用 VPN 授权的 adb 命令。 */
    fun allowCommand(packageName: String): String = "appops set $packageName ACTIVATE_VPN allow"

    /** 车机 adb 上执行放行命令的最小契约，便于在没有真车的环境下验证。 */
    interface Adb {
        /** 只用已批准的密钥连接；返回 true 表示通道可用。 */
        fun connect(): Boolean

        /** 在车机 shell 中执行放行命令。 */
        fun shell(command: String): String?
    }

    /**
     * 通过车机 adb 放行本应用的 VPN 授权；返回 adb 的可达状态，不可用时不会抛出异常。
     */
    fun grant(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access =
        runCatching {
            adb.use {
                val access = it.connect(mayAsk = false)
                if (access == LocalAdb.Access.READY) {
                    it.shell(allowCommand(context.packageName))
                }
                access
            }
        }.getOrDefault(LocalAdb.Access.UNREACHABLE)

    /**
     * 通过车机 adb 放行；返回是否成功执行了放行命令。adb 不可达或命令失败都返回 false。
     */
    fun grant(context: Context, adb: Adb): Boolean = runCatching {
        if (!adb.connect()) return false
        adb.shell(allowCommand(context.packageName)) != null
    }.getOrDefault(false)
}

/**
 * 放行后是否真的拿到授权：adb 放行成功只是前提，系统仍可能继续要求授权页。
 * 只有 [prepare] 不再返回授权页才可继续连接。
 */
internal class VpnPreConsentGate(
    private val preConsent: () -> Boolean,
    private val prepare: () -> android.content.Intent?,
) {
    /** 放行已执行且系统不再要求授权页时才成立。 */
    fun satisfied(): Boolean = try {
        preConsent() && prepare() == null
    } catch (_: RuntimeException) {
        false
    }
}
