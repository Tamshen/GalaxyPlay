package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7DebugGuideTest {
    private fun activity() = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get().apply {
        setTheme(R.style.Theme_Xcertplay)
    }
    private fun row(guide: L7DebugGuide, name: String) = ReflectionHelpers.getField<L7SettingRow>(guide, name)
    private fun dialog(guide: L7DebugGuide) = ReflectionHelpers.getField<AlertDialog?>(guide, "dialog")

    @Test fun userStartsGuideBlockedStepCannotPassAndMissingIsPreserved() {
        val activity = activity()
        var complete = false
        var entered = 0
        val guide = L7DebugGuide(activity, LinearLayout(activity), "TEST", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_listen, R.string.debug_guide_listen_body,
                ready = { complete }, enter = { entered++ })
        ))
        assertNull(dialog(guide))
        assertEquals(0, entered)
        row(guide, "next").performClick()
        dialog(guide)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, entered)
        assertFalse(row(guide, "next").isEnabled)
        dialog(guide)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, activity.debugGuides.state("TEST").index)
        guide.missing()
        assertEquals(2, activity.debugGuides.state("TEST").index)
        assertEquals(1, activity.debugGuides.state("TEST").skipped)
        complete = true
        guide.close()
    }
    @Test fun backgroundRejectsLateDialogActionsAndPageRebuildRestoresStepWithoutStartingAction() {
        val activity = activity()
        val steps = listOf(L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_listen, R.string.debug_guide_listen_body, ready = { false }))
        val guide = L7DebugGuide(activity, LinearLayout(activity), "TEST", steps)
        row(guide, "next").performClick()
        val cancelled = dialog(guide)!!
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        cancelled.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, activity.debugGuides.state("TEST").index)
        row(guide, "help").performClick()
        val pending = dialog(guide)!!
        guide.background()
        pending.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, activity.debugGuides.state("TEST").index)
        guide.resume(); guide.missing(); guide.close()
        shadowOf(Looper.getMainLooper()).idle()
        val rebuilt = L7DebugGuide(activity, LinearLayout(activity), "TEST", steps)
        rebuilt.update()
        assertNull(dialog(rebuilt))
        assertFalse(row(rebuilt, "next").isEnabled)
        assertEquals(1, activity.debugGuides.state("TEST").index)
        rebuilt.close()
    }
    @Test fun configurationStateKeepsOnlyProgressAndNoPendingOperationRuns() {
        val original = L7DebugGuideStore()
        original.state("STEERING").apply { index = 3; run = 99; skipped = 1; memory.putLong("baseline", 45) }
        val saved = Bundle(); original.save(saved)
        val restored = L7DebugGuideStore(); restored.restore(saved)
        assertEquals(3, restored.state("STEERING").index)
        assertEquals(99L, restored.state("STEERING").run)
        assertEquals(45L, restored.state("STEERING").memory.getLong("baseline"))
    }
    @Test fun scenarioRequiresCurrentStepActionBeforeAcceptingAnObservation() {
        val activity = activity()
        val page = GalaxyScenarioDebugPage(activity, LinearLayout(activity)) {}
        val state = activity.debugGuides.state("SCENARIO")
        state.run = 10; state.index = 1
        page.update()
        ReflectionHelpers.callInstanceMethod<Void>(page, "observe", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertFalse(state.memory.containsKey("observed"))
        ReflectionHelpers.getField<L7SettingRow>(page, "action").performClick()
        ReflectionHelpers.callInstanceMethod<Void>(page, "observe", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(state.memory.containsKey("observed"))
        assertFalse(state.memory.getBoolean("observed"))
        page.close()
    }
    @Test fun unchangedPollDoesNotRewriteTitleAndPreventAccessibilityIdle() {
        val guide = L7DebugGuide(activity(), LinearLayout(activity()), "TEST", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body)))
        guide.update()
        var changes = 0
        row(guide, "next").titleView.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes++ }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        repeat(5) { guide.update() }
        assertEquals(0, changes)
        guide.close()
    }
}
