package com.shilapi.xcertplay.media

/** 编解码器配置或启动失败时，释放已创建的实例后再传播原始异常。 */
internal object MediaCodecStartup {
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
