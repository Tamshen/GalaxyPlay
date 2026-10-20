package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class GalaxyScanGuardSessionTest {
    private class Rig {
        var scanner = GalaxyScanState.ENABLED
        var pending = false
        var durable = true
        var loseReply = false
        var deny = false
        val writes = mutableListOf<GalaxyScanAction>()
        val logs = mutableListOf<String>()
        fun session() = GalaxyScanGuardSession({ action ->
            if (action != GalaxyScanAction.INSPECT) {
                assertTrue("改变扫描前必须持久化恢复责任", pending)
                writes += action
                if (!deny) scanner = if (action == GalaxyScanAction.ENABLE) GalaxyScanState.ENABLED else GalaxyScanState.DISABLED
            }
            when {
                action == GalaxyScanAction.INSPECT -> scanner
                loseReply -> GalaxyScanState.UNKNOWN
                deny -> GalaxyScanState.DENIED
                else -> scanner
            }
        }, { pending }, { if (durable) pending = it; durable }, logs::add)
    }

    @Test fun connectedStopsScanningAndDisconnectRestoresExactlyOnce() {
        val rig = Rig(); val session = rig.session()
        assertTrue(rig.writes.isEmpty())
        session.start(); session.start(); session.check()
        assertEquals(GalaxyScanState.DISABLED, rig.scanner)
        assertEquals(listOf(GalaxyScanAction.DISABLE), rig.writes)
        assertTrue(session.stop()); assertTrue(session.stop())
        assertEquals(listOf(GalaxyScanAction.DISABLE, GalaxyScanAction.ENABLE), rig.writes)
        assertFalse(rig.pending)
    }

    @Test fun originalDisabledAndUnknownStatesAreNeverOverwritten() {
        for (before in listOf(GalaxyScanState.DISABLED, GalaxyScanState.UNKNOWN, GalaxyScanState.UNSUPPORTED)) {
            val rig = Rig(); rig.scanner = before
            val session = rig.session(); session.start(); session.stop()
            assertTrue(rig.writes.isEmpty()); assertFalse(rig.pending)
        }
    }

    @Test fun failedDurableJournalPreventsMutation() {
        val rig = Rig(); rig.durable = false
        val session = rig.session(); session.start(); session.stop()
        assertTrue(rig.writes.isEmpty()); assertFalse(rig.pending)
    }

    @Test fun lostReplyAndProcessRestartStillRestoreTheKnownOriginalState() {
        val rig = Rig(); rig.loseReply = true
        rig.session().start()
        assertTrue(rig.pending); assertEquals(GalaxyScanState.DISABLED, rig.scanner)
        rig.loseReply = false
        assertTrue(rig.session().recover())
        assertEquals(GalaxyScanState.ENABLED, rig.scanner); assertFalse(rig.pending)
    }

    @Test fun restoreFailuresRemainPendingUntilReadbackSucceeds() {
        val rig = Rig(); val session = rig.session(); session.start()
        rig.deny = true
        assertFalse(session.stop()); assertTrue(rig.pending)
        rig.deny = false
        assertTrue(session.recover()); assertFalse(rig.pending)
    }

    @Test fun externalReenableIsReassertedWithABoundedBudgetAndNoBlindSuccess() {
        val rig = Rig(); val session = rig.session(); session.start()
        repeat(8) { rig.scanner = GalaxyScanState.ENABLED; session.check() }
        assertEquals(3, rig.writes.count { it == GalaxyScanAction.DISABLE })
        assertEquals(GalaxyScanState.ENABLED, session.state)
        assertTrue(rig.logs.last().contains("REASSERT_LIMIT"))
        session.stop()
        assertFalse(rig.pending)
    }

    @Test fun priorCrashMarkerRemainsOwnedDuringANewConnection() {
        val rig = Rig(); rig.pending = true; rig.scanner = GalaxyScanState.DISABLED
        val session = rig.session(); session.start()
        assertTrue(rig.writes.isEmpty())
        session.stop()
        assertEquals(listOf(GalaxyScanAction.ENABLE), rig.writes)
    }
}
