package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyCodecProbeControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private var occupied = false
    private var catalog = listOf(CodecProbeDecoder("hardware", true, false), CodecProbeDecoder("software", false, true))
    private val attempts = mutableListOf<Attempt>()
    private lateinit var engine: GalaxyCodecProbeController
    private lateinit var surface: Surface
    private lateinit var texture: SurfaceTexture
    private class Attempt(override val run: Long, val method: CodecProbeMethod,
        val stage: (CodecProbeStage) -> Unit, val result: (CodecProbeResult) -> Unit) : CodecProbeAttempt {
        var starts = 0
        val stops = mutableListOf<String>()
        override fun start() { starts++ }
        override fun stop(reason: String) { stops += reason }
        fun finish(exited: Boolean = true, software: Boolean = false) = result(CodecProbeResult(run, method,
            CodecProbeStage.DONE, "test", !software, software, 60, 60, 60, true, true, processExited = exited))
    }
    @Before fun setup() {
        L7Agreement.accept(context)
        texture = SurfaceTexture(0); surface = Surface(texture)
        engine = GalaxyCodecProbeController(context, { occupied }, { catalog },
            { run, method, _, _, _, _, stage, result -> Attempt(run, method, stage, result).also(attempts::add) })
    }
    @After fun cleanup() { engine.close(); surface.release(); texture.release() }
    @Test fun defaultIsHardwareOnlyAndOpeningDoesNotStart() {
        assertEquals(listOf("hardware"), engine.available.map { it.name })
        assertTrue(attempts.isEmpty()); assertFalse(engine.busy)
        engine.allowSoftware = true
        assertEquals(2, engine.available.size)
    }
    @Test fun occupiedOrMissingHardwareOrDestroyedSurfacePreventsStart() {
        occupied = true; assertFalse(engine.start(surface, false))
        occupied = false; catalog = listOf(CodecProbeDecoder("software", false, true))
        assertFalse(engine.start(surface, false)); engine.allowSoftware = true
        surface.release(); assertFalse(engine.start(surface, false)); assertTrue(attempts.isEmpty())
    }
    @Test fun stopWaitsForOwnerExitAndDoesNotContinueTheSequence() {
        assertTrue(engine.start(surface, true)); assertFalse(engine.start(surface, false))
        engine.stop("BACKGROUND"); assertTrue(engine.busy)
        attempts.single().result(CodecProbeResult(attempts.single().run, attempts.single().method,
            CodecProbeStage.FEED, reason = "BACKGROUND", processExited = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(engine.busy); assertEquals(1, attempts.size); assertEquals("BACKGROUND", engine.notice)
    }
    @Test fun allPathsRunOneAtATimeAndRejectLateOldCallbacks() {
        engine.start(surface, true)
        CodecProbeMethod.entries.forEachIndexed { index, method ->
            assertEquals(index + 1, attempts.size); assertEquals(method, attempts.last().method)
            attempts.last().finish()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201))
        }
        assertFalse(engine.busy); assertEquals(CodecProbeMethod.entries.size, engine.results.size)
        val last = engine.results.last()
        attempts.first().stage(CodecProbeStage.CREATE); attempts.first().finish()
        assertEquals(last, engine.results.last()); assertEquals(CodecProbeStage.DONE, engine.stage)
    }
    @Test fun unconfirmedProcessCleanupStopsSequenceAndBlocksAnotherRun() {
        engine.start(surface, true)
        val first = attempts.single()
        first.result(CodecProbeResult(first.run, first.method, CodecProbeStage.RELEASE,
            reason = "RELEASE_FAILED", released = false, processExited = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, attempts.size); assertFalse(engine.start(surface, false))
        assertEquals("PROCESS_UNCONFIRMED", engine.notice)
    }
    @Test fun observationIsManualAndBoundToLastCompletedRun() {
        engine.start(surface, false); engine.observe(true); assertNull(engine.observed)
        attempts.single().finish(); assertNull(engine.observed)
        engine.observe(false); assertEquals(false, engine.observed)
        assertTrue(engine.lines.last().contains("run=${attempts.single().run}"))
        assertTrue(engine.lines.last().contains("origin=USER"))
        engine.start(surface, false); assertNull(engine.observed)
    }
    @Test fun normallyReleasedDecoderCanReuseTheIdleChildProcess() {
        engine.start(surface, true); attempts.single().finish(exited = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201))
        assertEquals(2, attempts.size)
    }
    @Test fun cancelledBeforeWorkerStartsDoesNotBlockTheNextManualRun() {
        engine.start(surface, false)
        val first = attempts.single()
        engine.stop("BACKGROUND")
        first.result(CodecProbeResult(first.run, first.method, CodecProbeStage.BIND,
            reason = "BACKGROUND", workerStarted = false))
        assertTrue(engine.start(surface, false)); assertEquals(2, attempts.size)
    }
    @Test fun softwareOutputAndIncompleteOrFailedCallsCannotPassHardware() {
        val success = CodecProbeResult(1, CodecProbeMethod.NDK, CodecProbeStage.DONE, hardware = true,
            outputs = 60, eos = true, released = true)
        assertTrue(success.hardwarePassed)
        assertFalse(success.copy(software = true).hardwarePassed)
        assertFalse(success.copy(stage = CodecProbeStage.CONFIGURE).hardwarePassed)
        assertFalse(success.copy(eos = false).hardwarePassed)
        assertFalse(success.copy(released = false).hardwarePassed)
        assertFalse(success.copy(code = -1).hardwarePassed)
        assertFalse(success.copy(reason = "TIMEOUT").hardwarePassed)
    }
    @Test fun aConnectionBetweenCasesCancelsTheRemainingQueue() {
        engine.start(surface, true); attempts.single().finish(); occupied = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, attempts.size); assertFalse(engine.busy)
    }
    @Test fun bufferOutputCannotAcceptAPictureJudgmentAndKeepsCountsAcrossIpc() {
        engine.method = CodecProbeMethod.OEM_DMSDP_BUFFER
        engine.start(surface, false)
        val first = attempts.single()
        val result = CodecProbeResult(first.run, first.method, CodecProbeStage.DONE,
            outputs = 60, eos = true, released = true, configInputs = 1, outputBytes = 123456)
        first.result(CodecProbeResult.read(result.bundle()))
        engine.observe(true)
        assertNull(engine.observed)
        assertEquals(123456L, engine.results.single().outputBytes)
        assertEquals(1, engine.results.single().configInputs)
        assertTrue(engine.lines.last().contains("surfaceOutput=false"))
    }
}
