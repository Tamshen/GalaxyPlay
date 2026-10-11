package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyUpdateLayoutTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun verify(fontScale: Float) {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = host.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
        val context = activity.createConfigurationContext(configuration)
        val content = L7Dialogs.Content().apply {
            title = "检查更新"; message = "版本说明"; fixedHeight = true
            actions[AlertDialog.BUTTON_NEGATIVE] = L7Dialogs.Action("关闭", null)
            actions[AlertDialog.BUTTON_POSITIVE] = L7Dialogs.Action("下载更新", null)
        }
        val dialog = L7ModalDialog(context, content)
        try {
            dialog.show(); shadowOf(android.os.Looper.getMainLooper()).idle()
            val decor = dialog.window!!.decorView
            val width = dialog.window!!.attributes.width
            val height = dialog.window!!.attributes.height
            fun layout() {
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
            }
            layout()
            val scroll = views(decor).filterIsInstance<ScrollView>().single { candidate ->
                views(candidate).any { it is android.widget.TextView && it.text.toString() == "版本说明" }
            }
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)!!
            val initialHeight = decor.height
            val initialButton = IntArray(2).also { button.getLocationInWindow(it) }
            val message = views(scroll).filterIsInstance<android.widget.TextView>().single()
            message.text = (1..400).joinToString("\n") { "更新说明 $it：修复与优化" }
            layout()
            assertEquals(initialHeight, decor.height)
            assertTrue("正文应存在滚动空间", scroll.getChildAt(0).height > scroll.height)
            val before = IntArray(2).also { button.getLocationInWindow(it) }
            assertArrayEquals(initialButton, before)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue(scroll.scrollY > 0)
            assertArrayEquals(before, IntArray(2).also { button.getLocationInWindow(it) })
            assertTrue("底部操作必须位于窗口内", before[1] + button.height <= height)
            assertTrue(scroll.height > 0)
        } finally { dialog.dismiss(); host.pause().stop().destroy() }
    }
    @Test @Config(qualifiers = "w600dp-h800dp-port") fun longNotesScrollInPortrait() = verify(1f)
    @Test @Config(qualifiers = "w800dp-h600dp-land") fun longNotesScrollInLandscape() = verify(1f)
    @Test @Config(qualifiers = "w600dp-h800dp-port") fun largeTextKeepsPortraitButtonsFixed() = verify(1.5f)
    @Test @Config(qualifiers = "w800dp-h600dp-land") fun largeTextKeepsLandscapeButtonsFixed() = verify(1.5f)
}
