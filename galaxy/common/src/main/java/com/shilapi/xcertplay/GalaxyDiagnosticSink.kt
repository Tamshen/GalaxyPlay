package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import com.shilapi.xcertplay.diagnostics.DiagnosticSink

/** 复用现有脱敏、内存缓冲和后台落盘；日志默认保留，展示开关不决定是否采集。 */
internal class GalaxyDiagnosticSink(
    private val recordLine: (String) -> Unit = L7DebugLog::record,
    private val accepts: () -> Boolean = { true },
) : DiagnosticSink {
    override fun record(event: DiagnosticEvent) {
        if (!accepts()) return
        val metrics = event.metrics.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
        recordLine("CORE_TRACE session=${event.session} seq=${event.sequence} elapsedMs=${event.elapsedMillis} " +
            "component=${event.component} kind=${event.kind} state=${event.state}" +
            if (metrics.isEmpty()) "" else " $metrics")
    }

    companion object {
        fun create(context: Context): DiagnosticSink {
            L7DebugLog.initialize(context.applicationContext)
            return GalaxyDiagnosticSink()
        }
    }
}
