package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class L7AgreementTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun noConsentByDefaultAndAcceptanceIsBoundToTheBundledText() {
        val context = activity()
        assertFalse(L7Agreement.accepted(context))
        assertTrue(L7Agreement.accept(context))
        assertTrue(L7Agreement.accepted(context))
        assertEquals(L7Agreement.digest(context), context.getSharedPreferences("l7_agreement", Context.MODE_PRIVATE)
            .getString("accepted_digest", null))
        // 模拟旧正文的同意记录；更新正文不能继承旧同意。
        context.getSharedPreferences("l7_agreement", Context.MODE_PRIVATE).edit()
            .putString("accepted_digest", "previous-agreement").commit()
        assertFalse(L7Agreement.accepted(context))
    }

    @Test fun revocationSurvivesAnotherContextAndPreservesConnectionPreferences() {
        val context = activity()
        AirPlayPersistence.saveWirelessEnabled(context, false)
        DiPlayPreferences.saveAutoConnect(context, true)
        assertTrue(L7Agreement.accept(context))
        assertTrue(L7Agreement.revoke(context))
        assertFalse(L7Agreement.accepted(context.applicationContext))
        assertTrue(context.getSharedPreferences("l7_agreement", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
        assertTrue(DiPlayPreferences.autoConnect(context))
        assertTrue(L7Agreement.accept(context))
        assertTrue(L7Agreement.accepted(context))
    }

    @Test fun bundledAgreementRetainsEverySectionAndHasNoTemplateFields() {
        val content = L7Agreement.document(activity())
        assertEquals(11, Regex("(?m)^## ").findAll(content).count())
        assertTrue(content.contains("不对许可证授予的运行、复制、修改及再分发等权利增加限制"))
        assertTrue(content.contains("协议版本：${L7Agreement.VERSION}"))
        assertFalse(content.contains("[软件名称]"))
        assertFalse(content.contains("[姓名或网名]"))
    }

    private fun layout(panel: L7AgreementPanel) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 900, 1200)
        shadowOf(Looper.getMainLooper()).idle()
        panel.setReadingActive(true)
    }

    @Test fun reachingTheEndRequiresAnExplicitCheckBeforeAccepting() {
        val context = activity()
        var accepted = 0
        val panel = L7AgreementPanel(context, L7Agreement.document(context), false, { accepted++ }, {}, {})
        layout(panel)
        panel.setReady(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(panel.check.isEnabled)
        assertFalse(panel.accept.isEnabled)
        panel.accept.performClick()
        assertEquals(0, accepted)
        panel.scroll.scrollTo(0, panel.scroll.getChildAt(0).height)
        assertFalse(panel.check.isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        assertFalse(panel.check.isEnabled)
        panel.accept.performClick()
        assertEquals(0, accepted)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(panel.check.isEnabled)
        assertFalse(panel.check.isChecked)
        assertFalse(panel.accept.isEnabled)
        panel.check.performClick()
        assertTrue(panel.accept.isEnabled)
        panel.accept.performClick()
        assertEquals(1, accepted)
        panel.check.performClick()
        assertFalse(panel.accept.isEnabled)
    }

    @Test fun pendingShutdownPreventsAcceptingEvenAfterReadingAndChecking() {
        val context = activity()
        val panel = L7AgreementPanel(context, L7Agreement.document(context), false, {}, {}, {})
        layout(panel)
        panel.scroll.scrollTo(0, panel.scroll.getChildAt(0).height)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(panel.check.isEnabled)
        panel.setReady(true)
        assertFalse(panel.check.isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        panel.check.performClick()
        assertTrue(panel.accept.isEnabled)
        panel.setReady(false)
        assertFalse(panel.check.isEnabled)
        assertFalse(panel.accept.isEnabled)
    }

    @Test fun unfinishedCountdownRestartsAfterReturningFromBackground() {
        val context = activity()
        val panel = L7AgreementPanel(context, L7Agreement.document(context), false, {}, {}, {})
        layout(panel)
        panel.setReady(true)
        panel.scroll.scrollTo(0, panel.scroll.getChildAt(0).height)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        panel.setReadingActive(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(panel.check.isEnabled)
        panel.setReadingActive(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        assertFalse(panel.check.isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(panel.check.isEnabled)
        assertFalse(panel.check.isChecked)
    }

    @Test fun repeatedLayoutAndPaletteRefreshDoNotRestartTheCountdown() {
        val context = activity()
        val panel = L7AgreementPanel(context, L7Agreement.document(context), false, {}, {}, {})
        layout(panel)
        panel.setReady(true)
        panel.scroll.scrollTo(0, panel.scroll.getChildAt(0).height)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        L7Ui.refresh(panel)
        layout(panel)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(panel.check.isEnabled)
        assertFalse(panel.accept.isEnabled)
    }

    @Test fun readingFromSettingsDoesNotExposeAnotherAcceptanceCheckbox() {
        val context = activity()
        val panel = L7AgreementPanel(context, L7Agreement.document(context), true, {}, {}, {})
        assertEquals(View.GONE, panel.check.visibility)
        assertNull(panel.accept.parent)
    }
}
