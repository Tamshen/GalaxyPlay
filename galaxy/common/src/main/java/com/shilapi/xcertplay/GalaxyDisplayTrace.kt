package com.shilapi.xcertplay

/** 几何变化才记录，避免逐帧日志；代次取实际拥有者，不记录屏幕内容。 */
internal class GalaxyDisplayTrace(private val report: (String) -> Unit) {
    private var previous: List<Int>? = null
    fun observe(generation: Int, width: Int, height: Int, canvasWidth: Int, canvasHeight: Int,
                left: Int, top: Int, viewportWidth: Int, viewportHeight: Int, hardware: Boolean) {
        val value = listOf(generation, width, height, canvasWidth, canvasHeight, left, top, viewportWidth, viewportHeight, if (hardware) 1 else 0)
        if (previous == value) return
        previous = value
        runCatching { report("ProjectionGeometry: generation=$generation monoMs=${android.os.SystemClock.elapsedRealtime()} viewWidth=$width viewHeight=$height canvasWidth=$canvasWidth canvasHeight=$canvasHeight left=$left top=$top viewportWidth=$viewportWidth viewportHeight=$viewportHeight hardware=$hardware") }
    }
}
