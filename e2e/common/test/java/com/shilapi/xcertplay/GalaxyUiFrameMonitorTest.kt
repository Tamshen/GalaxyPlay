package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyUiFrameMonitorTest {
    @Test fun onlyManualMonitoringSubscribesAndLateOldWindowMetricsCannotCount() {
        val activity = mock(Activity::class.java); val window = mock(Window::class.java); val decor = mock(View::class.java)
        `when`(activity.window).thenReturn(window); `when`(window.decorView).thenReturn(decor)
        `when`(decor.isHardwareAccelerated).thenReturn(true)
        GalaxyUiFrameMonitor.stop()
        try {
            GalaxyUiFrameMonitor.onActivityResumed(activity)
            shadowOf(Looper.getMainLooper()).idle()
            verify(window, never()).addOnFrameMetricsAvailableListener(any(), any())
            GalaxyUiFrameMonitor.start(); GalaxyUiFrameMonitor.start(); shadowOf(Looper.getMainLooper()).idle()
            val listener = ArgumentCaptor.forClass(Window.OnFrameMetricsAvailableListener::class.java)
            verify(window).addOnFrameMetricsAvailableListener(listener.capture(), any())
            val old = listener.value
            old.onFrameMetricsAvailable(window, mock(FrameMetrics::class.java), 0)
            assertEquals(1L, GalaxyUiFrameMonitor.read()!!.frames)
            GalaxyUiFrameMonitor.onActivityPaused(activity)
            verify(window).removeOnFrameMetricsAvailableListener(old)
            old.onFrameMetricsAvailable(window, mock(FrameMetrics::class.java), 0)
            assertFalse(GalaxyUiFrameMonitor.read()!!.foreground)
            assertEquals(0L, GalaxyUiFrameMonitor.read()!!.frames)
            GalaxyUiFrameMonitor.onActivityResumed(activity); shadowOf(Looper.getMainLooper()).idle()
            old.onFrameMetricsAvailable(window, mock(FrameMetrics::class.java), 0)
            assertEquals(0L, GalaxyUiFrameMonitor.read()!!.frames)
            GalaxyUiFrameMonitor.stop(); old.onFrameMetricsAvailable(window, mock(FrameMetrics::class.java), 0)
            assertNull(GalaxyUiFrameMonitor.read())
        } finally { GalaxyUiFrameMonitor.stop(); GalaxyUiFrameMonitor.onActivityDestroyed(activity) }
    }
}
