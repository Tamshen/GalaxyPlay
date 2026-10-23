package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer
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
class GalaxyAudioBufferSettingsTest {
    private fun rows(view: View): List<L7SettingRow> = if (view is L7SettingRow) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { rows(view.getChildAt(it)) } else emptyList()
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun rowOf(fields: List<L7SettingRow>, title: Int, activity: Activity): L7SettingRow {
        val found = fields.firstOrNull { it.titleView.text.toString() == activity.getString(title) }
        assertNotNull("缺少设置行 ${activity.getString(title)}；现有=${fields.map { it.titleView.text }}", found)
        return found!!
    }

    private fun choose(row: L7SettingRow, index: Int, confirm: Boolean) {
        row.performClick()
        val picker = dialog()
        picker.listView.performItemClick(picker.listView.adapter.getView(index, null, picker.listView), index, index.toLong())
        picker.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun purposeChoicesCancelOrUpdateDraftAndOnlySaveAffectsFutureConnections() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            java.io.File(filesDir, "configurations").deleteRecursively()
        }
        val repository = GalaxyProfiles(activity)
        val original = repository.active()
        val connected = GalaxyConfigurationContext(activity, original)
        val page = GalaxyConfigurationPage(activity) {}
        try {
            choose(rowOf(rows(page.view.fields), R.string.config_music_stability, activity), 0, true)
            // 音频用途页在分组 3；这里原本写的 2 是方向盘页，属于本次对齐前就失效的旧索引。
            page.view.tabs.buttons[3].performClick()
            val fields = rows(page.view.fields)
            assertFalse(fields.any { it.titleView.text.toString() == activity.getString(R.string.music_buffer) })
            val phone = rowOf(fields, R.string.audio_buffer_phone_title, activity)
            choose(phone, 6, false)
            assertEquals(-1, GalaxyAudioBufferSettings.load(activity).phoneMillis)
            choose(phone, 2, true)
            choose(rowOf(fields, R.string.audio_buffer_assistant_title, activity), 1, true)
            choose(rowOf(fields, R.string.audio_buffer_navigation_title, activity), 4, true)
            assertEquals(original, repository.active())
            assertTrue(page.dirty)
            page.view.commitButton.performClick()
            // 保存会先弹文件名确认框，确认后才真正落盘。
            shadowOf(Looper.getMainLooper()).idle()
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(page.dirty)
            val next = GalaxyAudioBufferSettings.load(GalaxyConfigurationContext(activity, repository.active()))
            assertEquals(300, next.mediaMillis)
            assertEquals(32, next.phoneMillis)
            assertEquals(0, next.assistantMillis)
            assertEquals(160, next.navigationMillis)
            assertEquals(300, GalaxyAudioBufferSettings.load(connected).mediaMillis)
            assertEquals(-1, GalaxyAudioBufferSettings.load(connected).phoneMillis)
        } finally { page.close() }
    }

    @Test fun musicBufferKeepsDiPlayPresetsAndDefault() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertEquals("出厂默认回到 DiPlay 的 300 毫秒", 300, GalaxyAudioBufferSettings.load(activity).mediaMillis)
        assertEquals(listOf(300, 500, 1000), MediaAudioBuffer.wirelessPresets)
        assertEquals(300, MediaAudioBuffer.DEFAULT_MILLIS)
        for (manual in MediaAudioBuffer.wirelessPresets) {
            AirPlayPersistence.saveMediaBufferMillis(activity, manual)
            assertEquals("手动选 $manual 毫秒应原样保留", manual, GalaxyAudioBufferSettings.load(activity).mediaMillis)
        }
    }
}
