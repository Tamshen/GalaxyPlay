package com.shilapi.xcertplay.media

/** 收到连续视频却长时间没有新呈现时提示；静态画面或后台无目标不计失败。 */
internal class VideoPlaybackWatchdog(private val timeoutNs: Long = 8_000_000_000L,
    private val arrivalGapNs: Long = 1_000_000_000L) {
    private var sinceNs: Long? = null
    private var arrivalNs: Long? = null
    private var arrivals = 0
    private var reported = false
    @Synchronized fun received(nowNs: Long) {
        val previous = arrivalNs
        if (previous == null || nowNs - previous > arrivalGapNs) {
            sinceNs = nowNs; arrivals = 0; reported = false
        }
        arrivalNs = nowNs; arrivals++
    }
    @Synchronized fun ready() { sinceNs = null; arrivalNs = null; arrivals = 0; reported = false }
    @Synchronized fun presented(nowNs: Long) { sinceNs = nowNs; reported = false }
    @Synchronized fun failure(nowNs: Long): Boolean {
        val since = sinceNs ?: return false
        val last = arrivalNs ?: return false
        if (reported || arrivals < 3 || nowNs - last > arrivalGapNs || nowNs - since < timeoutNs) return false
        reported = true
        return true
    }
}
