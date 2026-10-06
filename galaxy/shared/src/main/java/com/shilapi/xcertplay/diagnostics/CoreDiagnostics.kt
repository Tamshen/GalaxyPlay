package com.shilapi.xcertplay.diagnostics

import java.io.Closeable
import java.util.Collections

/** 核心只发结构化元数据；实现负责非阻塞入队，禁止传入身份、载荷或异常原文。 */
fun interface DiagnosticSink {
    fun record(event: DiagnosticEvent)

    companion object {
        val NONE = DiagnosticSink { }
    }
}

data class DiagnosticEvent(
    val session: Long,
    val sequence: Long,
    val elapsedMillis: Long,
    val component: Component,
    val kind: Kind,
    val state: State,
    val metrics: Map<String, Long>,
) {
    enum class Component { CONNECTION, AUTHENTICATION, NETWORK, VIDEO, AUDIO, INPUT, MEDIA, NAVIGATION }
    enum class Kind { START, STATE, REQUEST, RECEIVE, SEND, RECOVERY, PRESENTATION, STOP, RELEASE, FAILURE }
    enum class State { REQUESTED, WAITING, READY, RUNNING, ENDED, FAILED, UNKNOWN }
}

/** 每个控制器独占一个通道，不使用全局可替换回调；关闭后拒绝迟到事件。 */
class DiagnosticChannel(
    private val session: Long,
    private val sink: DiagnosticSink = DiagnosticSink.NONE,
    private val clockNanos: () -> Long = System::nanoTime,
) : Closeable {
    private val started = clockNanos()
    private var sequence = 0L
    private var closed = false

    @Synchronized fun emit(
        component: DiagnosticEvent.Component,
        kind: DiagnosticEvent.Kind,
        state: DiagnosticEvent.State,
        metrics: Map<String, Long> = emptyMap(),
    ) {
        if (closed) return
        // 数值字段数量及名称有界；错误诊断不能阻断传输或资源释放。
        if (metrics.size > 12 || metrics.keys.any { !METRIC.matches(it) }) return
        publish(component, kind, state, metrics)
    }

    private fun publish(component: DiagnosticEvent.Component, kind: DiagnosticEvent.Kind,
        state: DiagnosticEvent.State, metrics: Map<String, Long>) {
        val event = DiagnosticEvent(session, ++sequence,
            ((clockNanos() - started) / 1_000_000).coerceAtLeast(0), component, kind, state,
            Collections.unmodifiableMap(LinkedHashMap(metrics)))
        try { sink.record(event) } catch (_: Exception) { }
    }

    /** 终止锚点与关闭原子完成，其他线程不能在 STOP 之后插入状态事件。 */
    @Synchronized fun finish() {
        if (closed) return
        closed = true
        publish(DiagnosticEvent.Component.CONNECTION, DiagnosticEvent.Kind.STOP, DiagnosticEvent.State.REQUESTED, emptyMap())
    }

    @Synchronized override fun close() { closed = true }

    private companion object {
        val METRIC = Regex("[a-zA-Z][a-zA-Z0-9]{0,31}")
    }
}
