package com.shilapi.xcertplay

import android.app.Activity
import com.shilapi.xcertplay.media.AudioOutputRole
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L6AudioTemplatesTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun explicitL6UsesVerifiedRolesFocusAndNoGuessedBus() {
        val context = context()
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertEquals(listOf(L7AudioTemplates.Mode.L6, L7AudioTemplates.Mode.CUSTOM), L7AudioTemplates.modes(context))
        val template = L7AudioTemplates.load(context)
        assertEquals("L6", template.name)
        assertEquals(101, template.choice(AudioOutputRole.MEDIA))
        assertEquals(103, template.choice(AudioOutputRole.NAVIGATION))
        assertEquals(102, template.choice(AudioOutputRole.ASSISTANT))
        assertFalse(template.preferBus)
        val json = JSONObject(template.toJson())
        assertEquals(0, json.getJSONObject("outputBuses").length())
        assertEquals(3, json.getJSONObject("focusGains").getInt("navigation"))
        assertEquals(3, json.getJSONObject("focusGains").getInt("ringtone"))
        assertEquals(2, json.getJSONObject("focusGains").getInt("phone"))
    }

    @Test fun modelSwitchAppliesPresetAndPreservesBothFilesAndFrozenSessionTemplate() {
        val context = context()
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 20))
        val frozen = L7AudioTemplates.load(context)
        val l7 = File(context.filesDir, "audio-template.json").readText()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.NAVIGATION, 19))
        val l6 = File(context.filesDir, "audio-template-l6.json").readText()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        assertEquals(L7AudioTemplates.Mode.L7, L7AudioTemplates.mode(context))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(20, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(19, L7AudioTemplates.load(context).choice(AudioOutputRole.NAVIGATION))
        assertEquals(l7, File(context.filesDir, "audio-template.json").readText())
        assertEquals(l6, File(context.filesDir, "audio-template-l6.json").readText())
        assertEquals(20, frozen.choice(AudioOutputRole.MEDIA))
        assertEquals("L7", frozen.name)
    }

    @Test fun legacyL7MigratesBeforeSwitchAndCannotBeOverwrittenByL6Restore() {
        val context = context()
        AirPlayPersistence.saveNavigationAudioChannel(context, 18)
        AirPlayPersistence.saveL7AudioBusEnabled(context, true)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        AirPlayPersistence.restoreUsageAudioDefaults(context)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        assertEquals(L7AudioTemplates.Mode.L7, L7AudioTemplates.mode(context))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(18, L7AudioTemplates.load(context).choice(AudioOutputRole.NAVIGATION))
        assertTrue(L7AudioTemplates.load(context).preferBus)
    }

    @Test fun damagedL6FallsBackToL6AndPreservesBothOriginalFiles() {
        val context = context()
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 17))
        val l7 = File(context.filesDir, "audio-template.json").readText()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        File(context.filesDir, "audio-template-l6.json").writeText("broken")
        assertTrue(L7AudioTemplates.customInvalid(context))
        assertEquals("L6", L7AudioTemplates.load(context).name)
        assertEquals("broken", File(context.filesDir, "audio-template-l6.json").readText())
        assertEquals(l7, File(context.filesDir, "audio-template.json").readText())
    }

    @Test fun l6BackupIsRecoveredWithoutReadingOrChangingL7Template() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.ASSISTANT, 16))
        val file = File(context.filesDir, "audio-template-l6.json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.L6)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(16, L7AudioTemplates.load(context).choice(AudioOutputRole.ASSISTANT))
        assertFalse(File(context.filesDir, "audio-template.json").exists())
    }

    @Test fun staleModelSaveCannotOverwriteEitherCustomFile() {
        val context = context()
        val previous = L7AudioTemplates.load(context)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertTrue(runCatching { L7AudioTemplates.saveCustom(context, previous, L7AudioTemplates.Model.L7) }.isFailure)
        assertFalse(File(context.filesDir, "audio-template.json").exists())
        assertFalse(File(context.filesDir, "audio-template-l6.json").exists())
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
    }

    @Test fun unknownModelAndCrossModelModesNeverSelectGuessedBus() {
        val context = context()
        val prefs = context.getSharedPreferences("l7_audio_templates", 0)
        prefs.edit().putString("model", "unknown").commit()
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        prefs.edit().putString("model", "l6").putString("mode_l6", "l7-bus").commit()
        assertEquals(L7AudioTemplates.Mode.L6, L7AudioTemplates.mode(context))
        assertFalse(L7AudioTemplates.load(context).preferBus)
        assertTrue(runCatching { L7AudioTemplates.select(context, L7AudioTemplates.Mode.BUS) }.isFailure)
    }
}
