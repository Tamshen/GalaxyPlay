package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    // 完成信号只在 worker 解除旧目标后调用，宿主据此释放 Surface。
    data class SurfaceChanged(val surface: Surface?, val onApplied: () -> Unit = {}) : VideoJob
    data object Resync : VideoJob
    data object Retry : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** 普通入队和出队只更新计数；超限时清除压缩参考链，保持控制任务的相对顺序。 */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val onDepth: (Int, Long, Int) -> Unit = { _, _, _ -> },
) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var frameCount = 0
    private var byteCount = 0L
    val queuedFrames: Int get() = lock.withLock { frameCount }
    val queuedBytes: Long get() = lock.withLock { byteCount }

    /** 返回丢弃数量，供低频诊断汇总；正常路径不创建统计列表。 */
    fun offer(job: VideoJob): Int = lock.withLock {
        var dropped = 0
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || byteCount + job.nalus.size > maxBytes) {
                dropped = discardFrames()
                jobs.addLast(VideoJob.Resync)
            }
            if (job.nalus.size > maxBytes) {
                available.signal()
                onDepth(frameCount, byteCount, dropped + 1)
                return@withLock dropped + 1
            }
            frameCount++
            byteCount += job.nalus.size
        }
        jobs.addLast(job)
        available.signal()
        onDepth(frameCount, byteCount, dropped)
        dropped
    }

    fun discardFrames(): Int = lock.withLock {
        val dropped = frameCount
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
        frameCount = 0
        byteCount = 0
        dropped
    }

    fun poll(timeoutMillis: Long): VideoJob? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (jobs.isEmpty()) {
            if (remaining <= 0) return@withLock null
            remaining = available.awaitNanos(remaining)
        }
        jobs.removeFirst().also { job ->
            if (job is VideoJob.Frame) { frameCount--; byteCount -= job.nalus.size }
        }
    }

    /** worker 退出时仍须完成目标解除回调，避免宿主资源永久等待。 */
    fun drain(): List<VideoJob> = lock.withLock {
        jobs.toList().also { jobs.clear(); frameCount = 0; byteCount = 0 }
    }
}

/** 接收、排队和输入等待共用同一个帧龄上限，不能逐阶段叠加超时。 */
internal object VideoFrameBudget {
    const val MAX_AGE_NS = 250_000_000L
    fun remainingNs(receivedNs: Long, nowNs: Long): Long =
        (MAX_AGE_NS - (nowNs - receivedNs).coerceAtLeast(0)).coerceAtLeast(0)
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = VideoFrameBudget.MAX_AGE_NS,
    ): Int {
        val start = nanoTime()
        while (running()) {
            if (nanoTime() - start >= timeoutNs) break
            drain()
            val index = dequeue()
            // 已取到的槽位由调用方决定提交或重建，不能遗失输入槽位。
            if (index >= 0) return index
        }
        return -1
    }
}
