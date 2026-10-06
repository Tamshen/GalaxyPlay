package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

/** 仅记录当前安装身份和已获权限，不将原厂包名等同于平台签名或系统应用。 */
internal object L7SystemDiagnostic {
    private const val REFERENCE_SIGNER = "47c1a2bce0efbff6580c1e05a8b179c5dc9993911cbdd15be653b474c340e40f"
    fun report(context: Context): String {
        val certs = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty().map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                }
        }.getOrDefault(emptyList())
        val permissions = listOf("NETWORK_SETTINGS", "MONITOR_INPUT", "TETHER_PRIVILEGED", "MODIFY_AUDIO_ROUTING")
            .joinToString(",") { name ->
                "$name=${context.checkSelfPermission("android.permission.$name") == PackageManager.PERMISSION_GRANTED}"
            }
        return "L7 installation package=${context.packageName} " +
            "systemApp=${context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0} " +
            "signerSha256=${certs.joinToString(",").ifEmpty { "unavailable" }} " +
            "matchesE5Reference=${certs.size == 1 && certs.single() == REFERENCE_SIGNER} permissions=[$permissions]"
    }
}
