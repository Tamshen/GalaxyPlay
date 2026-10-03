package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class ConnectionStageTimelineTest {
    @Test fun duplicateStatesDoNotRestartTheStageTimer() {
        var now = 100_000_000L
        val timeline = ConnectionStageTimeline { now }
        assertTrue(timeline.mark("MfiReady", 1)!!.contains("attemptElapsedMs=0"))
        now += 100_000_000
        assertNull(timeline.mark("MfiReady", 1))
        now += 200_000_000
        val rendered = timeline.mark("SurfacePresented", 1)!!
        assertTrue(rendered.contains("previous=MfiReady"))
        assertTrue(rendered.contains("stageElapsedMs=300"))
    }
    @Test fun reconnectClearsTimingAndRejectsLateOldGenerationEvents() {
        var now = 0L
        val timeline = ConnectionStageTimeline { now }
        timeline.mark("RunningWireless", 1)
        now = 900_000_000
        assertTrue(timeline.mark("StartingHotspot", 2)!!.contains("attemptElapsedMs=0"))
        assertNull(timeline.mark("SurfacePresented", 1))
        now += 50_000_000
        assertTrue(timeline.mark("SurfacePresented", 2)!!.contains("previous=StartingHotspot"))
    }
}
