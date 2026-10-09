package com.shilapi.xcertplay.media

/** 编解码器配置或启动失败时，释放已创建的实例后再传播原始异常。 */
internal object MediaCodecStartup {
    /** 每个候选只尝试一次；失败实例由 create 释放，平台链接错误仍直接传播。 */
    fun <T> firstAvailable(candidates: List<String>, create: (String) -> T,
                         failed: (String, Exception) -> Unit): T {
        var lastFailure: Exception? = null
        for (name in candidates.distinct()) {
            try { return create(name) } catch (failure: Exception) {
                lastFailure = failure
                runCatching { failed(name, failure) }
            }
        }
        throw lastFailure ?: IllegalArgumentException("NO_AUDIO_CODEC")
    }

    fun <T> create(
        create: () -> T,
        configure: (T) -> Unit,
        start: (T) -> Unit,
        release: (T) -> Unit,
    ): T {
        val candidate = create()
        try {
            configure(candidate)
            start(candidate)
            return candidate
        } catch (failure: Throwable) {
            // 驱动清理也可能失败，保留原始配置或启动异常。
            runCatching { release(candidate) }
            throw failure
        }
    }
}
