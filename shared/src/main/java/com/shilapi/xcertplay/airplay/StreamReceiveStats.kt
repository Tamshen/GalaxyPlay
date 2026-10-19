package com.shilapi.xcertplay.airplay

/** 只统计接收线程耗时，不记录载荷、端点地址或路由数据。 */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nowNs: () -> Long = System::nanoTime,
) {
    enum class Stage(val field: String) { HEADER("header"), BODY("body"), DECRYPT("decrypt"), DISPATCH("dispatch") }
    private val stageMaxNs = LongArray(Stage.entries.size)
    private val stageCalls = IntArray(Stage.entries.size)

    fun <T> measure(stage: Stage, block: () -> T): T {
        val start = nowNs()
        try { return block() } finally {
            val index = stage.ordinal
            stageMaxNs[index] = maxOf(stageMaxNs[index], (nowNs() - start).coerceAtLeast(0))
            stageCalls[index]++
        }
    }

    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var pendingReadNs = 0L
    private var readTimeouts = 0
    private var readErrors = 0
    private var lastReadError = "none"
    private var lastProcessNs = 0L
    private var lastLoopEndNs: Long? = null
    private var maxLoopDelayNs = 0L
    private var loopDelayNs = 0L
    private var gapReports = 0
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0

    fun reading() {
        readStart = nowNs()
        loopDelayNs = lastLoopEndNs?.let { (readStart - it).coerceAtLeast(0) } ?: 0
        maxLoopDelayNs = maxOf(maxLoopDelayNs, loopDelayNs)
    }

    fun readTimedOut() {
        // 百毫秒轮询超时不能把一次长断流拆成多个短等待，累计到下次真正收包。
        val now = nowNs()
        pendingReadNs += (now - readStart).coerceAtLeast(0)
        lastLoopEndNs = now
        readTimeouts++
    }

    fun readFailed(failureClass: String) {
        val now = nowNs()
        pendingReadNs += (now - readStart).coerceAtLeast(0)
        lastLoopEndNs = now
        readErrors++
        lastReadError = failureClass.take(64).takeIf { it.matches(Regex("[A-Za-z0-9_$]+")) } ?: "unknown"
    }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = pendingReadNs + (processingStart - readStart).coerceAtLeast(0)
        pendingReadNs = 0L
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        val previousReceivedNs = lastReceivedNs
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) recordSequence(sequence, timestamp, readNs, previousReceivedNs)
        if (timestamp != null) lastTimestamp = timestamp
    }

    private fun recordSequence(sequence: Int, timestamp: Int?, readNs: Long, previousReceivedNs: Long) {
        val expected = nextSequence
        val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
        if (delta >= 0x8000) { lateOrDuplicate++; return }
        forwardGapPackets += delta
        if (delta > 0) recordGap(delta, expected, sequence, timestamp, readNs, previousReceivedNs)
        nextSequence = (sequence + 1) and 0xffff
    }

    private fun recordGap(delta: Int, expected: Int?, sequence: Int, timestamp: Int?, readNs: Long, previousReceivedNs: Long) {
        sequenceGapEvents++
        if (readNs >= LONG_READ_NS) {
            seqGapAfterReadOver250Ms++
            seqMissingAfterReadOver250Ms += delta
        }
        maxSequenceGap = maxOf(maxSequenceGap, delta)
        lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
            "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
            "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
        lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
        if (gapReports++ < 4) runCatching {
            report("ReceiveGap: $label monoMs=${monoMs()} missing=$delta expected=$expected received=$sequence " +
                "rtpTimestamp=${timestamp?.toUnsignedLong() ?: "unknown"} " +
                "arrivalGapMs=${if (previousReceivedNs == 0L) "unknown" else (processingStart - previousReceivedNs) / 1_000_000} " +
                "readWaitMs=${readNs / 1_000_000} priorProcessUs=${lastProcessNs / 1000} " +
                "loopDelayUs=${loopDelayNs / 1000}")
        }
    }

    fun processed() {
        val now = nowNs()
        lastProcessNs = (now - processingStart).coerceAtLeast(0)
        maxProcessNs = maxOf(maxProcessNs, lastProcessNs)
        lastLoopEndNs = now
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
            "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms" +
            Stage.entries.joinToString("") { " ${it.field}MaxUs=${stageMaxNs[it.ordinal] / 1000} ${it.field}Calls=${stageCalls[it.ordinal]}" } +
            " monoMs=${monoMs()} readTimeouts=$readTimeouts readErrors=$readErrors lastReadError=$lastReadError " +
            "pendingReadMs=${pendingReadNs / 1_000_000} " +
            "sinceRxMs=${if (lastReceivedNs == 0L) "unknown" else (now - lastReceivedNs).coerceAtLeast(0) / 1_000_000} " +
            "loopDelayMaxUs=${maxLoopDelayNs / 1000} gapReportsSuppressed=${(gapReports - 4).coerceAtLeast(0)}") }
        stageMaxNs.fill(0)
        stageCalls.fill(0)
        windowStart = now
        packets = 0
        bytes = 0
        maxReadNs = 0
        readTimeouts = 0
        readErrors = 0
        lastReadError = "none"
        maxLoopDelayNs = 0
        gapReports = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private companion object { const val LONG_READ_NS = 250_000_000L }

}
