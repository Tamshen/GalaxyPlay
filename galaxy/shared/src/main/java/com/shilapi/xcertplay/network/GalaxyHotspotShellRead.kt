package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.util.Base64
import com.shilapi.xcertplay.hud.BydAdbShell
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity

/** 应用接口失败后才尝试已经授权的本地 ADB；不申请新授权、不回显凭据。 */
internal object GalaxyHotspotShellRead {
    fun read(context: Context, log: (String) -> Unit): NativeHotspotRead {
        if (Build.VERSION.SDK_INT < 30 || Thread.currentThread().isInterrupted)
            return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
        val shell = BydAdbShell("GalaxyHotspotRead")
        return try {
            val result = parse(shell.run(context, command(context.packageCodePath), 15_000))
            runCatching { log("hotspot read method=shellGetSoftApConfiguration readable=${result.credentials != null} " +
                "result=${result.problem ?: "READY"} access=${shell.lastAccess}") }
            result
        } catch (error: Exception) {
            runCatching { log("hotspot read method=shellGetSoftApConfiguration readable=false exceptionType=${error.javaClass.simpleName}") }
            NativeHotspotRead(problem = NativeHotspotProblem.FAILED)
        } finally { shell.close() }
    }

    internal fun command(apk: String): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${GalaxyHotspotReadMain::class.java.name}"
    }

    internal fun parse(output: String?): NativeHotspotRead {
        val prefix = "${GalaxyHotspotReadMain.HEADER}|"
        val line = output?.takeIf { it.length <= 4096 }?.lineSequence()?.map(String::trim)
            ?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        if (line == "PERMISSION") return NativeHotspotRead(problem = NativeHotspotProblem.PERMISSION)
        val parts = line?.split('|')
        if (parts == null || parts.size != 4 || parts[0] != "OK")
            return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
        return runCatching {
            val security = when (parts[3]) {
                "0" -> ManualHotspotSecurity.OPEN
                "1" -> ManualHotspotSecurity.WPA2
                "2" -> ManualHotspotSecurity.WPA3_TRANSITION
                "3" -> ManualHotspotSecurity.WPA3
                else -> return NativeHotspotRead(problem = NativeHotspotProblem.UNSUPPORTED)
            }
            val credentials = NativeHotspotCredentials(decoded(parts[1]), decoded(parts[2]), security)
            if (credentials.valid() && (credentials.password.isEmpty() || !credentials.password.all { it in "*.•●" }))
                NativeHotspotRead(credentials) else NativeHotspotRead(problem = NativeHotspotProblem.INVALID)
        }.getOrElse { NativeHotspotRead(problem = NativeHotspotProblem.INVALID) }
    }

    private fun decoded(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(Base64.encodeToString(bytes, Base64.NO_WRAP) == value)
        return bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
}
