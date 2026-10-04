package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** 加载当前本地认证，支持 L7 设置中的文字导入与内置身份切换。 */
internal object DiPlayBootstrap {
    @Volatile private var ready = false
    private var readyTarget: MfiTarget? = null

    @Synchronized fun ensure(context: Context) {
        val selectedTarget = AirPlayPersistence.loadMfiTarget(context)
        if (ready && readyTarget == selectedTarget && selectedTarget != MfiTarget.REMOTE) return
        // USB 与远程认证直接使用上游后端，不以本地身份文件作为启动前提。
        if (selectedTarget != MfiTarget.LOCAL) {
            if (selectedTarget == MfiTarget.REMOTE) {
                L7Authentication.validateRemote(AirPlayPersistence.loadRemoteMfiServer(context), AirPlayPersistence.loadRemoteMfiToken(context))
            }
            readyTarget = selectedTarget
            ready = true
            return
        }
        L7Authentication.recover(context)
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        readyTarget = selectedTarget
        ready = true
    }

    // 切换身份后使启动缓存失效；调用方应先等待旧 CarPlay 会话停止。
    @Synchronized fun reload(context: Context) {
        ready = false
        ensure(context)
    }

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
        L7StartupGuard.setEnabled(context, value)
    }
}
