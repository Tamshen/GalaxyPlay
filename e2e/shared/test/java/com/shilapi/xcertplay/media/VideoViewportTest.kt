package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.airplay.AirPlayHid

class VideoViewportTest {
    @Test fun l7PortraitVideoUsesTheEntirePortraitWindow() {
        assertEquals(VideoViewport(0.0, 0.0, 1440.0, 1920.0), VideoViewport.fit(1440, 1920, 1440, 1920))
    }
    @Test fun landscapeVideoInPortraitWindowSharesTheSameRectangleWithTouch() {
        val rect = VideoViewport.fit(1440, 1920, 1280, 720)
        assertEquals(555.0, rect.top, 0.001)
        assertEquals(810.0, rect.height, 0.001)
        assertFalse(rect.contains(720.0, 100.0))
        assertEquals(0.0, rect.normalizedY(555.0), 0.001)
        assertEquals(0.5, rect.normalizedY(960.0), 0.001)
        assertEquals(1.0, rect.normalizedY(1365.0), 0.001)
    }
    @Test fun aPressInBlackBarsCannotBecomeAPressAfterSlidingIntoVideo() {
        val state = VideoTouchState()
        val rect = VideoViewport(0.0, 100.0, 100.0, 100.0)
        assertTrue(state.update(listOf(VideoTouchPoint(7, 50.0, 0.0)), rect, pressed = 7).isEmpty())
        assertTrue(state.update(listOf(VideoTouchPoint(7, 50.0, 150.0)), rect).isEmpty())
    }
    @Test fun pointerIndicesCanChangeWithoutChangingTheSurvivingContactId() {
        val state = VideoTouchState()
        val rect = VideoViewport(0.0, 0.0, 100.0, 100.0)
        val first = VideoTouchPoint(7, 25.0, 25.0)
        val second = VideoTouchPoint(9, 75.0, 75.0)
        state.update(listOf(first), rect, pressed = 7)
        val both = state.update(listOf(first, second), rect, pressed = 9)
        val secondId = both.last().id
        val release = state.update(listOf(first, second), rect, lifted = 7)
        assertFalse(release.first().down)
        val surviving = state.update(listOf(second), rect).single()
        assertEquals(secondId, surviving.id)
        val report = AirPlayHid.touchReport(listOf(surviving.copy(x = surviving.x * 100, y = surviving.y * 100)))
        assertEquals(0, report[1].toInt())
        assertEquals(1, report[7].toInt())
        assertEquals(75, report[8].toInt())
        assertTrue(state.update(listOf(second), rect, cancel = true).isEmpty())
        assertTrue(state.update(listOf(second), rect).isEmpty())
    }
    @Test fun aDragOutsideVideoClampsAndStillSendsRelease() {
        val state = VideoTouchState()
        val rect = VideoViewport(10.0, 10.0, 100.0, 100.0)
        state.update(listOf(VideoTouchPoint(4, 50.0, 50.0)), rect, pressed = 4)
        val released = state.update(listOf(VideoTouchPoint(4, 200.0, -20.0)), rect, lifted = 4).single()
        assertEquals(1.0, released.x, 0.001)
        assertEquals(0.0, released.y, 0.001)
        assertFalse(released.down)
    }
}
