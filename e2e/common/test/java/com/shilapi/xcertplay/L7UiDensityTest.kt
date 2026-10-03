package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7UiDensityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultsToMediumAndStoresCustomWithoutChangingSystemOrProjection() {
        val systemDensity = context.resources.configuration.densityDpi
        val projection = AirPlayPersistence.loadDisplayScaleTenths(context)
        assertEquals(280, L7UiDensity.value(context))
        L7UiDensity.save(context, 300)
        assertEquals(300, L7UiDensity.value(context.applicationContext))
        assertEquals(systemDensity, context.resources.configuration.densityDpi)
        assertEquals(projection, AirPlayPersistence.loadDisplayScaleTenths(context))
    }

    @Test fun invalidInputCannotReplaceTheSavedValue() {
        L7UiDensity.save(context, 240)
        for (value in listOf(0, 159, 481, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { L7UiDensity.save(context, value) }
            assertEquals(240, L7UiDensity.value(context))
        }
        context.getSharedPreferences("l7_ui", Context.MODE_PRIVATE).edit().putInt("density", -1).commit()
        assertEquals(280, L7UiDensity.value(context))
    }

    @Test fun uiConfigurationConvertsDpAndPreservesSystemPixelAreaAndFont() {
        val base = Configuration().apply {
            densityDpi = 320
            screenWidthDp = 720
            screenHeightDp = 960
            smallestScreenWidthDp = 720
            fontScale = 1.5f
            setLocale(Locale.SIMPLIFIED_CHINESE)
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val adjusted = Configuration(base).apply { updateFrom(L7UiDensity.configurationFor(base, 240)) }
        assertEquals(960, adjusted.screenWidthDp)
        assertEquals(1280, adjusted.screenHeightDp)
        assertEquals(1440, adjusted.screenWidthDp * adjusted.densityDpi / 160)
        assertEquals(1920, adjusted.screenHeightDp * adjusted.densityDpi / 160)
        assertEquals(base.fontScale, adjusted.fontScale)
        assertEquals(base.uiMode, adjusted.uiMode)
        assertEquals(base.locales, adjusted.locales)
        assertEquals(320, base.densityDpi)
        assertEquals(720, base.screenWidthDp)
    }
}
