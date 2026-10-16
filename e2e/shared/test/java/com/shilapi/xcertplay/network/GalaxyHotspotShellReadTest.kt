package com.shilapi.xcertplay.network

import android.util.Base64
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [HotspotWriteShadow::class])
class GalaxyHotspotShellReadTest {
    private fun encoded(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun reply(name: String, password: String, security: Int) =
        "WARNING runtime\n${GalaxyHotspotReadMain.HEADER}|OK|${encoded(name)}|${encoded(password)}|$security\n"

    @Test fun readsAllSupportedSecurityTypesAndPreservesUnicodeName() {
        for ((value, security) in listOf(0 to ManualHotspotSecurity.OPEN, 1 to ManualHotspotSecurity.WPA2,
            2 to ManualHotspotSecurity.WPA3_TRANSITION, 3 to ManualHotspotSecurity.WPA3)) {
            val password = if (value == 0) "" else "SyntheticExample9"
            val result = GalaxyHotspotShellRead.parse(reply("合成热点|A", password, value)).credentials!!
            assertEquals("合成热点|A", result.ssid)
            assertEquals(password, result.password)
            assertEquals(security, result.security)
        }
    }

    @Test fun invalidMaskedTruncatedAndUnknownRepliesCannotReplaceSavedCredentials() {
        for (output in listOf(null, "noise", reply("test", "********", 1), reply("test", "••••••••", 1),
            reply("test", "", 1), reply("test", "Synthetic9", 7), reply("test", "Synthetic9", 0),
            "${GalaxyHotspotReadMain.HEADER}|OK|%%%|YWJjZA==|1", "x".repeat(4097)))
            assertNull(GalaxyHotspotShellRead.parse(output).credentials)
        assertEquals(NativeHotspotProblem.PERMISSION,
            GalaxyHotspotShellRead.parse("${GalaxyHotspotReadMain.HEADER}|PERMISSION").problem)
    }

    @Test fun appReadFailureUsesShellOnlyAfterBothMethodsAndDiagnosticsContainNoCredentials() {
        HotspotWriteShadow.config = null
        HotspotWriteShadow.readDenied = true
        val events = mutableListOf<String>()
        val access = NativeHotspotConfiguration(RuntimeEnvironment.getApplication(), events::add)
        var reads = 0
        access.shellRead = { _, log ->
            reads++
            log("hotspot read method=shellGetSoftApConfiguration readable=true result=READY")
            GalaxyHotspotShellRead.parse(reply("SyntheticNetwork", "SyntheticExample9", 1))
        }
        try {
            assertEquals("SyntheticNetwork", access.read().credentials!!.ssid)
            assertEquals(1, reads)
            assertTrue(events[0].contains("getWifiApConfiguration"))
            assertTrue(events[1].contains("getSoftApConfiguration"))
            assertTrue(events[2].contains("shellGetSoftApConfiguration"))
            assertFalse(events.joinToString().contains("Synthetic"))
        } finally { HotspotWriteShadow.readDenied = false }
    }

    @Test fun commandQuotesApkPathAndCarriesNoNetworkDetails() {
        val command = GalaxyHotspotShellRead.command("/data/it's/base.apk")
        assertTrue(command.startsWith("CLASSPATH='/data/it'\"'\"'s/base.apk' app_process"))
        assertTrue(command.endsWith("com.shilapi.xcertplay.network.GalaxyHotspotReadMain"))
    }
}
