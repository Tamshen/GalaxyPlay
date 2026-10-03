package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 有界进程日志：窗口关闭、切换页面和会话重连都不清除断联前的记录。 */
internal class DebugLogBuffer(private val capacity: Int = 1000) {
    init { require(capacity > 0) }
    data class Snapshot(val revision: Long, val lines: List<String>, val evicted: Long)
    private val lines = ArrayDeque<String>()
    private var revision = 0L
    private var evicted = 0L

    @Synchronized fun append(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        if (lines.size == capacity) { lines.removeFirst(); evicted++ }
        lines.addLast(safe)
        revision++
    }

    @Synchronized fun snapshot(onlyErrors: Boolean = false, limit: Int = capacity): Snapshot =
        Snapshot(revision, lines.filter { !onlyErrors || ERROR.containsMatchIn(it) }.takeLast(limit.coerceAtLeast(0)), evicted)

    @Synchronized fun clear() { lines.clear(); evicted = 0; revision++ }

    companion object {
        private val ERROR = Regex("(?i)error|fail|disconnect|ended|loss|reconnect|timeout|recover|reject|断开|断联|失败|异常|重连|超时")
    }
}

internal object L7DebugLog {
    val buffer = DebugLogBuffer()
    fun record(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        buffer.append("${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $safe")
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
