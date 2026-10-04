package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7ResetSettingsTest {
    @Before fun resetProcessState() {
        ReflectionHelpers.setStaticField(L7AppExit::class.java, "exiting", false)
        ReflectionHelpers.setStaticField(L7AppExit::class.java, "finishing", false)
        ReflectionHelpers.getStaticField<Handler>(L7AppExit::class.java, "handler").removeCallbacksAndMessages(null)
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stopAction", null)
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stopping", false)
        ReflectionHelpers.getStaticField<MutableList<() -> Unit>>(CarPlayBackgroundSession::class.java, "stopWaiters").clear()
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun cancelKeepsSettingsAndConfirmationRunsOnlyOnce() {
        val activity = activity()
        val prefs = activity.getSharedPreferences("reset-fixture", 0)
        prefs.edit().putString("value", "keep").commit()
        var requests = 0
        val cancelled = L7ResetSettings.confirm(activity) { requests++ }
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0, requests)
        assertEquals("keep", prefs.getString("value", null))
        val confirmed = L7ResetSettings.confirm(activity) { requests++ }
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
    }

    @Test fun systemClearIsRequestedOnceAndConnectionsRemainBlockedWhileAccepted() {
        val manager = mock(ActivityManager::class.java)
        `when`(manager.clearApplicationUserData()).thenReturn(true)
        val context = activity().applicationContext
        var rejected = 0
        L7AppExit.reset(context, { rejected++ }, { manager.clearApplicationUserData() })
        L7AppExit.reset(context, { rejected++ }, { manager.clearApplicationUserData() })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        verify(manager, times(1)).clearApplicationUserData()
        assertTrue(L7AppExit.exiting)
        assertEquals(0, rejected)
    }

    @Test fun refusalKeepsPreferencesAndAllowsRetryWithoutFalseSuccess() {
        val context = activity().applicationContext
        val prefs = context.getSharedPreferences("reset-fixture", 0)
        prefs.edit().putBoolean("keep", true).commit()
        var rejected = 0
        L7AppExit.reset(context, { rejected++ }, { false })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertFalse(L7AppExit.exiting)
        assertEquals(1, rejected)
        assertTrue(prefs.getBoolean("keep", false))
        L7AppExit.reset(context, { rejected++ }, { throw SecurityException("test") })
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(L7AppExit.exiting)
        assertEquals(2, rejected)
    }

    @Test fun stalledSessionIsBoundedAndLateStopCannotClearAgain() {
        var stopped: (() -> Unit)? = null
        val action: (() -> Unit) -> Unit = { stopped = it }
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stopAction", action)
        var requests = 0
        L7AppExit.reset(activity().applicationContext, { fail("unexpected rejection") }, { requests++; true })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0, requests)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, requests)
        stopped!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
    }
}
