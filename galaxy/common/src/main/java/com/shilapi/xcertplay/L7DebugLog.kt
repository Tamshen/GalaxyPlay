package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 有界进程日志：窗口关闭、切换页面和会话重连都不清除断联前的记录。 */
internal class DebugLogBuffer(private val capacity: Int = 1000) {
    init { require(capacity > 0) }
    data class Snapshot(val revision: Long, val lines: List<String>, val evicted: Long)
    data class Status(val revision: Long, val evicted: Long)
    private val lines = ArrayDeque<String>()
    private var revision = 0L
    private var evicted = 0L

    @Synchronized fun append(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        appendRedacted(safe)
    }

    /** 仅接受调用方已脱敏的正文与非敏感时间／配置前缀，避免重复执行正则。 */
    @Synchronized internal fun appendRedacted(line: String) {
        if (lines.size == capacity) { lines.removeFirst(); evicted++ }
        lines.addLast(line)
        revision++
    }

    @Synchronized fun snapshot(onlyErrors: Boolean = false, limit: Int = capacity): Snapshot =
        Snapshot(revision, lines.filter { !onlyErrors || ERROR.containsMatchIn(it) }.takeLast(limit.coerceAtLeast(0)), evicted)

    @Synchronized fun clear() { lines.clear(); evicted = 0; revision++ }
    @Synchronized fun status() = Status(revision, evicted)

    companion object {
        private val ERROR = Regex("(?i)error|fail|disconnect|ended|loss|reconnect|timeout|recover|reject|断开|断联|失败|异常|重连|超时")
    }
}

internal object L7DebugLog {
    val buffer = DebugLogBuffer()
    private val timestamp = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val performance = GalaxyProcessPerformance()
    @Volatile internal var performanceMonitored = false
    @Volatile private var persistent: SessionLogFile? = null
    @Volatile private var currentConfiguration: GalaxyConfigurationEvidence? = null
    @Synchronized fun initialize(context: android.content.Context) {
        if (persistent == null) persistent = SessionLogFile(java.io.File(context.filesDir, "logs/debug.log"),
            listOf("debug-previous.log", "debug-previous-2.log"))
        if (currentConfiguration == null) refreshConfiguration(context)
    }
    fun refreshConfiguration(context: android.content.Context) {
        currentConfiguration = runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()
    }
    fun logger(context: android.content.Context, target: SessionLogFile? = persistent): (String) -> Unit {
        val snapshot = runCatching { GalaxyConfigurationEvidence.capture(context) }.getOrNull()
        return { line -> record(line, target, snapshot) }
    }

    /** 所有调试入口默认落盘；展示窗口和 JSON 报告不承担日志留存。 */
    fun record(message: String) = record(message, persistent)
    fun recordConfiguration(message: String, configuration: GalaxyConfigurationEvidence?) = record(message, persistent, configuration)

    fun recordSession(message: String, session: SessionLogFile?) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        val now = System.currentTimeMillis()
        val event = DiagnosticLogIdentity.next()
        val configuration = session?.configuration ?: currentConfiguration
        AsyncDiagnosticLog.appendRedacted(session, safe, now, configuration, event)
        recordRedacted(safe, persistent, configuration, event, now)
    }

    fun record(message: String, target: SessionLogFile?, configuration: GalaxyConfigurationEvidence? = target?.configuration ?: currentConfiguration,
               event: String? = null, nowMillis: Long = System.currentTimeMillis()) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        recordRedacted(safe, target, configuration, event, nowMillis)
    }

    private fun recordRedacted(safe: String, target: SessionLogFile?, configuration: GalaxyConfigurationEvidence?,
                               event: String?, nowMillis: Long) {
        if (!performanceMonitored) runCatching { performance.poll() }.getOrNull()?.let { writeRedacted(it, target, configuration) }
        writeRedacted(safe, target, configuration, event ?: DiagnosticLogIdentity.next(), nowMillis)
    }

    private fun writeRedacted(safe: String, target: SessionLogFile?, configuration: GalaxyConfigurationEvidence?,
                              event: String = DiagnosticLogIdentity.next(), now: Long = System.currentTimeMillis()) {
        buffer.appendRedacted((configuration?.let { "config_ref=${it.id} " } ?: "") +
            "${timestamp.get()!!.format(Date(now))}  ${DiagnosticLogIdentity.prefix(event, now)}$safe")
        AsyncDiagnosticLog.appendRedacted(target, safe, now, configuration, event)
    }
}

/** 拖动和窗口尺寸变化都约束位置，防止折叠后展开或旋转使日志窗移出屏幕。 */
internal object DebugOverlayPosition {
    data class Position(val x: Int, val y: Int)
    fun clamp(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) = Position(
        x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
        y.coerceIn(0, (screenHeight - height).coerceAtLeast(0)),
    )
}
