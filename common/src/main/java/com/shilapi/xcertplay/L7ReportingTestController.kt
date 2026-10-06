package com.shilapi.xcertplay

import java.io.Closeable

internal enum class L7ReportingKind { MEDIA, NAVIGATION }
internal enum class L7ReportingAction { TRACK, PLAY_PAUSE, PROGRESS, COVER, ROAD, REFRESH }
internal interface L7ReportingSession : Closeable {
    fun start()
    fun action(value: L7ReportingAction)
}

/** 展示是有界快照，证据始终写入现有日志；结束等待实际清理，不把调用返回当作实车通过。 */
internal class L7ReportingTestController(
    private val factory: (L7ReportingKind, (String) -> Unit, () -> Unit, () -> Boolean) -> L7ReportingSession,
    private val blocked: () -> Boolean,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    enum class Phase { IDLE, RUNNING, STOPPING, STOPPED }
    data class Snapshot(val run: Long = 0, val kind: L7ReportingKind? = null,
        val phase: Phase = Phase.IDLE, val deadline: Long = 0, val lines: List<String> = emptyList(),
        val revision: Long = 0, val firstIssue: String? = null, val step: Long = 0,
        val preview: L7ReportingPreview? = null, val observation: Boolean? = null, val cleanupObservation: Boolean? = null)
    private var state = Snapshot()
    private var session: L7ReportingSession? = null

    @Synchronized fun snapshot() = state
    @Synchronized private fun current(run: Long) = state.run == run && state.phase == Phase.RUNNING && clock() < state.deadline
    private fun event(run: Long, kind: L7ReportingKind, value: String) {
        val line = "ReportTest: run=$run kind=$kind monoMs=${clock()} $value"
        log(line)
        synchronized(this) {
            if (state.run == run) {
                val preview = L7ReportingPreview.parse(kind, value)?.takeIf { state.phase == Phase.RUNNING }
                val changed = preview != null && preview != state.preview
                val failed = value.contains("exceptionType=") || value.contains("tokenValid=false") ||
                    value.contains("accepted=false") || value.contains(" result=false")
                state = state.copy(lines = (state.lines + line.take(700)).takeLast(12),
                    revision = state.revision + 1, firstIssue = state.firstIssue ?: line.take(700).takeIf { failed },
                    preview = preview ?: state.preview, step = state.step + if (changed) 1 else 0,
                    observation = if (changed) null else state.observation)
            }
        }
    }

    fun start(kind: L7ReportingKind): Boolean {
        // 不持状态锁进入真实会话锁；会话创建时也会反向请求停止测试。
        if (blocked()) return false
        val run = synchronized(this) {
            if (state.phase in setOf(Phase.RUNNING, Phase.STOPPING)) return false
            state = Snapshot(state.run + 1, kind, Phase.RUNNING, clock() + 120_000,
                revision = state.revision + 1)
            state.run
        }
        event(run, kind, "stage=begin fixture=SYNTHETIC display=NOT_VERIFIED")
        var created: L7ReportingSession? = null
        try {
            created = factory(kind, { event(run, kind, it) }, { released(run, kind) },
                { current(run) && !blocked() })
            synchronized(this) { session = created }
            if (current(run) && !blocked()) created.start() else {
                stop("START_CANCELLED")
                created.close()
            }
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            event(run, kind, "stage=begin exceptionType=${error.javaClass.simpleName}")
            stop("START_FAILED")
            if (created == null) released(run, kind)
        }
        return true
    }

    fun action(kind: L7ReportingKind, value: L7ReportingAction) {
        val target = synchronized(this) {
            if (state.kind != kind || state.phase != Phase.RUNNING) return
            state = state.copy(step = state.step + 1, observation = null, revision = state.revision + 1)
            session
        }
        if (blocked()) { stop("CARPLAY_OR_AGREEMENT"); return }
        val next = snapshot()
        event(next.run, kind, "stage=action value=$value")
        try { target?.action(value) }
        catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            event(next.run, kind, "stage=action exceptionType=${error.javaClass.simpleName}")
        }
    }

    fun observe(kind: L7ReportingKind, visible: Boolean, expectedRun: Long? = null, expectedStep: Long? = null,
                expectedPhase: Phase? = null): Boolean {
        val value = synchronized(this) {
            if (state.run == 0L || state.kind != kind || state.phase !in setOf(Phase.RUNNING, Phase.STOPPED) ||
                expectedRun != null && expectedRun != state.run || expectedStep != null && expectedStep != state.step ||
                expectedPhase != null && expectedPhase != state.phase) return false
            val value = state
            state = if (state.phase == Phase.STOPPED) state.copy(cleanupObservation = visible, revision = state.revision + 1)
                else state.copy(observation = visible, revision = state.revision + 1)
            value
        }
        val ended = value.phase == Phase.STOPPED
        val stage = if (ended) "userCleanupObservation" else "userObservation"
        val result = if (ended) {
            if (visible) "CLEARED" else "RESIDUAL_OR_ABNORMAL"
        } else if (visible) "VISIBLE" else "MISSING_OR_ABNORMAL"
        event(value.run, kind, "stage=$stage result=$result origin=MANUAL phase=${value.phase} step=${value.step}")
        return true
    }

    fun expire() {
        val value = snapshot()
        if (value.phase == Phase.RUNNING && (clock() >= value.deadline || blocked()))
            stop(if (clock() >= value.deadline) "TIME_LIMIT" else "CARPLAY_OR_AGREEMENT")
    }

    fun stop(reason: String) {
        val (value, target) = synchronized(this) {
            if (state.phase != Phase.RUNNING) return
            state = state.copy(phase = Phase.STOPPING, revision = state.revision + 1)
            state to session
        }
        val kind = value.kind ?: return
        event(value.run, kind, "stage=end reason=$reason remoteClear=NOT_VERIFIED")
        // 构造中的取消由 start 接管清理，仍占用互斥直到其完成。
        if (target == null) return
        try { target.close() }
        catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            // 清理没有完成证据时保持互斥，避免新测试覆盖远端状态。
            event(value.run, kind, "stage=cleanup exceptionType=${error.javaClass.simpleName} completion=UNCONFIRMED")
        }
    }

    private fun released(run: Long, kind: L7ReportingKind) {
        synchronized(this) {
            if (state.run != run || state.phase != Phase.STOPPING) return
            session = null
            state = state.copy(phase = Phase.STOPPED, revision = state.revision + 1)
        }
        event(run, kind, "stage=localRelease result=COMPLETED remoteClear=NOT_VERIFIED")
    }
}
