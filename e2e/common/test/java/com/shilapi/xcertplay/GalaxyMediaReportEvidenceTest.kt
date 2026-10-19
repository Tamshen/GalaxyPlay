package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyMediaReportEvidenceTest {
    private var time = 100L
    private var current = true
    private val logs = mutableListOf<String>()
    private val checkpoints = mutableListOf<Pair<Long, Long>>()
    private val evidence = GalaxyMediaReportEvidence(1, true, { current }, logs::add,
        { id, revision -> checkpoints += id to revision }, { time })
    private val song = CarPlayNowPlaying(title = "private-song", artist = "private-artist", playing = true, playbackKnown = true)
    private val correct = GalaxyMediaReportEvidence.Seen.CORRECT
    private val missing = GalaxyMediaReportEvidence.Seen.MISSING
    private val cluster = GalaxyMediaReportEvidence.Target.CLUSTER
    @After fun cleanup() { evidence.stop(); evidence.released() }

    @Test fun acceptedRegistrationAndStateNeverInventOwnFocusOrInstrumentSuccess() {
        evidence.activate(); evidence.input(song, true)
        evidence.result("register", true)
        evidence.result("sourceList", true)
        evidence.result("registrationActive", true)
        evidence.result("requestPlay", true)
        evidence.result("updateState", true)
        assertTrue(evidence.observe(evidence.snapshot(), cluster, missing, missing, missing))
        val text = logs.joinToString("\n")
        assertTrue(text.contains("focus=UNKNOWN"))
        assertTrue(text.contains("target=CLUSTER"))
        assertTrue(text.contains("dimPublish=NOT_IMPLEMENTED downstream=UNVERIFIED"))
        assertFalse(text.contains("private-song")); assertFalse(text.contains("private-artist"))
        assertEquals(1, checkpoints.size)
    }

    @Test fun feedbackRepeatsRegistrationAndServiceEvidenceAfterInitialLogsAreGone() {
        evidence.activate(); evidence.input(song, true)
        evidence.details(mapOf("sdkProvider" to 1, "registrationParams" to 3, "catalog" to 2,
            "dimService" to 1, "dimExported" to 1), 6)
        evidence.result("requestPlay", true); evidence.focus(true)
        logs.clear()
        time += 30_000
        evidence.input(song.copy(elapsedMillis = 30_000), true)
        assertTrue(evidence.observe(evidence.snapshot(), cluster, correct, missing, correct,
            GalaxyMediaReportEvidence.Comparison.OTHER_WORKS))
        val text = logs.joinToString("\n")
        for (field in listOf("catalog=UPDATED", "dimService=VISIBLE", "source=6", "requestCount=1", "focus=OWN",
            "comparison=OTHER_WORKS", "cover=MISSING")) assertTrue(field, text.contains(field))
        assertTrue(text.contains("event=SUMMARY"))
    }

    @Test fun progressDoesNotInvalidateTrackButSongPlaybackAndCoverChangesDo() {
        evidence.activate(); evidence.input(song, true, "cover-a")
        val expected = evidence.snapshot()
        evidence.input(song.copy(elapsedMillis = 1000), true, "cover-a")
        assertTrue(evidence.observe(expected, cluster, correct, correct, correct))
        evidence.input(song, true, "cover-b")
        assertFalse(evidence.observe(expected, cluster, correct, correct, correct))
        val next = evidence.snapshot()
        evidence.input(song.copy(playing = false), true, "cover-b")
        assertFalse(evidence.observe(next, cluster, correct, correct, correct))
        val paused = evidence.snapshot()
        evidence.input(song.copy(title = "another", playing = false), true, "cover-b")
        assertFalse(evidence.observe(paused, cluster, correct, correct, correct))
    }

    @Test fun oldSessionCannotRecordOrClearNewSessionAndStoppedFeedbackIsRejected() {
        evidence.activate(); evidence.input(song, false)
        val old = evidence.snapshot()
        val next = GalaxyMediaReportEvidence(2, true, { true }, logs::add)
        next.activate(); next.input(song, false)
        assertFalse(evidence.observe(old, cluster, correct, correct, correct))
        evidence.stop()
        assertSame(next, GalaxyMediaReportEvidence.active)
        val expected = next.snapshot()
        next.stop()
        assertFalse(next.observe(expected, cluster, correct, correct, correct))
        next.released()
        assertNull(GalaxyMediaReportEvidence.active)
    }

    @Test fun failureSummaryRetainsStageAndTypeWithoutExceptionOrMediaText() {
        evidence.activate(); evidence.input(song, true)
        evidence.failure("requestPlay", SecurityException("private-error"))
        logs.clear()
        evidence.observe(evidence.snapshot(), cluster, missing, missing, missing)
        val text = logs.joinToString("\n")
        assertTrue("failureStage=requestPlay" in text)
        assertTrue("failureType=SecurityException" in text)
        assertFalse("private-error" in text)
    }

    @Test fun unobservedFieldsAndExpiredSessionCannotBeRecordedAsDisplaySuccess() {
        evidence.activate(); evidence.input(song, false)
        val unknown = GalaxyMediaReportEvidence.Seen.UNOBSERVED
        assertFalse(evidence.observe(evidence.snapshot(), cluster, unknown, unknown, unknown))
        current = false
        assertFalse(evidence.observe(evidence.snapshot(), cluster, correct, correct, correct))
        assertTrue(checkpoints.isEmpty())
    }

    @Test fun syntheticProgressChangeNeedsFreshObservationAndUploadKeepsFeedbackUnderMediaLoad() {
        val manual = GalaxyMediaReportEvidence(3, true, { true }, logs::add, synthetic = true)
        manual.activate(); manual.input(song.copy(elapsedMillis = 0), true)
        val expected = manual.snapshot()
        manual.input(song.copy(elapsedMillis = 15_000), true)
        assertFalse(manual.observe(expected, cluster, correct, correct, correct))
        logs.clear()
        assertTrue(manual.observe(manual.snapshot(), cluster, correct, missing, correct))
        val anchors = logs.toList()
        val report = RemoteLogReport.create(anchors.asSequence() + (1..20_000).asSequence().map {
            "Video stats event=$it detail=" + "统计".repeat(300)
        }, "test", "test") {}
        val retained = report.batches.flatMap { it.entries }.map { it.message }
        assertTrue(retained.containsAll(anchors))
        manual.stop(); manual.released()
    }

    @Test fun changedTrackMarksQueuedCheckpointStaleAndLostServiceClearsOldEvidence() {
        evidence.activate(); evidence.input(song, true)
        val expected = evidence.snapshot()
        evidence.observe(expected, cluster, correct, correct, correct)
        evidence.input(song.copy(title = "next"), true)
        evidence.checkpoint(1, expected.revision)
        assertTrue(logs.any { "event=STALE_CHECKPOINT" in it })
        evidence.focus(true); evidence.ready(false)
        assertEquals(0L, evidence.snapshot().facts["focus"])
        assertEquals(-1L, evidence.snapshot().facts["stateResult"])
        assertEquals(-1L, evidence.snapshot().facts["tokenValid"])
        assertEquals(0L, evidence.snapshot().facts["registered"])
        assertEquals(0L, evidence.snapshot().facts["catalog"])
    }
}
