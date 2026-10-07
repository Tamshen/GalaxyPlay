package com.shilapi.xcertplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 同步与异步路径使用同一固定样例；输出、呈现回调与资源释放分别统计。 */
internal class GalaxyJavaCodecProbe(private val run: Long, private val method: CodecProbeMethod,
    private val sample: GalaxyCodecProbeSample, private val name: String, private val surface: Surface,
    private val allowSoftware: Boolean, private val progress: (CodecProbeStage) -> Unit) {
    @Volatile private var stage = CodecProbeStage.CREATE
    @Volatile private var error: Throwable? = null
    private var actual = ""
    private var hardware = false
    private var software = false
    private val inputs = AtomicInteger()
    private val outputs = AtomicInteger()
    private val renders = AtomicInteger()
    private val outputBytes = AtomicLong()
    private val recipe = GalaxyCodecProbeRecipe(method)
    private var configInputs = 0
    @Volatile private var eos = false
    private var inputEos = false
    private var origin = 0L
    private val done = CountDownLatch(1)

    fun decode(): CodecProbeResult {
        var codec: MediaCodec? = null
        val thread = HandlerThread("codec-probe-callback").apply { start() }
        var released = false
        var failedAt = stage
        try {
            move(CodecProbeStage.CREATE)
            val mime = sample.format.getString(MediaFormat.KEY_MIME)!!
            if (recipe.avcOnly && mime != CodecProbeVideo.AVC.mime) throw UnsupportedVideo()
            codec = if (recipe.byType) MediaCodec.createDecoderByType(mime)
                else MediaCodec.createByCodecName(name)
            actual = codec.name
            if (Build.VERSION.SDK_INT >= 29) {
                hardware = codec.codecInfo.isHardwareAccelerated
                software = codec.codecInfo.isSoftwareOnly
            }
            if (!allowSoftware && (!hardware || software)) throw SoftwareFallback()
            if (method == CodecProbeMethod.OEM_UCAR_QTI && (!hardware || software || !recipe.isQti(actual))) throw QtiDecoderRequired()
            val callbacks = Handler(thread.looper)
            if (recipe.async) installCallbacks(codec, callbacks)
            move(CodecProbeStage.CONFIGURE)
            val format = recipe.format(sample)
            if (method == CodecProbeMethod.JAVA_TUNED) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30 && codec.codecInfo.getCapabilitiesForType(mime)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            codec.configure(format, if (method.surfaceOutput) surface else null, null, 0)
            if (recipe.ucar) codec.setParameters(Bundle().apply { putFloat(MediaFormat.KEY_OPERATING_RATE, 45f) })
            if (method.surfaceOutput) codec.setOnFrameRenderedListener({ _, _, _ -> renders.incrementAndGet() }, callbacks)
            move(CodecProbeStage.START)
            origin = System.nanoTime()
            codec.start()
            move(CodecProbeStage.FEED)
            if (recipe.async) {
                if (!done.await(4, TimeUnit.SECONDS)) throw ProbeTimeout()
                error?.let { throw it }
            } else drain(codec)
            check(eos && outputs.get() > 0) { "NO_OUTPUT" }
            // 定时提交的最后一帧留出呈现时间，不把已入 Surface 队列当回调成功。
            val remaining = if (method.surfaceOutput) (origin + sample.times.last() * 1000 + 200_000_000 - System.nanoTime()) / 1_000_000 else 0
            if (remaining > 0) Thread.sleep(remaining.coerceAtMost(2200))
            failedAt = CodecProbeStage.DONE
        } catch (failure: Exception) {
            error = failure
            failedAt = stage
        } finally {
            move(CodecProbeStage.RELEASE)
            try { codec?.release(); released = true }
            catch (failure: Exception) { if (error == null) { error = failure; failedAt = CodecProbeStage.RELEASE } }
            thread.quitSafely()
            thread.join(300)
        }
        val failure = error
        return CodecProbeResult(run, method, failedAt, actual, hardware, software, inputs.get(), outputs.get(),
            if (method.surfaceOutput) renders.get() else -1, eos, released, (failure as? MediaCodec.CodecException)?.errorCode ?: 0,
            failure?.javaClass?.simpleName.orEmpty(), (System.nanoTime() - origin.coerceAtLeast(1)) / 1_000_000,
            configInputs = configInputs, outputBytes = outputBytes.get())
    }

    private fun move(next: CodecProbeStage) { stage = next; progress(next) }
    private fun feed(codec: MediaCodec, index: Int) {
        if (inputEos) return
        if (recipe.inBandCsd && configInputs == 0) {
            val buffer = checkNotNull(codec.getInputBuffer(index))
            buffer.clear()
            val size = sample.csd.sumOf { it.size }
            check(size <= buffer.remaining())
            sample.csd.forEach(buffer::put)
            codec.queueInputBuffer(index, 0, size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            configInputs++
            return
        }
        val n = inputs.get()
        if (n == sample.packets.size) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
            move(CodecProbeStage.DRAIN)
        } else {
            val packet = sample.packets[n]
            val buffer = checkNotNull(codec.getInputBuffer(index))
            buffer.clear(); check(buffer.remaining() >= packet.size)
            buffer.put(packet)
            // 立即呈现的原厂组合按样例到达时间送入，避免两秒样例瞬间播放完毕。
            if (recipe.immediate) {
                val waitMs = (origin + sample.times[n] * 1000 - System.nanoTime()) / 1_000_000
                if (waitMs > 0) Thread.sleep(waitMs.coerceAtMost(100))
            }
            codec.queueInputBuffer(index, 0, packet.size, recipe.presentationTime(n, sample), 0)
            inputs.incrementAndGet()
        }
    }

    private fun output(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val frame = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
            (info.size > 0 || method.surfaceOutput && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0)
        if (frame) {
            if (!method.surfaceOutput) {
                // 只检查输出缓冲区与字节数，不拷贝像素、不保存内容、不冒充可见画面。
                checkNotNull(codec.getOutputBuffer(index))
                outputBytes.addAndGet(info.size.toLong())
                codec.releaseOutputBuffer(index, false)
            } else if (recipe.ucar) codec.releaseOutputBuffer(index, System.nanoTime())
            else if (recipe.immediate) codec.releaseOutputBuffer(index, true)
            else codec.releaseOutputBuffer(index, origin + info.presentationTimeUs * 1000)
            outputs.incrementAndGet()
        } else codec.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; done.countDown() }
    }

    private fun drain(codec: MediaCodec) {
        val deadline = System.nanoTime() + 4_000_000_000L
        val info = MediaCodec.BufferInfo()
        while (!eos && System.nanoTime() < deadline) {
            if (!inputEos) codec.dequeueInputBuffer(recipe.waitUs).takeIf { it >= 0 }?.let { feed(codec, it) }
            val index = codec.dequeueOutputBuffer(info, recipe.waitUs)
            if (index >= 0) output(codec, index, info)
        }
        if (!eos) throw ProbeTimeout()
    }

    private fun installCallbacks(codec: MediaCodec, handler: Handler) {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                try { if (error == null) feed(codec, index) } catch (failure: Exception) { error = failure; done.countDown() }
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try { if (error == null) output(codec, index, info) } catch (failure: Exception) { error = failure; done.countDown() }
            }
            override fun onError(codec: MediaCodec, failure: MediaCodec.CodecException) { error = failure; done.countDown() }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
    }
    private class SoftwareFallback : Exception()
    private class ProbeTimeout : Exception()
    private class UnsupportedVideo : Exception()
    private class QtiDecoderRequired : Exception()
}
