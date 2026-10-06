package com.shilapi.xcertplay.media

/** 显示和输入共用的等比视频矩形，坐标相对于当前 View，不使用面板固定像素。 */
data class VideoViewport(val left: Double, val top: Double, val width: Double, val height: Double) {
    fun contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height
    fun normalizedX(x: Double): Double = ((x - left) / width).coerceIn(0.0, 1.0)
    fun normalizedY(y: Double): Double = ((y - top) / height).coerceIn(0.0, 1.0)

    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): VideoViewport {
            val vw = viewWidth.coerceAtLeast(1).toDouble()
            val vh = viewHeight.coerceAtLeast(1).toDouble()
            val scale = minOf(vw / videoWidth.coerceAtLeast(1), vh / videoHeight.coerceAtLeast(1))
            val width = videoWidth.coerceAtLeast(1) * scale
            val height = videoHeight.coerceAtLeast(1) * scale
            return VideoViewport((vw - width) / 2, (vh - height) / 2, width, height)
        }
    }
}
