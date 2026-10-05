package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.util.concurrent.Executor

/** 只发送样本确认的导航启停和路名，不猜测转向编号、距离、ETA 或 HUD 目标。 */
internal class L7ManualNavigationReport(
    context: Context,
    private val log: (String) -> Unit,
    released: () -> Unit,
    private val current: () -> Boolean,
    port: L7NavigationPort = L7ReflectiveNavigation(context),
    worker: Executor? = null,
) : L7ReportingSession {
    private val roads = listOf(context.getString(R.string.l7_report_road_a), context.getString(R.string.l7_report_road_b))
    private var road = 0
    private var closed = false
    private val observed = object : L7NavigationPort by port {
        override fun close() {
            try { port.close(); log("stage=callbackCleanup result=RETURNED remoteClear=NOT_VERIFIED") }
            catch (error: Throwable) {
                if (error !is Exception && error !is LinkageError) throw error
                log("stage=callbackCleanup exceptionType=${error.javaClass.simpleName}")
            } finally { released() }
        }
    }
    private val session = if (worker == null) L7NavigationSession(observed, current, log = log)
        else L7NavigationSession(observed, current, worker, log)

    override fun start() = publish()
    override fun action(value: L7ReportingAction) {
        if (closed || !current()) return
        when (value) {
            L7ReportingAction.ROAD -> { road = 1 - road; publish() }
            L7ReportingAction.REFRESH -> {
                log("stage=explicitRefresh display=NOT_VERIFIED")
                session.refresh()
            }
            else -> Unit
        }
    }
    private fun publish() {
        if (closed || !current()) return
        log("stage=fixture road=${if (road == 0) "A" else "B"} active=true display=NOT_VERIFIED")
        session.update(CarPlayNavigationSnapshot(active = true, road = roads[road]))
    }
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }
}
