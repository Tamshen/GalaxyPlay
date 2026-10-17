package com.shilapi.xcertplay

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 固定合成视频经过真实 MediaCodec／Surface 呈现；不是 iPhone 或实车画面。 */
internal object GalaxyPerformanceVideoCheck {
    private fun avcConfiguration(csd: Array<ByteArray>): ByteArray {
        // MediaExtractor 提供 Annex-B 参数集，CarPlay 配置入口要求 AVCDecoderConfigurationRecord。
        fun raw(bytes: ByteArray) = bytes.drop(if (bytes[2] == 1.toByte()) 3 else 4).toByteArray()
        val sps = raw(csd[0]); val pps = raw(csd[1])
        fun length(bytes: ByteArray) = byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte())
        return byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()) +
            length(sps) + sps + byteArrayOf(1) + length(pps) + pps
    }

    fun run(context: Context, test: Instrumentation, activity: GalaxyPerformanceHarnessActivity): JSONObject {
        val ready = CountDownLatch(1)
        val view = SurfaceView(activity)
        test.runOnMainSync {
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { ready.countDown() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            activity.content.addView(view, FrameLayout.LayoutParams(640, 360, Gravity.CENTER))
        }
        check(ready.await(5, TimeUnit.SECONDS))
        val source = GalaxyCodecProbeSample.read(context, CodecProbeVideo.AVC)
        val sink = AndroidMediaSink(view.holder.surface, 640, 360, videoFps = 30, context = context)
        val values = CopyOnWriteArrayList<GalaxyPerformanceSample>()
        val reader = GalaxyPerformanceReader(); val owner = Any()
        val monitor = GalaxyPerformanceMonitor({ reader.read(false).copy(owner = owner, video = sink.videoPerformance()) },
            { sample, _ -> values.add(sample) }, { owner }, {})
        try {
            sink.onScreenStreamActive(110, true)
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, avcConfiguration(source.csd))
            monitor.start()
            repeat(3) {
                for (packet in source.packets) {
                    val start = SystemClock.elapsedRealtime()
                    sink.onVideoFrame(110, packet)
                    val wait = 33 - (SystemClock.elapsedRealtime() - start)
                    if (wait > 0) Thread.sleep(wait)
                }
            }
            val snapshot = sink.videoPerformance().single { it.stream == 110 }
            check(snapshot.received == 180L && snapshot.presented > 0)
            check(values.any { (it.main?.presentedFps ?: 0.0) > 0.0 }) { "NO_ACTUAL_PRESENTATION_FPS" }
            return JSONObject().put("received", snapshot.received).put("presented", snapshot.presented)
                .put("codec", snapshot.codec).put("width", snapshot.outputWidth).put("height", snapshot.outputHeight)
                .put("presentedFps", JSONArray(values.mapNotNull { it.main?.presentedFps }))
        } finally {
            try { monitor.close(); check(monitor.awaitClosed(2000)) } finally {
                try {
                    val detached = CountDownLatch(1)
                    sink.detachSurface(view.holder.surface) { detached.countDown() }
                    check(detached.await(3, TimeUnit.SECONDS))
                } finally {
                    sink.close()
                    test.runOnMainSync { activity.content.removeView(view) }
                }
            }
        }
    }
}
