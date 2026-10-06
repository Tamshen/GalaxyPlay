package com.shilapi.xcertplay.media

/** 超时帧已经取得的槽位仍归 worker；保留到下一帧，不靠反复重建来归还槽位。 */
internal class VideoInputSlot {
    private var held = -1
    fun acquire(dequeue: () -> Int): Int {
        if (held < 0) held = dequeue()
        return held
    }
    fun queued() { held = -1 }
    fun clear() { held = -1 }
}

/** 短时拿不到输入先重同步，持续无输出进展才认定 codec 卡住。 */
internal class VideoInputProgress(private val stallNs: Long = 2_000_000_000L) {
    private var waitingSince: Long? = null
    fun timedOut(nowNs: Long): Boolean {
        val since = waitingSince
        if (since == null) waitingSince = nowNs
        return since != null && nowNs - since >= stallNs
    }
    fun output() { waitingSince = null }
    fun reset() { waitingSince = null }
}
