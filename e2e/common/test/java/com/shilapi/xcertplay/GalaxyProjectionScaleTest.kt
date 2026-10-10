package com.shilapi.xcertplay

import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyProjectionScaleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val display = AirPlayDisplayConfig(1440, 1920, 200, 270, 30)
    @Test fun exactUiPercentIsSavedAndChangesTheActualEvenCanvasInsteadOfFallingBack() {
        for ((percent, expected) in listOf(125 to (1152 to 1536), 137 to (1052 to 1402), 150 to (960 to 1280))) {
            AirPlayPersistence.saveUiScalePercent(app, percent)
            assertEquals(percent, AirPlayPersistence.loadUiScalePercent(app))
            val actual = GalaxyProjectionScale.icons(display, AirPlayPersistence.loadUiScalePercent(app))
            assertEquals(expected.first, actual.widthPixels)
            assertEquals(expected.second, actual.heightPixels)
            assertEquals(display.widthPhysicalMm, actual.widthPhysicalMm)
            assertEquals(display.heightPhysicalMm, actual.heightPhysicalMm)
            assertEquals(display.fps, actual.fps)
        }
    }
    @Test fun legacyPresetsKeepTheirExistingGeometryAndUnsupportedEnlargementKeepsOriginal() {
        CarPlayUiScale.presets.forEach { assertEquals(CarPlayUiScale.apply(display, it), GalaxyProjectionScale.icons(display, it)) }
        assertSame(display, GalaxyProjectionScale.icons(display, 50))
        for (invalid in listOf(-1, 49, 201, Int.MAX_VALUE)) {
            assertEquals(100, GalaxyProjectionScale.sanitize(invalid))
            assertSame(display, GalaxyProjectionScale.icons(display, invalid))
        }
    }
    @Test fun customUiScaleKeepsSafeAndViewInsetsProportional() {
        val source = display.copy(viewArea = AirPlayInsets(20, 40, 60, 80), safeArea = AirPlayInsets(10, 30, 50, 70))
        val actual = GalaxyProjectionScale.icons(source, 125)
        assertEquals(AirPlayInsets(16, 32, 48, 64), actual.viewArea)
        assertEquals(AirPlayInsets(8, 24, 40, 56), actual.safeArea)
    }
    @Test fun resolutionUsesExactVehiclePercentForGalaxyAndKeepsLegacyHostBehavior() {
        AirPlayPersistence.saveDisplayScalePercent(app, 73)
        val resources = spy(app.resources)
        val context = object : ContextWrapper(app) { override fun getResources() = resources }
        `when`(resources.getBoolean(R.bool.config_l7_product_ui)).thenReturn(true)
        val actual = GalaxyProjectionScale.resolution(context, display, 10)
        assertEquals(1052, actual.widthPixels)
        assertEquals(1402, actual.heightPixels)
        `when`(resources.getBoolean(R.bool.config_l7_product_ui)).thenReturn(false)
        assertEquals(CarPlayDisplayScale.apply(display, 8), GalaxyProjectionScale.resolution(context, display, 8))
    }
}
