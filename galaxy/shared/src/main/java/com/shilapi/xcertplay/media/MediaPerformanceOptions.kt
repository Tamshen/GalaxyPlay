package com.shilapi.xcertplay.media

/** 创建连接时固定一份；三个开关独立，空配置完整保留软件默认路径。 */
data class MediaPerformanceOptions(
    val separateMusicWriter: Boolean = false,
    val reuseVideoBuffer: Boolean = false,
    val qtiLowLatency: Boolean = false,
)
