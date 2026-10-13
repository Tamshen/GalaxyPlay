package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], qualifiers = "zh-rCN")
class GalaxyCarPlayAppearanceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)
    @Before fun clean() {
        prefs.edit().clear().commit()
        GalaxyCarPlayAppearance.resetIcon(app)
        File(app.filesDir, "configurations").deleteRecursively()
    }
    private fun png(width: Int = 48, height: Int = width): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
    }
    private fun config(context: Context = app) = AirPlayConfig("GalaxyPlay", "test", "00:11:22:33:44:55", "950.7.1",
        AirPlayDisplayConfig(1440, 1920), oemLabel = AirPlayPersistence.loadOemLabel(context),
        icons = listOf(GalaxyCarPlayAppearance.icon(context)))
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun freshAndLegacyDefaultsAdvertiseReturnNameInBothLanguages() {
        assertEquals("回到车机", AirPlayPersistence.loadOemLabel(app))
        for (old in listOf("", "BYD", "byd", " BYD ")) {
            prefs.edit().putString("oem_label", old).commit()
            assertEquals("回到车机", AirPlayPersistence.loadOemLabel(app))
        }
        val english = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        })
        assertEquals("Back to Car", AirPlayPersistence.loadOemLabel(english))
        prefs.edit().putString("oem_label", "我的银河").commit()
        assertEquals("我的银河", AirPlayPersistence.loadOemLabel(english))
    }

    @Test fun explicitNameIncludingBydIsKeptAndInvalidSaveCannotOverwriteIt() {
        AirPlayPersistence.saveOemLabel(app, " BYD ")
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(app))
        for (invalid in listOf(" ", "x".repeat(33), "车\n机", "车\u0000机")) {
            assertTrue(runCatching { AirPlayPersistence.saveOemLabel(app, invalid) }.isFailure)
            assertEquals("BYD", AirPlayPersistence.loadOemLabel(app))
        }
        AirPlayPersistence.saveOemLabel(app, "🚗".repeat(32))
        assertEquals("🚗".repeat(32), AirPlayPersistence.loadOemLabel(app))
    }

    @Test fun protocolPayloadUsesSameDefaultAndCustomImagesAndCurrentSessionStaysFrozen() {
        val current = config()
        assertEquals(256, current.icons.single().widthPixels)
        assertNotNull(GalaxyCarPlayAppearance.decode(current.icons.single().data))
        val custom = png()
        AirPlayPersistence.saveCustomAirPlayIcon(app, custom)
        AirPlayPersistence.saveOemLabel(app, "我的车机")
        val next = config()
        val info = AirPlayInfoPlist.build(next)
        assertEquals("我的车机", info["oemIconLabel"])
        assertEquals(true, info["oemIconVisible"])
        val entry = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertArrayEquals(custom, entry["imageData"] as ByteArray)
        assertEquals(48, entry["widthPixels"])
        assertEquals("回到车机", current.oemLabel)
        assertEquals(256, current.icons.single().widthPixels)
    }

    @Test fun invalidImportPreservesImageAndAtomicBackupRecoversOldFile() {
        val custom = png()
        AirPlayPersistence.saveCustomAirPlayIcon(app, custom)
        for (invalid in listOf(byteArrayOf(), byteArrayOf(1, 2), png(48, 32), png(1025), ByteArray(5 * 1024 * 1024 + 1))) {
            assertTrue(runCatching { AirPlayPersistence.saveCustomAirPlayIcon(app, invalid) }.isFailure)
            assertArrayEquals(custom, GalaxyCarPlayAppearance.icon(app).data)
        }
        File(app.filesDir, "airplay-icon.png.bak").writeBytes(custom)
        File(app.filesDir, "airplay-icon.png").writeBytes(byteArrayOf(1))
        assertArrayEquals(custom, GalaxyCarPlayAppearance.icon(app).data)
        assertFalse(File(app.filesDir, "airplay-icon.png.bak").exists())
    }

    @Test fun templatesSaveAndResetKeepIndependentAppearanceAndEvidenceOmitsImage() {
        val custom = png()
        AirPlayPersistence.saveOemLabel(app, "银河入口")
        AirPlayPersistence.saveCustomAirPlayIcon(app, custom)
        val profiles = GalaxyProfiles(app)
        for (model in listOf("l6", "l7", "custom")) {
            val selected = profiles.applyTemplate(model)
            val draft = GalaxyConfigurationContext(app, selected, editable = true)
            AirPlayPersistence.saveFps(draft, 60)
            profiles.save(selected.copy(configuration = draft.configuration()))
            profiles.resetCurrent(profiles.active())
            assertEquals("银河入口", AirPlayPersistence.loadOemLabel(app))
            assertArrayEquals(custom, GalaxyCarPlayAppearance.icon(app).data)
            assertFalse(profiles.active().configuration.preferences.getValue("xcertplay_airplay").containsKey("oem_label"))
        }
        val evidence = GalaxyApplicationPreferences.capture(app).getJSONObject("xcertplay_airplay")
        assertEquals("银河入口", evidence.getJSONObject("oem_label").getString("value"))
        assertEquals("CUSTOM", evidence.getJSONObject("galaxy_oem_icon").getString("value"))
        GalaxyApplicationPreferences.read(GalaxyApplicationPreferences.capture(app))
        assertFalse(evidence.toString().contains("airplay-icon.png"))
        GalaxyCarPlayAppearance.resetLabel(app)
        assertArrayEquals(custom, GalaxyCarPlayAppearance.icon(app).data)
        AirPlayPersistence.saveOemLabel(app, "保留名称")
        GalaxyCarPlayAppearance.resetIcon(app)
        assertEquals("保留名称", AirPlayPersistence.loadOemLabel(app))
    }

    @Test fun applicationRowsConfirmNameButCancelAndImageResetDoNotChangeOtherSettings() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val parent = LinearLayout(activity)
        var picked = 0
        GalaxyCarPlayAppearancePage.add(activity, parent) { picked++ }
        val rows = descendants(parent).filterIsInstance<L7SettingRow>()
        rows.single { it.titleView.text == activity.getString(R.string.galaxy_carplay_return_import) }.performClick()
        assertEquals(1, picked)
        val name = rows.single { it.titleView.text == activity.getString(R.string.galaxy_carplay_return_name) }
        name.performClick(); shadowOf(Looper.getMainLooper()).idle()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("取消的名字")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("回到车机", AirPlayPersistence.loadOemLabel(app))
        name.performClick(); shadowOf(Looper.getMainLooper()).idle()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(""); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        input.setText("自定义入口"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("自定义入口", name.valueView.text.toString())
        name.performClick(); shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("回到车机", name.valueView.text.toString())
        activity.finish()
    }
}
