package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
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
class L7AudioSettingsTest {
    @Test fun restoringRequiresConfirmationAndRefreshesVisibleValues() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        AirPlayPersistence.saveMediaAudioChannel(context, 3)
        val parent = LinearLayout(context)
        L7AudioSettings.page(context, parent) { _, _, _, _ -> }
        fun restore() {
            row(parent, context.getString(R.string.l7_audio_restore))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        restore()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
        restore()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(101, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(L7AudioSettings.label(context, 101),
            row(parent, context.getString(R.string.l7_audio_media))!!.valueView.text.toString())
        assertNotNull(row(parent, context.getString(R.string.l7_audio_restore)))
    }

    private fun row(view: View, label: String): L7SettingRow? {
        if (view is L7SettingRow && view.titleView.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            row(view.getChildAt(index), label)?.let { return it }
        return null
    }
}
