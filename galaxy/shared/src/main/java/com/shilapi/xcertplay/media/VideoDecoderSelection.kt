package com.shilapi.xcertplay.media

/** 能力查询结果与选择策略分开，便于验证硬件优先和显式软件偏好。 */
data class VideoDecoderCandidate(
    val name: String,
    val hardware: Boolean,
    val software: Boolean,
    val lowLatency: Boolean,
)

object VideoDecoderSelection {
    fun ordered(candidates: List<VideoDecoderCandidate>, preferSoftware: Boolean,
                preferredHardwareDecoder: String? = null): List<VideoDecoderCandidate> {
        val defaults = candidates.distinctBy { it.name }.sortedBy {
            when {
                preferSoftware && it.software -> 0
                it.hardware -> 1
                !it.software -> 2
                else -> 3
            }
        }
        // 指定名称只调整可用硬件的尝试顺序，能力不符或创建失败仍保留默认回退。
        val preferred = defaults.firstOrNull {
            !preferSoftware && it.name == preferredHardwareDecoder && it.hardware && !it.software
        } ?: return defaults
        return listOf(preferred) + defaults.filter { it.name != preferred.name }
    }
}
