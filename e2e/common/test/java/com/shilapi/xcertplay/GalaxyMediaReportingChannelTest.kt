package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyMediaReportingChannelTest {
    private val key = GalaxyMediaReportingChannel.KEY
    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        java.io.File(filesDir, "configurations").deleteRecursively()
    }
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun row(view: View): L7SettingRow? {
        if (view is L7SettingRow && view.titleView.text == view.context.getString(R.string.galaxy_media_channel_title)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) row(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun choose(page: GalaxyConfigurationPage, mode: GalaxyMediaReportingChannel.Mode) {
        row(page.view.fields)!!.performClick()
        val picker = dialog()
        assertEquals(4, picker.listView.adapter.count)
        picker.listView.performItemClick(picker.listView.adapter.getView(mode.ordinal, null, picker.listView), mode.ordinal, mode.ordinal.toLong())
    }

    @Test fun absentOrUnrecognizedPreferenceKeepsCurrentModeWithoutMigratingOldKeys() {
        val context = activity()
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().remove(key).commit()
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(context))
        assertFalse(prefs.contains(key))
        prefs.edit().putString(key, "unrecognized").commit()
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(context))
        assertEquals("unrecognized", prefs.getString(key, null))
    }

    @Test fun cancellationDoesNotChangeDraftAndConfirmationRequiresPageSave() {
        val context = activity()
        val page = GalaxyConfigurationPage(context) {}
        page.view.tabs.buttons[4].performClick()
        choose(page, GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(page.dirty)
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(context))
        choose(page, GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(page.dirty)
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(context))
        page.view.commitButton.performClick()
        assertFalse(page.dirty)
        assertEquals(GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT, GalaxyMediaReportingChannel.load(context))
        choose(page, GalaxyMediaReportingChannel.Mode.QQ_COMPAT)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT, GalaxyMediaReportingChannel.load(context))
        page.view.commitButton.performClick()
        assertEquals(GalaxyMediaReportingChannel.Mode.QQ_COMPAT, GalaxyMediaReportingChannel.load(context))
        choose(page, GalaxyMediaReportingChannel.Mode.LOCAL)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        page.view.commitButton.performClick()
        assertEquals(GalaxyMediaReportingChannel.Mode.LOCAL, GalaxyMediaReportingChannel.load(context))
        page.close()
    }

    @Test fun changingSavedChannelKeepsExistingSessionSnapshotAndIndependentSettings() {
        val context = activity()
        val repository = GalaxyProfiles(context)
        val original = repository.applyTemplate("l6")
        val session = GalaxyConfigurationContext(context, original, runtimeOnly = true)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putString("manual_hotspot_ssid", "TEST_WIFI").commit()
        AppLocale.save(context, "en")
        val draft = GalaxyConfigurationContext(context, original, editable = true)
        draft.getSharedPreferences("xcertplay_airplay", 0).edit().putString(key, GalaxyMediaReportingChannel.Mode.LOCAL.name).commit()
        val saved = repository.save(original.copy(configuration = draft.configuration()))
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(session))
        assertEquals(GalaxyMediaReportingChannel.Mode.LOCAL, GalaxyMediaReportingChannel.load(context))
        assertEquals(GalaxyMediaReportingChannel.Mode.LOCAL,
            GalaxyMediaReportingChannel.load(GalaxyConfigurationContext(context, saved, runtimeOnly = true)))
        repository.resetCurrent(saved)
        assertEquals(GalaxyMediaReportingChannel.Mode.CURRENT, GalaxyMediaReportingChannel.load(context))
        assertEquals("TEST_WIFI", context.getSharedPreferences("xcertplay_airplay", 0).getString("manual_hotspot_ssid", null))
        assertEquals("en", AppLocale.preference(context))
    }

    @Test fun channelEnumsRemainVisibleInSanitizedEvidenceButInvalidConfigurationIsRejected() {
        val context = activity()
        val original = GalaxyProfiles(context).active()
        val draft = GalaxyConfigurationContext(context, original, editable = true)
        for (mode in GalaxyMediaReportingChannel.Mode.entries) {
            draft.getSharedPreferences("xcertplay_airplay", 0).edit().putString(key, mode.name).commit()
            val evidence = GalaxyConfigurationEvidence.from(context, original.copy(configuration = draft.configuration()))
            val prefs = JSONObject(evidence.text).getJSONObject("configuration").getJSONObject("preferences")
            assertEquals(mode.name, prefs.getJSONObject("xcertplay_airplay").getJSONObject(key).getString("value"))
            assertEquals(evidence, GalaxyConfigurationEvidence.read(evidence.text))
        }
        draft.getSharedPreferences("xcertplay_airplay", 0).edit().putString(key, "invalid").commit()
        assertTrue(runCatching { GalaxyConfigurationFields.validate(draft.configuration()) }.isFailure)
    }
}
