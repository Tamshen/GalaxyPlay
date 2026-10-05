package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7AudioModelDetectionTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private fun click(which: Int) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun confirmedIdentifiersMatchCompleteTokensAcrossBuildFields() {
        assertEquals(L7AudioTemplates.Model.L7, L7AudioModelDetector.detect("g636", "unknown", "unknown"))
        assertEquals(L7AudioTemplates.Model.L6, L7AudioModelDetector.detect("unknown", "G733_product", "unknown"))
        assertEquals(L7AudioTemplates.Model.L7, L7AudioModelDetector.detect("unknown", "unknown", "Galaxy (g636)"))
        assertEquals(L7AudioTemplates.Model.L6, L7AudioModelDetector.detect("g733", "g733-user", "g733"))
    }
    @Test fun unknownPartialAndConflictingIdentitiesCannotGuessModel() {
        for (value in listOf("", "emulator_arm64", "g6360", "ag733", "g733g636"))
            assertNull(L7AudioModelDetector.detect(value, "unknown", "unknown"))
        assertNull(L7AudioModelDetector.detect("g636", "g733", "unknown"))
        assertNull(L7AudioModelDetector.detect("g636/g733", "unknown", "unknown"))
    }
    @Test fun recognitionOnlyProposesUntilUserConfirmsAndContinuesOnce() {
        val context = context()
        val gate = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }
        var continuations = 0
        try {
            assertTrue(gate.ensure { continuations++ })
            assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
            assertEquals(0, continuations)
            click(AlertDialog.BUTTON_POSITIVE)
            assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(context))
            assertEquals(1, continuations)
            assertFalse(gate.ensure { continuations++ })
            assertFalse(L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }.ensure {})
        } finally { gate.close() }
    }
    @Test fun decliningRetainsCustomL7AndDoesNotPromptAgain() {
        val context = context()
        L7AudioTemplates.saveCustom(context, L7AudioTemplates.load(context).withChoice(
            com.shilapi.xcertplay.media.AudioOutputRole.MEDIA, 20))
        val gate = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }
        var continued = false
        try {
            assertTrue(gate.ensure { continued = true })
            click(AlertDialog.BUTTON_NEGATIVE)
            assertTrue(continued)
            assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
            assertEquals(20, L7AudioTemplates.load(context).choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA))
            assertFalse(gate.ensure {})
        } finally { gate.close() }
    }
    @Test fun duplicateResumeKeepsOneDialogAndReplacesPendingContinuation() {
        val context = context()
        val gate = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L7 }
        var first = 0; var latest = 0
        try {
            assertTrue(gate.ensure { first++ })
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(gate.ensure { latest++ })
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
            click(AlertDialog.BUTTON_POSITIVE)
            assertEquals(0, first)
            assertEquals(1, latest)
        } finally { gate.close() }
    }
    @Test fun closeInvalidatesQueuedDecisionAndNewOwnerCanConfirmAgain() {
        val context = context()
        val gate = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }
        var calls = 0
        gate.ensure { calls++ }
        val old = ShadowAlertDialog.getLatestAlertDialog()
        gate.close()
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(context))
        val next = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }
        try { assertTrue(next.ensure {}) } finally { next.close() }
    }
    @Test fun unknownIdentityDoesNotPromptOrChangeManualChoice() {
        val context = context()
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        val gate = L7AudioModelConfirmation(context) { null }
        try {
            assertFalse(gate.ensure { fail("未知车型不能调用待确认回调") })
            assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(context))
        } finally { gate.close() }
    }
    @Test fun changedRecognizedIdentityRequiresAnotherConfirmation() {
        val context = context()
        L7AudioModelConfirmation.markReviewed(context, L7AudioTemplates.Model.L7)
        val gate = L7AudioModelConfirmation(context) { L7AudioTemplates.Model.L6 }
        try { assertTrue(gate.ensure {}) } finally { gate.close() }
    }
}
