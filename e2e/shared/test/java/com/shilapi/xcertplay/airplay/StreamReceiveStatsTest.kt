package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun linksSequenceGapsToTheSameLongReadAndResetsOnlyWindowCounters() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int, readMs: Long) {
            stats.reading()
            clock += readMs * 1_000_000L
            stats.received(100, sequence)
            stats.processed()
        }
        packet(1, 10)
        packet(4, 300) // Two missing packets follow this long socket wait.
        packet(7, 10) // Two more missing packets follow a short socket wait.
        packet(8, 400) // A long read alone does not imply packet loss.
        stats.flush(ended = true)
        assertEquals(1, output.count { it.startsWith("Receive:") })
        assertTrue(output.last().contains("windowMs=720 readsOver250Ms=2 seqGapAfterReadOver250Ms=1 seqMissingAfterReadOver250Ms=2"))
        packet(9, 10)
        stats.flush(ended = true)
        assertTrue(output.last().contains("windowMs=10 readsOver250Ms=0 seqGapAfterReadOver250Ms=0 seqMissingAfterReadOver250Ms=0"))
        assertTrue(output.last().contains("seqForwardGaps=0"))
    }

    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.count { it.startsWith("Receive:") })
        assertTrue(output.last().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.last().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.count { it.startsWith("Receive:") })
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }
    @Test fun separatesStageTimingEvenOnFailureAndResetsAfterBrokenReporter() {
        var clock = 0L
        val output = mutableListOf<String>()
        var failReport = false
        val stats = StreamReceiveStats("video", { output.add(it); if (failReport) error("report") }) { clock }
        assertEquals(7, stats.measure(StreamReceiveStats.Stage.HEADER) { clock += 500_000_000; 7 })
        stats.measure(StreamReceiveStats.Stage.BODY) { clock += 20_000_000 }
        try { stats.measure(StreamReceiveStats.Stage.DECRYPT) { clock += 200_000; error("decode") }; fail() } catch (_: IllegalStateException) { }
        stats.measure(StreamReceiveStats.Stage.DISPATCH) { clock += 100_000 }
        failReport = true
        stats.flush(true)
        assertTrue(output.last().contains("headerMaxUs=500000 headerCalls=1 bodyMaxUs=20000 bodyCalls=1 decryptMaxUs=200 decryptCalls=1 dispatchMaxUs=100 dispatchCalls=1"))
        failReport = false
        stats.flush(true)
        assertTrue(output.last().contains("headerMaxUs=0 headerCalls=0"))
    }
    @Test fun decryptTimingIsRecordedAndResetUsingTheProductStageCounters() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.measure(StreamReceiveStats.Stage.DECRYPT) { clock += 2_000_000 }
        stats.measure(StreamReceiveStats.Stage.DECRYPT) { clock += 500_000 }
        clock = 5_000_000_000L; stats.flush()
        assertTrue(output.last().contains("decryptMaxUs=2000 decryptCalls=2"))
        clock = 10_000_000_000L; stats.flush()
        assertTrue(output.last().contains("decryptMaxUs=0 decryptCalls=0"))
    }

    @Test fun repeatedPollTimeoutsRemainOneLongWaitAndSilenceIsReportedWithoutNewPackets() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio type=102", output::add, monoMs = { clock / 1_000_000 }) { clock }
        stats.reading(); stats.received(10, 1); stats.processed()
        repeat(50) {
            stats.reading(); clock += 100_000_000; stats.readTimedOut(); stats.flush()
        }
        assertTrue(output.last().contains("readTimeouts=50 readErrors=0"))
        assertTrue(output.last().contains("pendingReadMs=5000 sinceRxMs=5000"))
        assertTrue(output.last().contains("loopDelayMaxUs=0"))
        stats.reading(); clock += 50_000_000; stats.received(10, 20, 20_480); stats.processed(); stats.flush(true)
        val gap = output.single { it.startsWith("ReceiveGap:") }
        assertTrue(gap.contains("monoMs=5050 missing=18"))
        assertTrue(gap.contains("arrivalGapMs=5050 readWaitMs=5050 priorProcessUs=0 loopDelayUs=0"))
        assertTrue(output.last().contains("readMaxMs=5050"))
        assertTrue(output.last().contains("seqMissingAfterReadOver250Ms=18"))
    }

    @Test fun localProcessingAndLoopDelayAreDistinguishableFromSocketStarvation() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading(); stats.received(10, 1)
        stats.measure(StreamReceiveStats.Stage.DISPATCH) { clock += 500_000_000 }
        stats.processed()
        clock += 400_000_000
        stats.reading(); clock += 10_000_000; stats.received(10, 3); stats.processed(); stats.flush(true)
        val gap = output.single { it.startsWith("ReceiveGap:") }
        assertTrue(gap.contains("arrivalGapMs=910 readWaitMs=10 priorProcessUs=500000 loopDelayUs=400000"))
        assertTrue(output.last().contains("dispatchMaxUs=500000 dispatchCalls=1"))
    }

    @Test fun gapEventsAreBoundedWhileSummaryRetainsAllGapsAndErrorsHideDetails() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        repeat(11) { stats.reading(); clock += 1_000_000; stats.received(10, it * 2); stats.processed() }
        stats.reading(); clock += 1_000_000; stats.readFailed("SocketException private details")
        stats.flush(true)
        assertEquals(4, output.count { it.startsWith("ReceiveGap:") })
        assertTrue(output.last().contains("seqForwardGaps=10"))
        assertTrue(output.last().contains("readErrors=1 lastReadError=unknown"))
        assertTrue(output.last().contains("gapReportsSuppressed=6"))
        assertFalse(output.joinToString().contains("private"))
        stats.reading(); stats.received(10, 24); stats.processed(); stats.flush(true)
        assertEquals(5, output.count { it.startsWith("ReceiveGap:") })
        assertTrue(output.last().contains("gapReportsSuppressed=0"))
    }

}
