package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DisplayDiagnosticSnapshotTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun sessionDeviceDensitySurvivesLaterDisplayChanges() {
        val metrics = context.resources.displayMetrics
        val previous = metrics.densityDpi
        try {
            metrics.densityDpi = 160
            DisplayDiagnosticSnapshot.begin(context, "request", "capability", "effective")
            metrics.densityDpi = 182
            assertTrue(DisplayDiagnosticSnapshot.report(context).contains("densityDpi=160"))
            assertFalse(DisplayDiagnosticSnapshot.report(context).contains("densityDpi=182"))
            assertTrue(L7DeviceDiagnostic.capture(context).any { it.contains("densityDpi=182") })
            assertTrue(DisplayDiagnosticSnapshot.report(context).contains("board="))
        } finally {
            metrics.densityDpi = previous
        }
    }

    @Test fun decoderFailureRemainsWithRecoveryButIsClearedOnReconnect() {
        val old = DisplayDiagnosticSnapshot.begin(context, "request", "capability", "effective")
        DisplayDiagnosticSnapshot.record(context, old,
            "Video: configure rejected decoder=c2.qti.hevc.decoder phase=configure diagnostic=example")
        DisplayDiagnosticSnapshot.record(context, old, "Video: recovery: budget exhausted")
        assertTrue(DisplayDiagnosticSnapshot.report(context).contains("phase=configure diagnostic=example"))
        DisplayDiagnosticSnapshot.begin(context, "new request", "capability", "effective")
        DisplayDiagnosticSnapshot.record(context, old, "Video: no usable decoder mime=video/hevc")
        assertFalse(DisplayDiagnosticSnapshot.report(context).contains("diagnostic=example"))
        assertFalse(DisplayDiagnosticSnapshot.report(context).contains("no usable decoder"))
    }

    @Test fun requestedSizeSurvivesAFallbackAndSubsequentPreferenceReset() {
        DisplayDiagnosticSnapshot.selection(context, 100, 75, true)
        DisplayDiagnosticSnapshot.begin(context,
            "Display request selected=Smaller percent=75 surface=1920x1080 candidate=2560x1440",
            "Decoder capability codec=c2.test hardware=true sizeSupported=false result=canvas_dimensions_unsupported",
            "Display effective percent=100 canvas=1920x1080 decision=canvas_dimensions_unsupported")
        AirPlayPersistence.saveUiScalePercent(context, 100)
        val report = DisplayDiagnosticSnapshot.report(context)
        assertTrue(report.contains("selected=75% reconnect=true"))
        assertTrue(report.contains("percent=75"))
        assertTrue(report.contains("percent=100"))
        assertTrue(report.contains("canvas_dimensions_unsupported"))
    }

    @Test fun actualOutputAndPhoneVersionsSurviveReportRedaction() {
        val attempt = DisplayDiagnosticSnapshot.begin(context, "Display request selected=Smaller",
            "Decoder capability result=supported", "Display effective canvas=2560x1440")
        DisplayDiagnosticSnapshot.record(context, attempt, "Phone display peer model=iPhone11,2 ios=18.7.10 airplay=950.7.1")
        DisplayDiagnosticSnapshot.record(context, attempt, "Video: decoder=c2.mtk.avc.decoder mime=video/avc size=2560x1440")
        DisplayDiagnosticSnapshot.record(context, attempt, "Video: output format requested=2560x1440 coded=1920x1088 crop=0,0,1919,1079")
        val report = DisplayDiagnosticSnapshot.report(context)
        assertTrue(report.contains("ios=18.7.10"))
        assertTrue(report.contains("decoder=c2.mtk.avc.decoder"))
        assertTrue(report.contains("coded=1920x1088"))
        assertTrue(report.contains("crop=0,0,1919,1079"))
    }

    @Test fun reconnectClearsOldOutputAndRejectsLateCallbacks() {
        val old = DisplayDiagnosticSnapshot.begin(context, "old request", "old capability", "old effective")
        DisplayDiagnosticSnapshot.record(context, old, "Video: output format coded=2560x1440")
        val current = DisplayDiagnosticSnapshot.begin(context, "new request", "new capability", "new effective")
        DisplayDiagnosticSnapshot.record(context, old, "Video: output format coded=800x480")
        assertFalse(DisplayDiagnosticSnapshot.report(context).contains("coded="))
        assertEquals(current, DisplayDiagnosticSnapshot.currentAttempt(context))
    }

    @Test fun onlySupportedMetadataIsStoredAndItIsBounded() {
        val attempt = DisplayDiagnosticSnapshot.begin(context, "request", "capability", "effective")
        DisplayDiagnosticSnapshot.record(context, attempt, "payload=secret")
        DisplayDiagnosticSnapshot.record(context, attempt, "Video: output format password=secret")
        DisplayDiagnosticSnapshot.record(context, attempt, "Video: decoder=" + "x".repeat(2000))
        val report = DisplayDiagnosticSnapshot.report(context)
        assertFalse(report.contains("secret"))
        assertTrue(report.lines().all { it.length <= 700 })
    }

    @Test fun installationAndLatestVideoHealthAreIncludedWithoutAcceptingAnOldSession() {
        val old = DisplayDiagnosticSnapshot.begin(context, "old", "capability", "effective")
        val current = DisplayDiagnosticSnapshot.begin(context, "new", "capability", "effective")
        DisplayDiagnosticSnapshot.record(context, current, "Video: recovery: input stalled attempt=2/4")
        DisplayDiagnosticSnapshot.record(context, current, "Video: first frame rendered")
        DisplayDiagnosticSnapshot.record(context, current, "Video: video stats input=30fps presented=29fps ageP95Bucket input=33ms")
        DisplayDiagnosticSnapshot.record(context, old, "Video: recovery: old failure")
        val report = DisplayDiagnosticSnapshot.report(context)
        assertTrue(report.contains("L7 installation package="))
        assertTrue(report.contains("matchesE5Reference=false"))
        assertTrue(report.contains("attempt=2/4"))
        assertTrue(report.contains("presented=29fps"))
        assertFalse(report.contains("old failure"))
    }
}
