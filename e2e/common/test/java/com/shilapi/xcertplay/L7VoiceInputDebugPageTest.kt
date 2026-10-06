package com.shilapi.xcertplay

import android.Manifest
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7VoiceInputDebugPageTest {
    private lateinit var activity: GalaxySettingsActivity
    private lateinit var page: L7VoiceInputDebugPage
    private val controller = mock(CarPlayController::class.java)
    private val sink = mock(AndroidMediaSink::class.java)
    private var grants = 0
    private var creates = 0
    private val owner = Any()
    private val engine = L7VoiceInputTest(object : L7VoiceInputTest.Access {
        override fun permitted() = true
        override fun occupied() = false
        override fun create(source: Int): L7VoiceInputTest.Recorder { creates++; error("不应自动启动录音") }
    }, {}, { 0 })

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        page = L7VoiceInputDebugPage(activity, LinearLayout(activity), { grants++ }, {}, engine)
    }

    @After fun cleanup() {
        page.close(); CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun row(name: String): L7SettingRow = ReflectionHelpers.getField(page, name)
    private fun connected() {
        `when`(controller.hasActiveSession()).thenReturn(true)
        `when`(controller.requestSiri()).thenReturn(true)
        `when`(controller.activeMediaSessionOwner()).thenReturn(owner)
        CarPlayBackgroundSession.store(controller, sink, 1440, 1920, Any()) { it() }
        page.update()
    }

    @Test fun openingAndGrantingPermissionDoesNotStartCapture() {
        page.permissionResult(true)
        assertEquals(0, creates)
        assertEquals(L7VoiceInputTest.Phase.IDLE, engine.snapshot.phase)
        verify(controller, never()).requestSiri()
    }

    @Test fun siriRequiresARealSessionAndUsesTheExistingController() {
        assertFalse(row("siri").isEnabled)
        connected(); assertTrue(row("siri").isEnabled)
        row("siri").performClick()
        verify(controller, times(1)).requestSiri()
        assertEquals(activity.getString(R.string.l7_voice_siri_queued), row("siri").feedbackView.text.toString())
        assertEquals(0, creates)
    }

    @Test fun activePhoneMicrophoneDisablesLocalCaptureAndNewSiriRequest() {
        connected()
        `when`(sink.hasMicrophoneUplink()).thenReturn(true)
        page.update()
        assertFalse(row("toggle").isEnabled)
        assertFalse(row("siri").isEnabled)
        row("toggle").performClick(); row("siri").performClick()
        assertEquals(0, creates)
        verify(controller, never()).requestSiri()
    }

    @Test fun missingPermissionRequiresExplicitGrantAndKeepsSiriDisabled() {
        connected()
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        page.update()
        assertTrue(row("permission").isEnabled)
        assertFalse(row("toggle").isEnabled); assertFalse(row("siri").isEnabled)
        row("permission").performClick(); assertEquals(1, grants)
        assertEquals(0, creates)
    }

    @Test fun backgroundAndDisposedPageCannotStartCommands() {
        connected()
        page.background(); page.update()
        assertFalse(row("toggle").isEnabled); assertFalse(row("siri").isEnabled)
        row("siri").performClick(); verify(controller, never()).requestSiri()
        page.resume(); page.update(); assertTrue(row("siri").isEnabled)
        page.close(); row("siri").performClick(); verify(controller, never()).requestSiri()
        assertFalse(engine.start(6))
    }
    @Test fun siriResultIsBoundToTheQueuedRequestAndReconnectionRejectsOldResult() {
        connected()
        row("siri").performClick(); row("siri").performClick()
        verify(controller, times(1)).requestSiri()
        val results = ReflectionHelpers.getField<List<L7SettingRow>>(page, "voiceResults")
        assertTrue(results.first().isEnabled)
        results.first().performClick()
        assertTrue(L7VoiceDiagnostics.store.snapshot().lines.any { "phase=USER_OBSERVATION" in it && "RESPONDED" in it })
        val count = L7VoiceDiagnostics.store.snapshot().lines.count { "phase=USER_OBSERVATION" in it }
        `when`(controller.activeMediaSessionOwner()).thenReturn(Any())
        page.update()
        assertFalse(results.first().isEnabled)
        results.first().performClick()
        assertEquals(count, L7VoiceDiagnostics.store.snapshot().lines.count { "phase=USER_OBSERVATION" in it })
    }
    @Test fun reconnectingBeforeAResultAllowsANewRequestWithoutRelabelingTheOldOne() {
        connected(); row("siri").performClick()
        val count = L7VoiceDiagnostics.store.snapshot().lines.count { "phase=USER_OBSERVATION" in it }
        `when`(controller.activeMediaSessionOwner()).thenReturn(Any())
        page.update()
        assertTrue(row("siri").isEnabled)
        assertEquals(count, L7VoiceDiagnostics.store.snapshot().lines.count { "phase=USER_OBSERVATION" in it })
        row("siri").performClick()
        verify(controller, times(2)).requestSiri()
    }
    @Test fun mainStatusUsesHumanLabelsAndTechnicalEvidenceStartsCollapsed() {
        assertFalse(row("source").valueView.text.toString().contains("VOICE_RECOGNITION"))
        assertFalse(row("level").valueView.text.toString().contains("RMS"))
        val details = ReflectionHelpers.getField<L7DebugDetails>(page, "details")
        assertEquals(android.view.View.GONE, ReflectionHelpers.getField<L7SettingRow>(details, "body").visibility)
    }

}
