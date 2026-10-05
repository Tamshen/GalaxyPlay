package com.shilapi.xcertplay

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ReportingProbeTest {
    private fun permission(name: String, declared: String, granted: String, visible: String?, mode: String = "DEFAULT") =
        L7ProbeItem("PERM:$name", name, "PERMISSION", L7ProbeOutcome.UNKNOWN, "DEFINITION_NOT_VISIBLE",
            mapOf("declared" to declared, "granted" to granted, "definitionVisible" to visible, "appOpMode" to mode))

    @Test fun missingDefinitionsAndDeclarationsRemainSeparateFromUnsupported() {
        val item = L7ReportingProbe({ permission(it, "false", "false", null) }, { "NOT_VISIBLE" }).inspect("REPORT-HUD")
        assertEquals(L7ProbeStatus.PENDING, L7ProbeStatus.of(item))
        assertEquals("0", item.facts["declaredCount"])
        assertEquals("0", item.facts["definitionVisibleCount"])
        assertEquals("0", item.facts["sdkVisibleCount"])
        val detail = item.facts.getValue("permission.ecarx.openapi.permission.NAVI_SERVICE")!!
        assertTrue(detail.contains("declared=false;granted=false;definitionVisible=unknown"))
        assertTrue(detail.contains("reason=DEFINITION_NOT_VISIBLE"))
    }

    @Test fun grantsAndSdkVisibilityNeverProveServiceAuthorizationOrQnxReception() {
        val item = L7ReportingProbe({ permission(it, "true", "true", "true", "IGNORED") }, { "VISIBLE" }).inspect("REPORT-QNX")
        assertEquals("3", item.facts["grantedCount"])
        assertEquals("3", item.facts["restrictedCount"])
        assertEquals("2", item.facts["sdkVisibleCount"])
        assertEquals("NOT_RUN", item.facts["effectiveCall"])
        assertEquals("NOT_TESTED", item.facts["serviceAuthorization"])
        assertEquals("UNKNOWN", item.facts["reportingSupported"])
        assertEquals("UNCONFIRMED_MEDIACENTER_DOWNSTREAM", item.facts["qnxProtocol"])
        assertEquals(L7ProbeStatus.PENDING, L7ProbeStatus.of(item))
        assertFalse(item.facts["candidatePermissions"]!!.contains("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"))
    }

    @Test fun sdkChecksDoNotInitializeClassesAndMissingDependenciesRemainVisibleInEvidence() {
        assertEquals("VISIBLE", L7ReportingProbe.sdkVisibility(UninitializedSdk::class.java.name, javaClass.classLoader!!))
        assertEquals("NOT_VISIBLE", L7ReportingProbe.sdkVisibility("missing.vendor.Sdk", javaClass.classLoader!!))
        val loader = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> = throw NoClassDefFoundError("missing dependency")
        }
        assertEquals("UNAVAILABLE:NoClassDefFoundError", L7ReportingProbe.sdkVisibility("vendor.Sdk", loader))
    }

    @Test fun reportingEvidenceSurvivesJsonAndLogExportWithReadableLabels() {
        val item = L7ReportingProbe({ permission(it, "false", "false", null) }, { "NOT_VISIBLE" }).inspect("REPORT-QNX")
        val report = L7ProbeReport(UUID.randomUUID().toString(), "test", "test", 1,
            L7ProbePhase.COMPLETED, listOf(item), 1, 2)
        assertEquals(item, L7ProbeReport.read(report.json()).items.single())
        val log = L7ProbeLog.lines(report).joinToString("\n")
        assertTrue(log.contains("entry=REPORT-QNX status=PENDING"))
        assertTrue(log.contains("qnxProtocol=UNCONFIRMED_MEDIACENTER_DOWNSTREAM"))
        assertTrue(log.contains("permission.com.ecarx.media.provider.WRITE_DYNAMIC_SOURCE_DATA=declared=false"))
        val labels = L7ProbeLabels(RuntimeEnvironment.getApplication())
        assertFalse(labels.reason(item).contains("QUERY_FAILED"))
        assertFalse(labels.name(item).contains("REPORT-QNX"))
        assertTrue(labels.compactReason(item).contains("0/3"))
    }

    class UninitializedSdk {
        companion object { init { error("专项只读检查不得初始化厂商 SDK") } }
    }
}
