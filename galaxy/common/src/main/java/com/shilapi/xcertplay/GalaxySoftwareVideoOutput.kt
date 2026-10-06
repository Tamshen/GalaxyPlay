package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.VideoViewport
import java.io.Closeable
import kotlin.math.roundToInt

/** 软件窗口保留整窗视口；holder 拥有 Surface，宿主只解除 worker 绑定。 */
internal class GalaxySoftwareVideoOutput(context: Context,
    private val created: (Surface) -> Unit,
    private val destroyed: (Surface) -> Unit,
    private val resized: (Int, Int) -> Unit,
) : Closeable {
    val viewport = FrameLayout(context).apply { clipChildren = true }
    val video = SurfaceView(context)
    private var closed = false
    private var surface: Surface? = null
    private var bounds: List<Int>? = null
    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (closed || holder !== video.holder) return
            val next = holder.surface
            surface = next
            created(next)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (closed || holder !== video.holder) return
            surface?.let(destroyed)
            surface = null
        }
    }
    init {
        video.holder.addCallback(callback)
        viewport.addView(video, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (!closed && (r - l != or - ol || b - t != ob - ot)) resized(r - l, b - t)
        }
    }
    fun layout(content: VideoViewport) {
        val l = content.left.roundToInt(); val t = content.top.roundToInt()
        val w = ((content.left + content.width).roundToInt() - l).coerceAtLeast(1)
        val h = ((content.top + content.height).roundToInt() - t).coerceAtLeast(1)
        val next = listOf(l, t, w, h)
        if (closed || bounds == next) return
        bounds = next
        video.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = l; topMargin = t
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        video.holder.removeCallback(callback)
        surface?.let(destroyed)
        surface = null
    }
}
