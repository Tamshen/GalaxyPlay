package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyConfigurationSharingTest {
    private lateinit var activity: ComponentActivity
    private lateinit var sharing: GalaxyConfigurationSharing
    private lateinit var original: GalaxyProfile
    private var changes = 0
    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        File(activity.filesDir, "configurations").deleteRecursively()
        original = GalaxyProfiles(activity).applyTemplate("l7_no_headrest")
        sharing = GalaxyConfigurationSharing(activity, { original }, { original }) { changes++ }
    }
    @After fun close() { sharing.close(); activity.finish() }
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun descendants() = views(dialog().window!!.decorView)
    private fun click(label: Int) = descendants().filterIsInstance<Button>()
        .single { it.text == activity.getString(label) }.performClick()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(condition())
    }
    @Test fun copiedCodeIsPreviewedBeforeApplyAndClosingThePageRejectsLateConfirmation() {
        val code = GalaxyConfigurationShare.from(original, "配置01").code()
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("profile", code))
        sharing.paste()
        click(R.string.config_share_paste)
        assertEquals(code, descendants().filterIsInstance<EditText>().single().text.toString())
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { descendants().filterIsInstance<Button>().any { it.text == activity.getString(R.string.config_share_apply) } }
        assertEquals(original, GalaxyProfiles(activity).active())
        val preview = dialog()
        sharing.close()
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, changes)
        assertEquals(original, GalaxyProfiles(activity).active())
    }
    @Test fun sharingGeneratesACopyableCodeWithoutSavingTheVehicleOrLaunchingAnImporter() {
        sharing.share()
        val name = descendants().filterIsInstance<EditText>().single()
        assertTrue(name.text.isNotEmpty())
        name.setText("配置01")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { descendants().filterIsInstance<Button>().any { it.text == activity.getString(R.string.config_share_copy) } }
        click(R.string.config_share_copy)
        val code = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        val shared = GalaxyConfigurationShare.parse(code)
        assertEquals("配置01", shared.name)
        assertEquals(original.configuration, shared.configuration)
        assertEquals(original, GalaxyProfiles(activity).active())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
