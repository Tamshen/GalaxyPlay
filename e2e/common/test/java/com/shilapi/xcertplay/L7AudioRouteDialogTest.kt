package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioOutputPolicy
import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7AudioRouteDialogTest {
    private fun find(view: View, label: String): View? {
        if (view is L7SettingRow) {
            if (findText(view, label) != null) return view
        }
        return findText(view, label)
    }

    private fun findText(view: View, label: String): View? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            find(view.getChildAt(index), label)?.let { return it }
        return null
    }

    @Test fun selectingDoesNotSaveUntilOuterConfirmationAndCancelDiscards() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        val commits = mutableListOf<Int>()
        val dialog = L7AudioRouteDialog.show(context, "媒体音频流", 0, AudioOutputRole.MEDIA) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        find(dialog.window!!.decorView, context.getString(R.string.l7_audio_route_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        val navigation = AudioOutputPolicy.choices.indexOf(AudioOutputPolicy.NAVIGATION)
        selector.listView.performItemClick(selector.listView.adapter.getView(navigation, null, selector.listView), navigation, navigation.toLong())
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(commits.isEmpty())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(commits.isEmpty())
        val reopened = L7AudioRouteDialog.show(context, "媒体音频流", 0, AudioOutputRole.MEDIA) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(find(reopened.window!!.decorView, L7AudioSettings.label(context, 0)))
        reopened.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun savingOuterDialogCommitsChosenStreamOnce() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        val commits = mutableListOf<Int>()
        val dialog = L7AudioRouteDialog.show(context, "导航音频流", 0, AudioOutputRole.ASSISTANT) { commits.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        find(dialog.window!!.decorView, context.getString(R.string.l7_audio_route_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        val navigation = AudioOutputPolicy.choices.indexOf(AudioOutputPolicy.NAVIGATION)
        selector.listView.performItemClick(selector.listView.adapter.getView(navigation, null, selector.listView), navigation, navigation.toLong())
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        save.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(103), commits)
        assertFalse(dialog.isShowing)
    }

    @Test fun phoneConfirmationUpdatesOnlyVehicleDraftAndPausingStopsPreview() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        val repository = GalaxyProfiles(activity)
        val original = repository.applyTemplate("l7")
        val context = GalaxyConfigurationContext(activity, original, editable = true)
        val dialog = L7AudioRouteDialog.show(context, activity.getString(R.string.l7_audio_phone),
            L7AudioTemplates.load(context).choice(AudioOutputRole.PHONE), AudioOutputRole.PHONE) {
            L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.PHONE, it))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.profile_update_draft),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertNotNull(find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_phone_hint)))
        assertFalse(find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_heard))!!.isEnabled)
        find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_heard))!!.performClick()
        assertNotNull(find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_ready)))
        find(dialog.window!!.decorView, context.getString(R.string.l7_audio_route_select))!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = ShadowAlertDialog.getLatestAlertDialog()
        val index = AudioOutputPolicy.choices.indexOf(AudioOutputPolicy.MEDIA)
        selector.listView.performItemClick(selector.listView.adapter.getView(index, null, selector.listView), index, index.toLong())
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        controller.pause()
        assertNotNull(find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_stopped)))
        assertFalse(find(dialog.window!!.decorView, context.getString(R.string.l7_audio_test_stop))!!.isEnabled)
        controller.resume()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(101, L7AudioTemplates.load(context).choice(AudioOutputRole.PHONE))
        assertEquals(original, repository.refresh())
        val saved = repository.save(original.copy(configuration = context.configuration()))
        assertEquals("custom", saved.templateId)
        assertEquals(101, L7AudioTemplates.load(GalaxyConfigurationContext(activity, saved)).choice(AudioOutputRole.PHONE))
    }
}
