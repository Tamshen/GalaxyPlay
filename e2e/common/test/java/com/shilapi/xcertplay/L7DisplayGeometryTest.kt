package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Test

class L7DisplayGeometryTest {
    @Test fun fullPanelUsesConfirmedDiagonalAndAspectRatio() {
        assertEquals(AirPlayPhysicalSizeMm(201, 268), L7DisplayGeometry.panelSize)
    }

    @Test fun occupiedRegionExcludesRailAndVerticalOcclusion() {
        assertEquals(AirPlayPhysicalSizeMm(168, 251), L7DisplayGeometry.resolve(1200, 1800, 1440, 1920))
    }

    @Test fun rotatedDisplaySwapsPhysicalAxes() {
        assertEquals(AirPlayPhysicalSizeMm(268, 201), L7DisplayGeometry.resolve(1920, 1440, 1920, 1440))
    }

    @Test fun encodingScaleDoesNotChangeViewportPhysicalSize() {
        val physical = L7DisplayGeometry.resolve(1200, 1800, 1440, 1920)
        val display = AirPlayDisplayConfig(widthPixels = 1200, heightPixels = 1800,
            widthPhysicalMm = physical.widthMm, heightPhysicalMm = physical.heightMm)
        val scaled = CarPlayDisplayScale.apply(display, 6)
        assertEquals(720, scaled.widthPixels)
        assertEquals(display.widthPhysicalMm, scaled.widthPhysicalMm)
        assertEquals(display.heightPhysicalMm, scaled.heightPhysicalMm)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unmeasuredViewportIsNotNegotiated() {
        L7DisplayGeometry.resolve(0, 1800, 1440, 1920)
    }
}
