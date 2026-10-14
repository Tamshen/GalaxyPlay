package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class ConnectionCancellationTest {
    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stopping", false)
        ReflectionHelpers.getStaticField<MutableList<() -> Unit>>(CarPlayBackgroundSession::class.java, "stopWaiters").clear()
    }

    @Test fun homeIsStartedBeforeHostFinishesAndLateReleaseDoesNotReopenAnyPage() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().apply { setTheme(R.style.Theme_Xcertplay) }
        var requests = 0
        var released: (() -> Unit)? = null
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            1440, 1920, host) { done ->
            requests++
            val intent = shadowOf(host).nextStartedActivity
            assertEquals(GalaxySettingsActivity::class.java.name, intent.component!!.className)
            assertEquals("home", intent.getStringExtra("page"))
            assertFalse(intent.hasCategory(Intent.CATEGORY_HOME))
            host.finish()
            released = done
        }
        ReflectionHelpers.callInstanceMethod<Unit>(host, "cancelConnection")
        assertTrue(host.isFinishing)
        assertTrue(CarPlayBackgroundSession.isStopping())
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "cancelConnection")
        assertEquals(1, requests)
        released!!.invoke()
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun retiredOwnerCannotCancelANewerSession() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().apply { setTheme(R.style.Theme_Xcertplay) }
        var requests = 0
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            1440, 1920, Any()) { requests++ }
        ReflectionHelpers.callInstanceMethod<Unit>(host, "cancelConnection")
        assertEquals(0, requests)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun cancellationBeforeControllerExistsReturnsHomeAndInvalidatesStartup() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().apply { setTheme(R.style.Theme_Xcertplay) }
        ReflectionHelpers.callInstanceMethod<Unit>(host, "cancelConnection")
        assertEquals("home", shadowOf(host).nextStartedActivity.getStringExtra("page"))
        assertTrue(host.isFinishing)
        assertTrue(ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(host, "shuttingDown").get())
        assertEquals(1, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertTrue(ReflectionHelpers.getField<GalaxyWirelessRecovery>(host, "startupRecovery").stopped)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "cancelConnection")
        assertNull(shadowOf(host).nextStartedActivity)
    }
}
