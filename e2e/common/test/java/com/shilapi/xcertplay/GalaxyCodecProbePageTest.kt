package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyCodecProbePageTest {
    @Test fun openingIsReadOnlySoftwareIsExplicitAndBackgroundDoesNotRestart() {
        val activity = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        var creates = 0
        val controller = GalaxyCodecProbeController(activity, { false },
            { listOf(CodecProbeDecoder("software", false, true)) },
            { _, _, _, _, _, _, _, _ -> creates++; error("页面不得自动测试") })
        val parent = LinearLayout(activity)
        val page = GalaxyCodecProbePage(activity, parent, controller, { false }, {})
        fun row(name: String) = ReflectionHelpers.getField<L7SettingRow>(page, name)
        assertFalse(controller.allowSoftware)
        assertFalse(row("start").isEnabled)
        assertFalse(row("all").isEnabled)
        assertFalse(row("stop").isEnabled)
        assertEquals(View.GONE, row("visible").visibility)
        assertEquals(View.GONE, row("abnormal").visibility)
        assertFalse(controller.busy)
        controller.method = CodecProbeMethod.OEM_DMSDP_BUFFER
        page.update()
        val cover = ReflectionHelpers.getField<View>(page, "bufferPreview")
        val preview = ReflectionHelpers.getField<View>(page, "preview")
        assertEquals(View.VISIBLE, cover.visibility)
        assertEquals(View.VISIBLE, preview.visibility)
        controller.method = CodecProbeMethod.JAVA_NAME
        page.update()
        assertEquals(View.GONE, cover.visibility)
        page.background(); page.resume(); page.update()
        assertEquals(0, creates)
        assertEquals("settings-debug", L7Routes.back("settings-debug-codec"))
        page.close(); controller.close()
    }
}
