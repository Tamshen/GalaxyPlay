package com.shilapi.xcertplay.media

import android.util.Log

internal enum class VideoDropReason { OVERFLOW, EXPIRED, NO_TARGET, NO_CONFIG, WAIT_KEYFRAME, RECOVERY_WAIT, INVALID, INPUT_CAPACITY, OUTPUT_EXPIRED, REBUILD_WAIT, INPUT_WAIT }

/** 每五秒汇总接收、输入、提交和呈现回调；帧龄使用固定桶，不保存逐帧对象。 */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var input = 0
    private var submitted = 0
    private var dropped = 0
    private var queueHighWaterFrames = 0
    private var queueHighWaterBytes = 0L
    private val inputAge = FrameAgeHistogram()
    private val outputAge = FrameAgeHistogram()
    private val presentedAge = FrameAgeHistogram()
    private val decodeAge = FrameAgeHistogram()
    private val dropReasons = IntArray(VideoDropReason.entries.size)
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onQueued(frames: Int, bytes: Long, discarded: Int) {
        queueHighWaterFrames = maxOf(queueHighWaterFrames, frames)
        queueHighWaterBytes = maxOf(queueHighWaterBytes, bytes)
        dropped += discarded
        dropReasons[VideoDropReason.OVERFLOW.ordinal] += discarded
    }

    @Synchronized fun onDropped(count: Int = 1, reason: VideoDropReason = VideoDropReason.INVALID) {
        dropped += count; dropReasons[reason.ordinal] += count
    }
    @Synchronized fun onInput(receivedNs: Long) { input++; inputAge.add(nanoTime() - receivedNs) }
    @Synchronized fun onSubmitted(presentationUs: Long, decodeNs: Long) {
        submitted++; outputAge.add(nanoTime() - presentationUs * 1000)
        if (decodeNs >= 0) decodeAge.add(decodeNs)
    }
    @Synchronized fun onPresented(presentationUs: Long, renderedNs: Long) {
        rendered++; presentedAge.add(renderedNs - presentationUs * 1000)
    }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && submitted == 0 && rendered == 0 && recoveries == 0 && touchSamples == 0) {
            windowStartNs = now; return null
        }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        // presented 是 MediaCodec 的 Surface 呈现回调，不代表物理屏幕扫描测量。
        val line = ("video stats$label rx=%.1ffps input=%.1ffps submitted=%.1ffps presented=%.1ffps " +
            "maxGap=%dms kbps=%d recoveries=%d dropped=%d queueMax=%dframes/%dbytes " +
            "ageP95Bucket input=%dms decode=%dms output=%dms presented=%dms " +
            "touch2rx avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, input / seconds, submitted / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            dropped, queueHighWaterFrames, queueHighWaterBytes,
            inputAge.p95Millis(), decodeAge.p95Millis(), outputAge.p95Millis(), presentedAge.p95Millis(),
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + " dropReasons=" + VideoDropReason.entries.filter { dropReasons[it.ordinal] > 0 }
            .joinToString(",") { "${it.name}:${dropReasons[it.ordinal]}" }
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        input = 0; submitted = 0; dropped = 0; queueHighWaterFrames = 0; queueHighWaterBytes = 0
        inputAge.clear(); decodeAge.clear(); outputAge.clear(); presentedAge.clear(); dropReasons.fill(0)
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}

/** 关联有限数量的输入 PTS 与输入时间；覆盖超限旧样本，不让诊断占用无限内存。 */
internal class VideoInputTiming(private val capacity: Int = 64) {
    private val pts = LongArray(capacity) { Long.MIN_VALUE }
    private val times = LongArray(capacity)
    private var next = 0
    var pending = 0
        private set
    fun record(presentationUs: Long, inputNs: Long) {
        if (pts[next] == Long.MIN_VALUE) pending++
        pts[next] = presentationUs; times[next] = inputNs; next = (next + 1) % capacity
    }
    fun take(presentationUs: Long, nowNs: Long): Long {
        val index = pts.indexOf(presentationUs)
        if (index < 0) return -1
        pts[index] = Long.MIN_VALUE
        pending--
        return (nowNs - times[index]).coerceAtLeast(0)
    }
    fun clear() { pts.fill(Long.MIN_VALUE); next = 0; pending = 0 }
}

/** P95 返回所在桶的上界，最后一桶返回实际最大值；无样本返回 -1。 */
internal class FrameAgeHistogram {
    private val boundsMs = longArrayOf(1, 5, 10, 16, 33, 50, 100, 150, 250, 500, 1000)
    private val counts = IntArray(boundsMs.size + 1)
    private var samples = 0
    private var maxMs = 0L
    fun add(ageNs: Long) {
        val ms = (ageNs.coerceAtLeast(0) + 999_999) / 1_000_000
        var bucket = 0
        while (bucket < boundsMs.size && ms > boundsMs[bucket]) bucket++
        counts[bucket]++; samples++; maxMs = maxOf(maxMs, ms)
    }
    fun p95Millis(): Long {
        if (samples == 0) return -1
        val target = (samples.toLong() * 95 + 99) / 100
        var accumulated = 0L
        for (index in counts.indices) {
            accumulated += counts[index]
            if (accumulated >= target) return if (index < boundsMs.size) boundsMs[index] else maxMs
        }
        return maxMs
    }
    fun clear() { counts.fill(0); samples = 0; maxMs = 0 }
}
