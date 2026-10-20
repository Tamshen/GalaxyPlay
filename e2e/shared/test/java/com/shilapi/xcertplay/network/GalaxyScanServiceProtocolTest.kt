package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class GalaxyScanServiceProtocolTest {
    private fun dump(single: String, pno: String) = """
        WifiScanningService - Log Begin ----
        previous curState=DefaultState
        WifiPnoScanStateMachine:
        rec[1]: dest=DefaultState
        curState=$pno
        WifiSingleScanStateMachine:
        rec[2]: dest=ScanningState
        curState=$single
        Latest scan results:
        private-network
    """.trimIndent()

    @Test fun readsCurrentStatesRatherThanHistoricalTransitions() {
        fun state(a: String, b: String) = GalaxyScanServiceProtocol.scannerState(dump(a, b).lineSequence())
        assertEquals(GalaxyScanState.ENABLED, state("IdleState", "StartedState"))
        assertEquals(GalaxyScanState.ENABLED, state("ScanningState", "HwPnoScanState"))
        assertEquals(GalaxyScanState.DISABLED, state("DefaultState", "DefaultState"))
        assertEquals(GalaxyScanState.UNKNOWN, state("DefaultState", "StartedState"))
        assertEquals(GalaxyScanState.UNKNOWN, state("VendorState", "StartedState"))
    }

    @Test fun refusesTruncatedAmbiguousOversizedAndPermissionDeniedDumps() {
        for (text in listOf("Permission Denial", "curState=DefaultState", dump("IdleState", "StartedState") + "\ncurState=DefaultState",
            "x".repeat(1_048_577))) {
            assertEquals(GalaxyScanState.UNKNOWN, GalaxyScanServiceProtocol.scannerState(text.lineSequence()))
        }
    }

    @Test fun helperProtocolOnlyExportsKnownStatesAndQuotesTheApkPath() {
        assertEquals(GalaxyScanState.DISABLED, GalaxyScanServiceProtocol.parse("noise\nGALAXY_SCAN_SERVICE_V1|DISABLED\n"))
        assertEquals(GalaxyScanState.UNKNOWN, GalaxyScanServiceProtocol.parse("GALAXY_SCAN_SERVICE_V1|private-network"))
        assertEquals(GalaxyScanState.UNKNOWN, GalaxyScanServiceProtocol.parse(null))
        assertTrue(GalaxyScanServiceProtocol.command("/data/it's/base.apk", GalaxyScanAction.DISABLE)
            .startsWith("CLASSPATH='/data/it'\"'\"'s/base.apk' app_process"))
    }
}
