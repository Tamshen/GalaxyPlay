package com.shilapi.xcertplay

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioRoutingTemplate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7AudioTemplatesTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun shippedTemplatesUseStandardRolesAndSeparateBusPreference() {
        val context = context()
        val normal = L7AudioTemplates.builtin(context, L7AudioTemplates.Mode.L7)
        val bus = L7AudioTemplates.builtin(context, L7AudioTemplates.Mode.BUS)
        assertFalse(normal.preferBus)
        assertTrue(bus.preferBus)
        for ((role, choice) in listOf(AudioOutputRole.MEDIA to 101, AudioOutputRole.NAVIGATION to 103, AudioOutputRole.ASSISTANT to 102)) {
            assertEquals(choice, normal.choice(role))
            assertEquals(choice, bus.choice(role))
        }
        assertTrue(bus.toJson().contains("bus1_navigation_out"))
        assertFalse(bus.toJson().contains("BUS00_MEDIA"))
    }

    @Test fun switchingBuiltinsPreservesCustomChoicesAndSessionSnapshot() {
        val context = context()
        val custom = L7AudioTemplates.builtin(context, L7AudioTemplates.Mode.BUS).withChoice(AudioOutputRole.MEDIA, 20)
        L7AudioTemplates.saveCustom(context, custom)
        val snapshot = L7AudioTemplates.load(context)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.L7)
        assertEquals(101, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.BUS)
        assertTrue(L7AudioTemplates.load(context).preferBus)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(20, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        assertEquals(20, snapshot.choice(AudioOutputRole.MEDIA))
    }

    @Test fun legacyChoicesAndBusSwitchAreMigratedOnceWithoutLoss() {
        val context = context()
        AirPlayPersistence.saveNavigationAudioChannel(context, 19)
        AirPlayPersistence.saveL7AudioBusEnabled(context, true)
        assertEquals(L7AudioTemplates.Mode.CUSTOM, L7AudioTemplates.mode(context))
        assertEquals(19, L7AudioTemplates.load(context).choice(AudioOutputRole.NAVIGATION))
        assertTrue(L7AudioTemplates.load(context).preferBus)
        AirPlayPersistence.saveNavigationAudioChannel(context, 14)
        assertEquals(19, L7AudioTemplates.load(context).choice(AudioOutputRole.NAVIGATION))
    }

    @Test fun malformedOversizedAndInvalidUtf8ImportNeverReplaceSavedTemplate() {
        val context = context()
        L7AudioTemplates.saveCustom(context, AudioRoutingTemplate.system().withChoice(AudioOutputRole.ASSISTANT, 18))
        for (bytes in listOf("{}".toByteArray(), ByteArray(AudioRoutingTemplate.MAX_BYTES + 1), byteArrayOf(0xc3.toByte(), 0x28)))
            assertTrue(runCatching { L7AudioTemplates.saveCustom(context, L7AudioTemplates.parse(ByteArrayInputStream(bytes))) }.isFailure)
        assertEquals(18, L7AudioTemplates.load(context).choice(AudioOutputRole.ASSISTANT))
    }

    @Test fun interruptedAtomicWriteIsRecoveredWhenReturningToCustom() {
        val context = context()
        L7AudioTemplates.saveCustom(context, AudioRoutingTemplate.system().withChoice(AudioOutputRole.MEDIA, 17))
        val file = File(context.filesDir, "audio-template.json")
        check(file.renameTo(File(file.path + ".bak")))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.L7)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(17, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
    }

    @Test fun unreadableCustomFallsBackWithoutDestroyingFile() {
        val context = context()
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        val file = File(context.filesDir, "audio-template.json")
        file.writeText("broken")
        assertTrue(L7AudioTemplates.customInvalid(context))
        assertEquals(101, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        assertFalse(L7AudioTemplates.load(context).preferBus)
        assertEquals("broken", file.readText())
    }

    @Test fun editorRejectsInvalidDraftAndCancelDoesNotSave() {
        val context = context()
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        var changes = 0
        L7AudioTemplateEditor.show(context) { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val editor = editor(dialog.window!!.decorView)!!
        editor.setText("{}")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertNotNull(editor.error)
        assertEquals(0, changes)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(101, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
    }

    @Test fun validEditorDraftIsCommittedExactlyOnce() {
        val context = context()
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        var changes = 0
        L7AudioTemplateEditor.show(context) { changes++ }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        editor(dialog.window!!.decorView)!!.setText(AudioRoutingTemplate.system().withChoice(AudioOutputRole.MEDIA, 16).toJson())
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        save.performClick()
        assertEquals(1, changes)
        assertEquals(16, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        assertFalse(dialog.isShowing)
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            editor(view.getChildAt(index))?.let { return it }
        return null
    }
}
