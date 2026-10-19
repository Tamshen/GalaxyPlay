package com.shilapi.xcertplay.transport

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyUsbGadgetPlatformTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(GalaxyUsbGadgetPlatform.JOURNAL, Context.MODE_PRIVATE)
    private val rules = """
        on property:sys.usb.config=iap,ncm && property:sys.usb.configfs=1
            symlink /config/usb_gadget/g1/functions/iap.0 /config/usb_gadget/g1/configs/b.1/f1
            symlink /config/usb_gadget/g1/functions/ncm.0 /config/usb_gadget/g1/configs/b.1/f2
            write /config/usb_gadget/g1/UDC controller
    """.trimIndent()
    @Before fun reset() { prefs.edit().clear().commit() }

    private class Shell(var rule: String) {
        var config = "adb"; var carplay = ""; var controller = "a600000.dwc3"; var state = "not attached"
        val calls = mutableListOf<Pair<String, Boolean>>()
        fun run(command: String, recovery: Boolean): String {
            calls += command to recovery
            return when {
                command == "id -u" -> "0"
                command == "getprop sys.usb.configfs" -> "1"
                command == "getprop sys.usb.config" || command == "getprop sys.usb.state" -> config
                command == "getprop carplay.config.usb" -> carplay
                command == "getprop sys.usb.controller" -> controller
                command.startsWith("cat /sys/class/udc/") -> state
                command.startsWith("for f in ") -> rule
                command.startsWith("if [ -") -> "PRESENT"
                command.startsWith("setprop sys.usb.config ") -> { config = command.substringAfterLast(' ').trim('\''); "" }
                command.startsWith("setprop carplay.config.usb ") -> { carplay = command.substringAfterLast(' ').trim('\''); "" }
                else -> error("unexpected command")
            }
        }
    }
    private fun platform(shell: Shell) = GalaxyUsbGadgetPlatform(context, { false }, {}, shell::run, { true })

    @Test fun markersOrCommentedRecipesNeverCountAsExecutableGadgetRules() {
        assertTrue(GalaxyUsbGadgetRules.supports(rules))
        assertFalse(GalaxyUsbGadgetRules.supports(rules.replace("configfs=1", "vendor.unconfirmed=1")))
        assertFalse(GalaxyUsbGadgetRules.supports(rules.replace("iap,ncm &&", "iap,ncm,adb &&")))
        assertFalse(GalaxyUsbGadgetRules.supports(rules.replace("write /config", "# write /config")))
        assertFalse(GalaxyUsbGadgetRules.supports(rules.replace("symlink /config/usb_gadget/g1/functions/ncm", "# symlink /config/usb_gadget/g1/functions/ncm")))
        assertFalse(GalaxyUsbGadgetRules.supports(rules.replace("    write", "on property:other=1\n    write")))
    }
    @Test fun hostilePropertyValuesCannotEnterCommands() {
        for (value in listOf("adb;id", "adb'", "\$(id)", "adb\n", "../controller", ""))
            assertFalse(value, GalaxyUsbGadgetRules.safeConfig(value))
        assertFalse(GalaxyUsbGadgetRules.safeController("../../other"))
        assertFalse(GalaxyUsbGadgetRules.safeController("a;id"))
        assertTrue(GalaxyUsbGadgetRules.safeConfig("diag,adb"))
    }
    @Test fun prepareIsReadOnlyAndMissingRuleDoesNotCheckpoint() {
        val shell = Shell("ncm. iap."); val platform = platform(shell)
        try {
            assertEquals(GalaxyUsbVerificationFailure.Reason.GADGET_RULE_MISSING,
                assertThrows(GalaxyUsbVerificationFailure::class.java) { platform.prepare() }.reason)
            assertFalse(prefs.contains("original"))
            assertTrue(shell.calls.none { it.first.startsWith("setprop") })
        } finally { platform.close() }
    }
    @Test fun recoveryRestoresExactOriginalValuesWithIndependentCancellationBudget() {
        val shell = Shell(rules).apply { config = "mtp,adb"; carplay = "0" }
        val platform = platform(shell)
        try {
            platform.prepare(); platform.checkpoint(); platform.activate()
            assertEquals("iap,ncm", shell.config)
            assertTrue(prefs.contains("original"))
            platform.restore()
            assertEquals("mtp,adb", shell.config); assertEquals("0", shell.carplay)
            assertFalse(prefs.contains("original"))
            assertTrue(shell.calls.any { it.second && it.first.startsWith("setprop") })
        } finally { platform.close() }
    }
    @Test fun changedOwnershipIsNotOverwrittenAndJournalSurvives() {
        val shell = Shell(rules); val platform = platform(shell)
        try {
            platform.prepare(); platform.checkpoint(); platform.activate()
            shell.config = "rndis,adb"
            val before = shell.calls.size
            assertThrows(GalaxyUsbVerificationFailure::class.java) { platform.restore() }
            assertEquals("rndis,adb", shell.config)
            assertTrue(prefs.contains("original"))
            assertTrue(shell.calls.drop(before).none { it.first.startsWith("setprop") })
        } finally { platform.close() }
    }
    @Test fun pendingRecoveryRunsBeforeNewPreflightAndConcurrentAttemptIsRefused() {
        val shell = Shell(rules); val first = platform(shell); val second = platform(shell)
        try {
            first.prepare(); first.checkpoint(); first.activate()
            assertThrows(GalaxyUsbVerificationFailure::class.java) { second.prepare() }
        } finally { second.close(); first.close() }
        val next = platform(shell)
        try {
            next.prepare()
            assertEquals("adb", shell.config); assertFalse(prefs.contains("original"))
        } finally { next.close() }
    }
    @Test fun configuredReadbackRequiresBothAppliedConfigAndActualUdc() {
        val shell = Shell(rules); val platform = platform(shell)
        try {
            platform.prepare(); platform.checkpoint(); platform.activate()
            shell.state = "configured"
            platform.awaitConfigured { false }
            assertThrows(GalaxyUsbVerificationFailure::class.java) { platform.restore() }
            assertTrue(prefs.contains("original"))
            shell.state = "not attached"
            platform.restore()
            assertFalse(prefs.contains("original"))
        } finally { platform.close() }
    }
    @Test fun interruptedOwnerCanStillRestoreAndKeepsItsInterruptFlag() {
        val shell = Shell(rules); val platform = platform(shell)
        try {
            platform.prepare(); platform.checkpoint(); platform.activate()
            Thread.currentThread().interrupt()
            platform.restore()
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(prefs.contains("original"))
        } finally { Thread.interrupted(); platform.close() }
    }

    @Test fun rootNodeVisibilityDoesNotReplaceApplicationReadWriteAccess() {
        val shell = Shell(rules)
        val platform = GalaxyUsbGadgetPlatform(context, { false }, {}, shell::run, { false })
        try {
            assertEquals(GalaxyUsbVerificationFailure.Reason.IPOD_NODE_UNAVAILABLE,
                assertThrows(GalaxyUsbVerificationFailure::class.java) { platform.prepare() }.reason)
            assertFalse(prefs.contains("original"))
            assertTrue(shell.calls.none { it.first.startsWith("setprop") })
        } finally { platform.close() }
    }

    @Test fun activeGadgetCannotBeTakenOver() {
        val shell = Shell(rules).apply { state = "configured" }; val platform = platform(shell)
        try {
            assertEquals(GalaxyUsbVerificationFailure.Reason.CONTROLLER_UNAVAILABLE,
                assertThrows(GalaxyUsbVerificationFailure::class.java) { platform.prepare() }.reason)
            assertTrue(shell.calls.none { it.first.startsWith("setprop") })
        } finally { platform.close() }
    }
}
