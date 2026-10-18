package com.shilapi.xcertplay

internal data class GalaxyUiFrames(
    val epoch: Long, val frames: Long, val missingReports: Long,
    val foreground: Boolean, val available: Boolean,
)

internal enum class GalaxyUiFpsState { WAITING, ACTIVE, STATIC, BACKGROUND, UNAVAILABLE, INCOMPLETE }

/** 帧回调只递增数值，快照低频分配；窗口代次拒绝停止／切页后的迟到帧。 */
internal class GalaxyUiFrameCounter {
    private var epoch = 0L
    private var enabled = false
    private var frames = 0L
    private var missingReports = 0L
    private var foreground = false
    private var available = true
    @Synchronized fun open(foreground: Boolean, available: Boolean = true): Long {
        this.foreground = foreground; this.available = available
        frames = 0; missingReports = 0; enabled = true
        return ++epoch
    }
    @Synchronized fun frame(expected: Long, missing: Int) {
        if (!enabled || epoch != expected || !foreground || !available) return
        frames++; missingReports += missing.coerceAtLeast(0)
    }
    @Synchronized fun unavailable(expected: Long) { if (epoch == expected) available = false }
    @Synchronized fun read(): GalaxyUiFrames? = if (!enabled) null else
        GalaxyUiFrames(epoch, frames, missingReports, foreground, available)
    @Synchronized fun close() { epoch++; enabled = false }
}
