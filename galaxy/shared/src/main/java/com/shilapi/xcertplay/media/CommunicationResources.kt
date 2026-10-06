package com.shilapi.xcertplay.media

/** 结束请求不能释放模式；所有上下行实际退出后恢复，失败仅显式重试。 */
internal class CommunicationResources(
    private val enter: () -> Unit,
    private val restore: () -> Boolean,
    private val suspendMedia: () -> Unit,
    private val recoverMedia: () -> Unit,
    private val finish: () -> Unit,
) {
    private val active = mutableSetOf<Any>()
    private var pending = false
    private var closed = false
    @Synchronized fun started(owner: Any) {
        if (closed || !active.add(owner)) return
        if (active.size == 1) {
            pending = true
            suspendMedia()
            enter()
        }
    }
    @Synchronized fun released(owner: Any) {
        if (!active.remove(owner)) return
        retry()
    }
    @Synchronized fun retry() {
        if (active.isNotEmpty()) return
        if (closed) { finish(); return }
        if (pending && restore()) { pending = false; recoverMedia() }
    }
    @Synchronized fun close() { closed = true; retry() }
}
