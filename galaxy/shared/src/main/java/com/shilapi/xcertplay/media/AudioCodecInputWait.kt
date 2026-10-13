package com.shilapi.xcertplay.media

/** 音乐突发到包时先排出解码输出再取输入槽；单包有限等待，保留原数据且不忙等。 */
internal class AudioCodecInputWait(
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: () -> Unit = { Thread.sleep(2) },
) {
    var retries = 0L
        private set
    var timeouts = 0L
        private set

    fun await(active: () -> Boolean, dequeue: () -> Int, drain: () -> Unit): Int {
        if (!active()) return -1
        var index = dequeue()
        if (index >= 0) return index
        retries++
        val deadline = nanoTime() + 100_000_000L
        while (active()) {
            drain()
            if (!active()) return -1
            index = dequeue()
            if (index >= 0) return index
            if (nanoTime() >= deadline) break
            pause()
        }
        if (active()) timeouts++
        return -1
    }
}
