package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class GalaxyP2pAutoJoinTest {
    @Test fun onlyPostcreationChangesRequireStabilizationAndReconnectHasNoExtraDelay() {
        val radio = Radio()
        var windows = 0
        repeat(2) { GalaxyP2pAutoJoin.prepare(info(), { false }, {}, { radio }) { windows++ } }
        assertEquals(1, windows)
        val precreated = Radio()
        GalaxyP2pAutoJoin.prepareBeforeGroup({ false }, {}) { precreated }
        GalaxyP2pAutoJoin.prepare(info(), { false }, {}, { precreated }) { windows++ }
        assertEquals(1, windows)
    }

    @Test fun precreationWritesAreReverifiedWithoutAnotherChangeAfterCreation() {
        val radio = Radio()
        val logs = mutableListOf<String>()
        GalaxyP2pAutoJoin.prepareBeforeGroup({ false }, logs::add) { radio }
        assertTrue(logs.any { "BEFORE_GROUP_READY changed=true" in it })
        assertFalse(GalaxyP2pAutoJoin.ensure(info(), { false }, logs::add) { radio })
        assertEquals(2, radio.commands.count { "vendor_elem_add" in it })
        assertEquals(8, radio.commands.count { "vendor_elem_get" in it })
    }

    @Test fun unavailablePrecreationDefersToPostcreationButCancellationNeverDoes() {
        val logs = mutableListOf<String>()
        GalaxyP2pAutoJoin.prepareBeforeGroup({ false }, logs::add) { throw java.io.IOException("private") }
        assertTrue(logs.last().contains("DEFERRED_TO_GROUP"))
        assertFalse(logs.joinToString().contains("private"))
        assertTrue(GalaxyP2pAutoJoin.ensure(info(), { false }, {}) { Radio() })
        logs.clear()
        assertThrows(WirelessStartupException::class.java) {
            GalaxyP2pAutoJoin.prepareBeforeGroup({ true }, logs::add) { error("must not connect") }
        }
        assertFalse(logs.any { "DEFERRED_TO_GROUP" in it })
    }

    @Test fun twoGhzOrUnknownPrecreationDoesNotConnect() {
        for (frequency in listOf(null, 2437))
            GalaxyP2pAutoJoin.prepareBeforeGroup(frequency, { error("must not prepare") }, {})
    }

    private val target = GalaxyP2pAutoJoin.element
    private val other = "dd0411223344"
    private fun info(backend: WirelessHotspotBackend = WirelessHotspotBackend.WIFI_P2P, frequency: Int = 5745) =
        WirelessHotspotInfo("synthetic", "Synthetic9", Iap2WirelessSecurity.WPA_WPA2, 149, frequency,
            null, "p2p0", InetAddress.getByName("192.168.49.1"), "5 GHz", backend)
    private class Radio : GalaxyP2pAutoJoin.Shell {
        val values = mutableMapOf(2 to "", 3 to "")
        val commands = mutableListOf<String>()
        var closed = false
        var writesAccepted = true
        var rememberWrites = true
        var afterCommand: () -> Unit = {}
        override fun run(command: String): String {
            commands += command
            val args = command.split(' ')
            val frame = args[4].toInt()
            val result = if (args[3] == "vendor_elem_get") values.getValue(frame) else {
                if (writesAccepted && rememberWrites) values[frame] = values.getValue(frame) + args[5]
                if (writesAccepted) "OK" else "FAIL"
            }
            afterCommand()
            return result
        }
        override fun close() { closed = true }
    }

    @Test fun coldPreparationPreservesOtherElementsAndReconnectDoesNotAppendAgain() {
        val radio = Radio().apply { values[2] = other }
        val logs = mutableListOf<String>()
        repeat(2) { GalaxyP2pAutoJoin.ensure(info(), { false }, logs::add) { radio } }
        assertEquals(other + target, radio.values[2]); assertEquals(target, radio.values[3])
        assertEquals(2, radio.commands.count { "vendor_elem_add" in it })
        assertTrue(logs.last().contains("changed=false"))
        assertTrue(logs.any { "stage=BEFORE frame=2 targetPresent=false" in it })
        assertTrue(logs.any { "stage=AFTER frame=2 targetPresent=true" in it })
        assertTrue(logs.any { "evidence=CONFIG_READBACK airBroadcast=UNVERIFIED" in it })
        assertTrue(logs.all { "atNs=" in it && "elapsedMs=" in it })
        assertFalse(logs.joinToString().contains("Synthetic"))
    }

    @Test fun duplicateConflictingAndMalformedElementsFailBeforeAnyWrite() {
        for (bad in listOf(target + target, "dd0800a0400000010021", "FAIL", "dd09ff", "f", "zz")) {
            val radio = Radio().apply { values[3] = bad }
            val error = assertThrows(WirelessStartupException::class.java) {
                GalaxyP2pAutoJoin.ensure(info(), { false }, {}) { radio }
            }
            assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, error.reason)
            assertFalse(radio.commands.any { "vendor_elem_add" in it }); assertTrue(radio.closed)
        }
    }

    @Test fun acceptedWriteWithoutReadbackIsNotSuccess() {
        val radio = Radio().apply { rememberWrites = false }
        val logs = mutableListOf<String>()
        assertThrows(WirelessStartupException::class.java) {
            GalaxyP2pAutoJoin.ensure(info(), { false }, logs::add) { radio }
        }
        assertTrue(logs.last().contains("READBACK_MISMATCH")); assertTrue(radio.closed)
        assertFalse(logs.any { "stage=VERIFIED" in it })
    }

    @Test fun cancellationBetweenFramesStopsFurtherWritesAndRetryCompletesTheMissingFrame() {
        val radio = Radio()
        var cancelled = false
        radio.afterCommand = { if (radio.commands.last().contains("vendor_elem_add")) cancelled = true }
        assertThrows(WirelessStartupException::class.java) {
            GalaxyP2pAutoJoin.ensure(info(), { cancelled }, {}) { radio }
        }
        assertEquals(1, radio.commands.count { "vendor_elem_add" in it }); assertTrue(radio.closed)
        cancelled = false; radio.afterCommand = {}
        GalaxyP2pAutoJoin.ensure(info(), { false }, {}) { radio }
        assertEquals(2, radio.commands.count { "vendor_elem_add" in it })
    }

    @Test fun otherNetworksAndTwoGhzNeverOpenTheMaintenancePort() {
        for (value in listOf(info(WirelessHotspotBackend.MANUAL_HOTSPOT), info(frequency = 2437)))
            GalaxyP2pAutoJoin.ensure(value, { false }, {}) { error("unexpected connection") }
    }

    @Test fun serviceFailureAndRejectedWriteHaveBoundedSafeDiagnostics() {
        val logs = mutableListOf<String>()
        assertThrows(WirelessStartupException::class.java) {
            GalaxyP2pAutoJoin.ensure(info(), { false }, logs::add) { throw java.io.IOException("private-terminal-content") }
        }
        assertFalse(logs.joinToString().contains("private-terminal-content"))
        val radio = Radio().apply { writesAccepted = false }
        assertThrows(WirelessStartupException::class.java) {
            GalaxyP2pAutoJoin.ensure(info(), { false }, logs::add) { radio }
        }
        assertTrue(logs.last().contains("WRITE_REJECTED")); assertTrue(radio.closed)
    }
}
