package com.shilapi.xcertplay.media

/** 结束请求不能释放模式；所有上下行实际退出后恢复，失败仅显式重试。 */
internal class CommunicationResources(
    private val enter: () -> Unit,
    private val restore: () -> Boolean,
    private val suspendMedia: () -> Unit,
    private val recoverMedia: () -> Unit,
    private val finish: () -> Unit,
    private val report: (String) -> Unit = {},
) {
    private val operations = com.shilapi.xcertplay.diagnostics.DiagnosticOperation(report = { value ->
        report("CommunicationResources: $value active=${active.size} pending=$pending closed=$closed")
    })
    private fun state(stage: String) { runCatching { report("CommunicationResources: stage=$stage active=${active.size} pending=$pending closed=$closed") } }
    private val active = mutableSetOf<Any>()
    private var pending = false
    private var closed = false
    @Synchronized fun started(owner: Any) {
        if (closed || !active.add(owner)) return
        state("ACQUIRED")
        if (active.size == 1) {
            pending = true
            operations.run("suspendMedia", suspendMedia)
            operations.run("enterMode", enter)
        }
    }
    @Synchronized fun released(owner: Any) {
        if (!active.remove(owner)) return
        state("RELEASED")
        retry()
    }
    @Synchronized fun retry() {
        if (active.isNotEmpty()) return
        if (closed) { operations.run("finishMode", finish); return }
        if (pending && operations.run("restoreMode", restore)) {
            pending = false
            operations.run("recoverMedia", recoverMedia)
        }
    }
    @Synchronized fun close() { closed = true; retry() }
}
