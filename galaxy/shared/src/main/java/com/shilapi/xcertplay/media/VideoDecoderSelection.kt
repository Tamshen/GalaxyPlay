package com.shilapi.xcertplay.media

/** 能力查询结果与选择策略分开，便于验证硬件优先和显式软件偏好。 */
data class VideoDecoderCandidate(
    val name: String,
    val hardware: Boolean,
    val software: Boolean,
    val lowLatency: Boolean,
)

object VideoDecoderSelection {
    fun ordered(candidates: List<VideoDecoderCandidate>, preferSoftware: Boolean): List<VideoDecoderCandidate> =
        candidates.distinctBy { it.name }.sortedBy {
            when {
                preferSoftware && it.software -> 0
                it.hardware -> 1
                !it.software -> 2
                else -> 3
            }
        }
}
