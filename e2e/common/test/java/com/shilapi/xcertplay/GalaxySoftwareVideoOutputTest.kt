package com.shilapi.xcertplay

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowSurfaceView
import org.mockito.Mockito
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.VideoViewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE, shadows = [GalaxySoftwareVideoOutputTest.OwnedHolderShadow::class])
class GalaxySoftwareVideoOutputTest {
    @Implements(SurfaceView::class)
    class OwnedHolderShadow : ShadowSurfaceView() {
        private val holder = Mockito.mock(SurfaceHolder::class.java).apply {
            Mockito.`when`(surface).thenReturn(Mockito.mock(Surface::class.java))
        }
        @Implementation override fun getHolder(): SurfaceHolder = holder
    }
    @Test fun fittedSurfaceKeepsFullViewportAndFrameworkOwnsItsLifetime() {
        val released = mutableListOf<Surface>()
        val output = GalaxySoftwareVideoOutput(RuntimeEnvironment.getApplication(), {}, released::add, { _, _ -> })
        output.viewport.layout(0, 0, 1440, 1920)
        output.layout(VideoViewport.fit(1440, 1920, 1920, 1080))
        val bounds = output.video.layoutParams as FrameLayout.LayoutParams
        assertEquals(1440, output.viewport.width)
        assertEquals(1920, output.viewport.height)
        assertEquals(1440, bounds.width)
        assertEquals(810, bounds.height)
        assertEquals(555, bounds.topMargin)
        val stable = output.video.layoutParams
        output.layout(VideoViewport.fit(1440, 1920, 1920, 1080))
        assertSame(stable, output.video.layoutParams)
        val callback = output.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(output) as SurfaceHolder.Callback
        callback.surfaceCreated(output.video.holder)
        output.close(); output.close()
        assertEquals(1, released.size)
        callback.surfaceDestroyed(output.video.holder)
        callback.surfaceCreated(output.video.holder)
        assertEquals(1, released.size)
        output.layout(VideoViewport.fit(1920, 1440, 1920, 1080))
        assertSame(stable, output.video.layoutParams)
    }
}
