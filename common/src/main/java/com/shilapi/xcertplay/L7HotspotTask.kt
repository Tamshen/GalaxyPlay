package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.NativeHotspotConfiguration
import com.shilapi.xcertplay.network.NativeHotspotCredentials
import com.shilapi.xcertplay.network.NativeHotspotProblem
import com.shilapi.xcertplay.network.NativeHotspotRead
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 单个设置宿主的有界任务；不持有界面，离开页面后旧结果不得保存或推进连接。 */
internal class L7HotspotTask(context: Context, private val access: Access = SystemAccess(context)) : Closeable {
    interface Access {
        fun read(): NativeHotspotRead
        fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem?
        fun enabled(): Boolean?
        fun permitted(): Boolean
        fun start(cancelled: () -> Boolean): CarHotspotTethering.Result
    }
    private class SystemAccess(context: Context) : Access {
        private val app = context.applicationContext
        private val config = NativeHotspotConfiguration(app) { L7DebugLog.record(it) }
        override fun read() = config.read()
        override fun apply(credentials: NativeHotspotCredentials) = config.apply(credentials)
        override fun enabled() = CarHotspotStatus.isEnabled(app)
        override fun permitted() = CarHotspotTethering.permitted(app)
        override fun start(cancelled: () -> Boolean) = CarHotspotTethering.enable(app, cancelled) { L7DebugLog.record(it) }
    }
    data class Status(val message: Int = R.string.l7_hotspot_reading, val busy: Boolean = false,
        val hotspotEnabled: Boolean? = null, val configurationApplied: Boolean = false)
    private val app = context.applicationContext
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { Thread(it, "l7-native-hotspot") })
    private var work: Future<*>? = null
    private var draft: NativeHotspotCredentials? = null
    @Volatile private var generation = 0
    @Volatile var status = Status()
        private set

    @Synchronized fun proposal(deviceId: String): NativeHotspotCredentials =
        draft ?: NativeHotspotCredentials.generate(deviceId).also { draft = it }

    fun read() = begin(R.string.l7_hotspot_reading) { token ->
        val result = access.read()
        finish(token, result.credentials, if (result.credentials != null) R.string.l7_hotspot_read_ok else when (result.problem) {
            NativeHotspotProblem.PERMISSION -> R.string.l7_hotspot_read_permission
            NativeHotspotProblem.INVALID -> R.string.l7_hotspot_read_invalid
            else -> R.string.l7_hotspot_read_unavailable
        }, runCatching { access.enabled() }.getOrNull())
    }

    fun start(proposed: NativeHotspotCredentials? = null) = begin(R.string.l7_hotspot_starting) { token ->
        // 无线连接也先读取；即使缺少开启权限，已读到的名称与密码仍可用于手动路径。
        if (proposed == null) access.read().credentials?.let { save(token, it) }
        if (cancelled(token)) return@begin
        if (proposed != null && access.enabled() == true) {
            finish(token, null, R.string.l7_hotspot_already_on, true); return@begin
        }
        // 已运行的热点只能复用；系统权限页面返回后仍需用户再次点击，不隐式重配。
        if (access.enabled() != true && !access.permitted()) {
            finish(token, null, R.string.l7_hotspot_start_permission); return@begin
        }
        if (proposed != null) {
            if (cancelled(token)) return@begin
            val problem = access.apply(proposed)
            if (problem != null) {
                finish(token, null, when (problem) {
                    NativeHotspotProblem.PERMISSION -> R.string.l7_hotspot_write_permission
                    NativeHotspotProblem.ACTIVE -> R.string.l7_hotspot_already_on
                    NativeHotspotProblem.UNSUPPORTED -> R.string.l7_hotspot_unsupported
                    else -> R.string.l7_hotspot_write_failed
                }); return@begin
            }
            // 系统已接受才保存；开启失败仍可在原生设置中手动开启同一配置。
            save(token, proposed)
            synchronized(this) { if (!cancelled(token)) status = status.copy(configurationApplied = true) }
        }
        if (cancelled(token)) return@begin
        val result = access.start { cancelled(token) }
        finish(token, null, when (result) {
            CarHotspotTethering.Result.READY -> if (AirPlayPersistence.loadManualHotspotSsid(app).isNotBlank())
                R.string.l7_hotspot_ready else R.string.l7_hotspot_ready_no_details
            CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.l7_hotspot_start_permission
            CarHotspotTethering.Result.UNSUPPORTED -> R.string.l7_hotspot_unsupported
            CarHotspotTethering.Result.TIMED_OUT -> R.string.l7_hotspot_timeout
            CarHotspotTethering.Result.CANCELLED -> R.string.l7_hotspot_cancelled
            else -> R.string.l7_hotspot_failed
        }, if (result == CarHotspotTethering.Result.READY) true else runCatching { access.enabled() }.getOrNull())
    }

    @Synchronized private fun begin(message: Int, action: (Int) -> Unit): Boolean {
        if (status.busy || executor.isShutdown || !L7Agreement.accepted(app) || L7AppExit.exiting) return false
        if (CarPlayBackgroundSession.hasSession()) {
            status = Status(R.string.l7_hotspot_session_active); return false
        }
        val token = ++generation
        status = Status(message, true)
        executor.purge()
        try {
            work = executor.submit {
                try { if (!cancelled(token)) action(token) }
                catch (_: Exception) { finish(token, null, R.string.l7_hotspot_failed) }
            }
        } catch (_: RejectedExecutionException) {
            status = Status(R.string.l7_hotspot_busy)
            return false
        }
        return true
    }

    private fun cancelled(token: Int) = token != generation || Thread.currentThread().isInterrupted ||
        !L7Agreement.accepted(app) || L7AppExit.exiting || CarPlayBackgroundSession.hasSession()

    @Synchronized private fun save(token: Int, credentials: NativeHotspotCredentials) {
        if (!cancelled(token) && credentials.valid()) AirPlayPersistence.saveNativeHotspotCredentials(app, credentials)
    }

    @Synchronized private fun finish(token: Int, credentials: NativeHotspotCredentials?, message: Int, enabled: Boolean? = null) {
        if (cancelled(token)) return
        credentials?.let { save(token, it) }
        status = status.copy(message = message, busy = false, hotspotEnabled = enabled)
        // 仅记结果码；不记录名称、密码或接口异常正文。
        L7DebugLog.record("原生热点操作结果=${app.resources.getResourceEntryName(message)}")
    }

    @Synchronized fun cancel() {
        generation++
        work?.cancel(true)
        if (status.busy) status = status.copy(message = R.string.l7_hotspot_cancelled, busy = false, hotspotEnabled = null)
    }
    override fun close() { cancel(); executor.shutdownNow() }
}
