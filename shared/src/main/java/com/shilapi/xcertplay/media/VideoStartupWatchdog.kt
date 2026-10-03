package com.shilapi.xcertplay.media

/** 只检测已收到可解码帧的启动阶段；没有视频、Surface 不在前台时不判为黑屏。 */
internal class VideoStartupWatchdog(private val timeoutNs: Long = 8_000_000_000L) {
    private var firstInputNs: Long? = null
    private var inputs = 0
    private var outputs = 0
    private var reported = false
    @Volatile private var rendered = false

    fun input(nowNs: Long) { if (firstInputNs == null) firstInputNs = nowNs; inputs++ }
    fun output() { outputs++ }
    fun rendered() { rendered = true }

    fun failure(nowNs: Long): String? {
        val since = firstInputNs ?: return null
        if (reported || rendered || inputs < 3 || nowNs - since < timeoutNs) return null
        reported = true
        return if (outputs == 0) "no decoded output after 8s inputs=$inputs"
            else "no Surface render callback after 8s inputs=$inputs outputs=$outputs"
    }

    fun reset() { firstInputNs = null; inputs = 0; outputs = 0; reported = false; rendered = false }
}
