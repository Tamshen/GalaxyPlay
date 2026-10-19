package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class GalaxyP2pStartupEvidenceTest {
    private class Radio : GalaxyP2pAutoJoin.Shell {
        val commands = mutableListOf<String>()
        var closed = false
        var afterQuery: () -> Unit = {}
        override fun run(command: String): String {
            commands += command
            afterQuery()
            return when {
                "vendor_elem_get" in command -> GalaxyP2pAutoJoin.element
                "all_sta" in command -> "02:00:00:00:00:01\nflags=[AUTH][ASSOC][AUTHORIZED]\nprivate=secret"
                else -> "192.0.2.123 lladdr 02:00:00:00:00:01 REACHABLE"
            }
        }
        override fun close() { closed = true }
    }

    @Test fun reportsReadbackCountsWithoutIdentitiesOrClaimingDhcpAndBoundsQueries() {
        val radio = Radio()
        var opens = 0
        val probe = GalaxyP2pStartupEvidence({ false }, { _, _ -> opens++; radio })
        val first = probe.sample()
        assertTrue(first.contains("frame2=PRESENT frame3=PRESENT"))
        assertTrue(first.contains("stations=1 authorized=1"))
        assertTrue(first.contains("neighborsV4=1 reachable=1"))
        assertTrue(first.contains("airBroadcast=UNVERIFIED dhcpLease=NOT_OBSERVED"))
        assertFalse(first.contains("192.0.2") || first.contains("02:00") || first.contains("secret"))
        repeat(3) { probe.sample() }
        assertTrue(probe.sample().contains("sampling=limit cached=true"))
        assertEquals(4, opens)
        assertEquals(16, radio.commands.size)
        assertTrue(radio.commands.all { "vendor_elem_get" in it || "all_sta" in it || it == "ip -4 neigh show dev p2p0" })
        assertTrue(radio.closed)
        probe.close(); probe.sample()
        assertEquals(4, opens)
    }

    @Test fun unavailableMalformedAndEmptyResultsRemainDistinct() {
        for (value in listOf(null, "FAIL", "UNKNOWN COMMAND", "permission denied", "unexpected firmware output")) {
            assertTrue(GalaxyP2pStartupEvidence.stations(value).contains("stations=UNAVAILABLE"))
            assertTrue(GalaxyP2pStartupEvidence.neighbors(value).contains("neighborsV4=UNAVAILABLE"))
        }
        assertTrue(GalaxyP2pStartupEvidence.stations("").contains("stations=0 authorized=0"))
        assertTrue(GalaxyP2pStartupEvidence.neighbors("").contains("neighborsV4=0 reachable=0"))
        assertTrue(GalaxyP2pStartupEvidence.neighbors("192.0.2.1 FAILED").contains("unresolved=1"))
        assertTrue(GalaxyP2pStartupEvidence.neighbors("unexpected REACHABLE").contains("UNAVAILABLE"))
        assertTrue(GalaxyP2pStartupEvidence.stations("02:00:00:00:00:01\nfirmware=unknown").contains("authorized=UNKNOWN"))
        assertTrue(GalaxyP2pStartupEvidence.elements("frame2", "").endsWith("ABSENT"))
        assertTrue(GalaxyP2pStartupEvidence.elements("frame2", "FAIL").endsWith("UNAVAILABLE"))
    }

    @Test fun cancellationDuringQuerySuppressesPartialEvidenceAndLaterCommands() {
        var cancelled = false
        val radio = Radio().apply { afterQuery = { cancelled = true } }
        val probe = GalaxyP2pStartupEvidence({ cancelled }, { _, _ -> radio })
        assertEquals("P2P_LINK_EVIDENCE sampling=stopped", probe.sample())
        assertEquals(1, radio.commands.size)
        assertTrue(radio.closed)
    }

    @Test fun transportFailureDoesNotPropagateOrLogExceptionBody() {
        val probe = GalaxyP2pStartupEvidence({ false }, { _, _ -> throw IllegalStateException("secret") })
        val text = probe.sample()
        assertTrue(text.contains("readback=UNAVAILABLE"))
        assertFalse(text.contains("secret"))
    }
}
