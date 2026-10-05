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
class CustomVehicleTemplatesTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun customModelStartsWithStandardRolesAndNoVehicleBusOrFocusOverrides() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        assertEquals(listOf(L7AudioTemplates.Mode.SYSTEM, L7AudioTemplates.Mode.CUSTOM), L7AudioTemplates.modes(context))
        assertEquals(L7AudioTemplates.Mode.CUSTOM, L7AudioTemplates.mode(context))
        val template = L7AudioTemplates.load(context)
        assertEquals(101, template.choice(AudioOutputRole.MEDIA))
        assertEquals(103, template.choice(AudioOutputRole.NAVIGATION))
        assertEquals(102, template.choice(AudioOutputRole.ASSISTANT))
        assertFalse(template.preferBus)
        val json = JSONObject(template.toJson())
        assertEquals(0, json.getJSONObject("outputBuses").length())
        assertFalse(json.has("focusGains"))
        assertEquals(L7AudioTemplates.Model.CUSTOM, L7AudioTemplates.model(context.applicationContext))
    }

    @Test fun threeFilesStayIndependentAndKnownModelsAlwaysFillTheirPreset() {
        val context = context()
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 20))
        val l7 = File(context.filesDir, "audio-template.json").readText()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.NAVIGATION, 19))
        val l6 = File(context.filesDir, "audio-template-l6.json").readText()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.ASSISTANT, 18))
        val custom = File(context.filesDir, "audio-template-custom.json").readText()
        val frozen = L7AudioTemplates.load(context)
        for ((model, preset) in listOf(L7AudioTemplates.Model.L7 to L7AudioTemplates.Mode.L7,
            L7AudioTemplates.Model.L6 to L7AudioTemplates.Mode.L6)) {
            L7AudioTemplates.selectModel(context, model)
            assertEquals(preset, L7AudioTemplates.mode(context))
            assertEquals(102, L7AudioTemplates.load(context).choice(AudioOutputRole.ASSISTANT))
        }
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        assertEquals(18, L7AudioTemplates.load(context).choice(AudioOutputRole.ASSISTANT))
        assertEquals(l7, File(context.filesDir, "audio-template.json").readText())
        assertEquals(l6, File(context.filesDir, "audio-template-l6.json").readText())
        assertEquals(custom, File(context.filesDir, "audio-template-custom.json").readText())
        assertEquals(18, frozen.choice(AudioOutputRole.ASSISTANT))
    }

    @Test fun damagedCustomModelFallsBackToSystemAndKeepsRawFile() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        val file = File(context.filesDir, "audio-template-custom.json")
        file.writeText("broken")
        assertTrue(L7AudioTemplates.customInvalid(context))
        assertEquals("System", L7AudioTemplates.load(context).name)
        assertEquals(0, JSONObject(L7AudioTemplates.load(context).toJson()).getJSONObject("outputBuses").length())
        assertEquals("broken", file.readText())
    }

    @Test fun customBackupSurvivesSwitchingThroughKnownModel() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 17))
        val file = File(context.filesDir, "audio-template-custom.json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        assertEquals(17, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        assertFalse(File(context.filesDir, "audio-template-l6.json").exists())
    }

    @Test fun staleModelSaveCannotReplaceCustomVehicleFile() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        val file = File(context.filesDir, "audio-template-custom.json")
        val before = file.readText()
        val old = L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 20)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        assertTrue(runCatching { L7AudioTemplates.saveCustom(context, old, L7AudioTemplates.Model.CUSTOM) }.isFailure)
        assertEquals(before, file.readText())
        assertFalse(File(context.filesDir, "audio-template-l6.json").exists())
    }

    @Test fun customSystemRestorePreservesEditableFileAndRejectsVehicleProfiles() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.CUSTOM)
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(AudioOutputRole.MEDIA, 16))
        val file = File(context.filesDir, "audio-template-custom.json")
        val before = file.readText()
        L7AudioTemplates.select(context, L7AudioTemplates.defaultMode(context))
        assertEquals(L7AudioTemplates.Mode.SYSTEM, L7AudioTemplates.mode(context))
        assertEquals(101, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
        assertEquals(before, file.readText())
        for (mode in listOf(L7AudioTemplates.Mode.L7, L7AudioTemplates.Mode.L6, L7AudioTemplates.Mode.BUS))
            assertTrue(runCatching { L7AudioTemplates.select(context, mode) }.isFailure)
        L7AudioTemplates.select(context, L7AudioTemplates.Mode.CUSTOM)
        assertEquals(16, L7AudioTemplates.load(context).choice(AudioOutputRole.MEDIA))
    }
}
