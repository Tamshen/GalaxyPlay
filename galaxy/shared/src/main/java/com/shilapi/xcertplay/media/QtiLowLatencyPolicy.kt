package com.shilapi.xcertplay.media

/** 原厂参数仅尝试高通硬解；拒绝或播放恢复后，本连接不再尝试厂商参数。 */
class QtiLowLatencyPolicy(private val enabled: Boolean) {
    var rejected = false
        private set

    fun applies(candidate: VideoDecoderCandidate): Boolean =
        enabled && !rejected && candidate.hardware && !candidate.software &&
            (candidate.name.startsWith("OMX.qcom.video.decoder.", true) ||
                candidate.name.startsWith("c2.qti.", true) && candidate.name.endsWith(".decoder", true))

    fun configure(setInteger: (String, Int) -> Unit) {
        setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
    }

    fun reject() { rejected = true }
}
