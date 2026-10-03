// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.net.URI
import com.shilapi.xcertplay.orchestration.MfiTarget

/** L7 的认证来源选择与文字导入；协议认证仍由上游实现处理。 */
internal object L7Authentication {
    enum class Source { BUILT_IN, TEXT, USB, REMOTE }
    class MissingBuiltIn : Exception()

    private fun preferences(context: Context) = context.getSharedPreferences("l7_authentication", Context.MODE_PRIVATE)
    private fun active(context: Context) = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
    private fun imported(context: Context) = File(context.noBackupFilesDir, "l7-mfi-text")

    fun source(context: Context): Source = when (AirPlayPersistence.loadMfiTarget(context)) {
        MfiTarget.USB_CH341 -> Source.USB
        MfiTarget.REMOTE -> Source.REMOTE
        else -> if (preferences(context).getString("source", null) == Source.TEXT.name) Source.TEXT else Source.BUILT_IN
    }

    fun validateRemote(server: String, token: String) {
        require('\u0000' !in token)
        val uri = URI(server.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port in -1..65535)
    }

    fun selectExternal(context: Context, source: Source, server: String, token: String) {
        require(source == Source.USB || source == Source.REMOTE)
        if (source == Source.REMOTE) {
            validateRemote(server, token)
            AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.REMOTE, server.trim(), token)
        } else AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.USB_CH341)
        DiPlayBootstrap.reload(context)
    }

    fun hasImported(context: Context) = imported(context).isDirectory

    fun prepareBuiltIn(context: Context): LocalMfiIdentityStore.Identity {
        fun read(name: String): ByteArray = try {
            context.assets.open("offline-mfi/$name").use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 16 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) { throw MissingBuiltIn() }
        val key = read("identity.pk8")
        return try { LocalMfiIdentityStore.prepareBytes(key, read("certificate.p7b")) }
        finally { key.fill(0) }
    }

    fun prepareImported(context: Context) = LocalMfiIdentityStore.read(imported(context))

    fun install(context: Context, source: Source, identity: LocalMfiIdentityStore.Identity, saveImport: Boolean) {
        if (saveImport) LocalMfiIdentityStore.install(imported(context), identity)
        LocalMfiIdentityStore.install(active(context), identity)
        check(preferences(context).edit().putString("source", source.name).commit())
        AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.LOCAL)
        DiPlayBootstrap.reload(context)
    }

    fun recover(context: Context) {
        LocalMfiIdentityStore.recover(active(context))
        if (source(context) == Source.TEXT && !active(context).exists()) {
            prepareImported(context).use { LocalMfiIdentityStore.install(active(context), it) }
        }
    }
}
