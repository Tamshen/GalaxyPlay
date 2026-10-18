package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GalaxyUiFramesTest {
    private fun input(time: Long, ui: GalaxyUiFrames?, connected: Boolean = false) =
        GalaxyPerformanceInput(time, 0, 0, 0, null, null, null, null, null, null, emptyList(), emptyList(),
            uiFrames = ui, carPlayConnected = connected)
    private fun GalaxyPerformanceRates.take(time: Long, ui: GalaxyUiFrames?) =
        sample(input(time, ui), false, 0, 0, 0, false)

    @Test fun disabledAndLateCallbacksNeverCreateFramesOrReviveAClosedWindow() {
        val counter = GalaxyUiFrameCounter()
        assertNull(counter.read())
        counter.frame(0, 0); assertNull(counter.read())
        val first = counter.open(true)
        counter.frame(first, 0); assertEquals(1L, counter.read()!!.frames)
        val next = counter.open(true)
        counter.frame(first, 0); assertEquals(0L, counter.read()!!.frames)
        counter.frame(next, 0); assertEquals(1L, counter.read()!!.frames)
        counter.close(); counter.frame(next, 0); assertNull(counter.read())
    }
    @Test fun appFramesCanRunWithoutCarPlayAndStaticWindowsDoNotPretendToBe60() {
        val counter = GalaxyUiFrameCounter(); val epoch = counter.open(true); val rates = GalaxyPerformanceRates()
        assertNull(rates.take(0, counter.read()).uiFps)
        repeat(48) { counter.frame(epoch, 0) }
        val animated = rates.take(1000, counter.read())
        assertEquals(48.0, animated.uiFps!!, 0.001)
        assertEquals(GalaxyUiFpsState.ACTIVE, animated.uiState)
        assertNull(animated.main); assertFalse(animated.input.carPlayConnected)
        val idle = rates.take(2000, counter.read())
        assertEquals(0.0, idle.uiFps!!, 0.0); assertEquals(GalaxyUiFpsState.STATIC, idle.uiState)
    }
    @Test fun anAlreadyStaticWindowCanReportIdleWithoutForcingItsFirstDraw() {
        val counter = GalaxyUiFrameCounter(); counter.open(true); val rates = GalaxyPerformanceRates()
        rates.take(0, counter.read())
        val idle = rates.take(1000, counter.read())
        assertEquals(0.0, idle.uiFps!!, 0.0); assertEquals(GalaxyUiFpsState.STATIC, idle.uiState)
    }
    @Test fun lostReportsAreNotSilentlyCountedAsZeroAndTheNextCleanWindowCanRecover() {
        val counter = GalaxyUiFrameCounter(); val epoch = counter.open(true); val rates = GalaxyPerformanceRates()
        rates.take(0, counter.read()); counter.frame(epoch, 3)
        val lost = rates.take(1000, counter.read())
        assertNull(lost.uiFps); assertEquals(GalaxyUiFpsState.INCOMPLETE, lost.uiState)
        counter.frame(epoch, 0)
        assertEquals(1.0, rates.take(2000, counter.read()).uiFps!!, 0.0)
    }
    @Test fun activityReplacementBackgroundAndUnavailableFramesHaveSeparateStates() {
        val counter = GalaxyUiFrameCounter(); val first = counter.open(true); val rates = GalaxyPerformanceRates()
        rates.take(0, counter.read()); counter.frame(first, 0)
        counter.open(true)
        assertEquals(GalaxyUiFpsState.WAITING, rates.take(1000, counter.read()).uiState)
        counter.open(false)
        assertEquals(GalaxyUiFpsState.BACKGROUND, rates.take(2000, counter.read()).uiState)
        counter.open(true, false)
        assertEquals(GalaxyUiFpsState.UNAVAILABLE, rates.take(3000, counter.read()).uiState)
    }
}
