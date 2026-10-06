package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.diagnostics.DiagnosticChannel
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent.Component
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent.Kind
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent.State
import com.shilapi.xcertplay.diagnostics.DiagnosticSink
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 只负责产品认证来源准备；协议认证和连接生命周期仍由上游负责。 */
internal class GalaxyAuthenticationBootstrap(
    private val sinkFactory: (Context) -> DiagnosticSink = GalaxyDiagnosticSink::create,
) {
    private var readyTarget: MfiTarget? = null

    @Synchronized fun ensure(context: Context) {
        val app = context.applicationContext
        GalaxyPreferencePolicy.migrateUsageAudioDefaults(app)
        val target = AirPlayPersistence.loadMfiTarget(app)
        // 认证准备采用负数任务号，与控制器正数会话编号区分；不保存 Context 或接收器。
        val sink = runCatching { sinkFactory(app) }.getOrDefault(DiagnosticSink.NONE)
        DiagnosticChannel(-attempts.incrementAndGet(), sink).use { diagnostics ->
            diagnostics.emit(Component.AUTHENTICATION, Kind.START, State.REQUESTED,
                mapOf("backend" to target.ordinal.toLong()))
            try {
                val cached = readyTarget == target && target != MfiTarget.REMOTE
                if (!cached) prepare(app, target)
                readyTarget = target
                diagnostics.emit(Component.AUTHENTICATION, Kind.STATE, State.READY,
                    mapOf("cached" to if (cached) 1L else 0L))
            } catch (error: Exception) {
                diagnostics.emit(Component.AUTHENTICATION, Kind.FAILURE, State.FAILED)
                throw error
            }
        }
    }

    private fun prepare(context: Context, target: MfiTarget) {
        when (target) {
            MfiTarget.LOCAL -> {
                L7Authentication.recover(context)
                val directory = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
                if (!directory.exists()) installBuiltIn(context, directory)
                LocalMfiAuthenticationClient.load(directory)
                AirPlayPersistence.saveDebugLogsEnabled(context, false)
            }
            MfiTarget.REMOTE -> L7Authentication.validateRemote(
                AirPlayPersistence.loadRemoteMfiServer(context), AirPlayPersistence.loadRemoteMfiToken(context))
            // 外部后端不读取、安装或覆盖本地身份，也不改变用户选择。
            else -> Unit
        }
    }

    private fun installBuiltIn(context: Context, directory: File) {
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
            check(staging.renameTo(directory)) { "Could not install local authentication" }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 身份切换调用方必须先停旧会话；失败后仍保留磁盘身份和原配置。 */
    @Synchronized fun reload(context: Context) {
        readyTarget = null
        ensure(context)
    }

    private companion object { val attempts = AtomicLong() }
}
