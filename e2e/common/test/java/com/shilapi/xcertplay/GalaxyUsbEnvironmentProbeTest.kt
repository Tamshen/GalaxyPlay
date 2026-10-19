package com.shilapi.xcertplay

import android.content.Intent
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** 环境证据不等同连接能力；验证隐私、边界、观察取消和日志进入既有报告。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyUsbEnvironmentProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun planCoversDependenciesAndDoesNotRepeatIds() {
        val plan = GalaxyUsbEnvironmentProbe(app).queries()
        assertEquals(plan.size, plan.map { it.id }.distinct().size)
        for (id in listOf("USB-SYSTEM", "USB-IDENTITY", "USB-USB", "USB-PORTS", "USB-NETWORK", "USB-NODE-0",
            "USB-SYSFS-3", "USB-RULE-DISCOVERY", "USB-KERNEL", "USB-MODULES", "USB-SELINUX", "USB-LIBRARY-0",
            "USB-APVP", "USB-ROUTES", "USB-PERMISSION-0", "USB-BOUNDARIES")) assertTrue(id, plan.any { it.id == id })
        assertTrue(plan.size > 55)
        val boundary = GalaxyUsbEnvironmentProbe(app).inspect(plan.last())
        assertEquals(L7ProbeStatus.PENDING, L7ProbeStatus.of(boundary))
        assertEquals("NOT_ATTEMPTED", boundary.facts["usbRoleSwitch"])
    }

    @Test fun absencePermissionAndHiddenApiRemainDistinctWithoutExceptionText() {
        val probe = GalaxyUsbEnvironmentProbe(app)
        val errors = listOf(
            ErrnoException("private-path", OsConstants.ENOENT) to "NOT_VISIBLE_AT_PATH",
            ErrnoException("private-path", OsConstants.EACCES) to "DENIED",
            InvocationTargetException(SecurityException("private-phone")) to "DENIED")
        errors.forEach { (error, state) ->
            val item = probe.inspect(GalaxyUsbEnvironmentProbe.Query("USB-TEST", "USB_PORT_STATUS") { throw error })
            assertEquals(state, item.facts["usbVisibility"])
            assertEquals("USB_PORT_STATUS", item.facts["usbSource"])
            assertEquals("APP_UID_READ_ONLY", item.facts["usbScope"])
            assertFalse(item.json().toString().contains("private"))
            assertNotEquals(L7ProbeStatus.SUPPORTED, L7ProbeStatus.of(item))
        }
        val visible = probe.inspect(GalaxyUsbEnvironmentProbe.Query("USB-TEST") { mapOf("usbVisibility" to "PRESENT") })
        assertEquals(L7ProbeStatus.OBSERVED, L7ProbeStatus.of(visible))
    }

    @Test fun fileReadsAreBoundedAndOnlyKnownMarkersEscape() {
        val file = File(app.cacheDir, "usb-fixture").apply { writeText("iap,ncm\nprivate-serial\n" + "x".repeat(100)) }
        try {
            val files = GalaxyUsbFileEvidence()
            val read = files.read(file.path, 32)
            assertTrue(read.truncated)
            assertEquals(32, read.text.length)
            val evidence = GalaxyUsbFileEvidence.markers(read, GalaxyUsbPlatformEvidence.markers)
            assertEquals("IAP_NCM", evidence["usbObserved"])
            assertFalse(evidence.toString().contains("private-serial"))
            val tokens = files.tokenFile(file.path, setOf("iap", "ncm"))
            assertEquals("iap,ncm", tokens["usbObserved"])
            assertFalse(tokens.toString().contains("private-serial"))
        } finally { file.delete() }
    }

    @Test fun observationsCloseReceiverAndStopWithoutSendingUsbRequests() {
        val before = RemoteLogUpload.status
        val items = mutableListOf<L7ProbeItem>()
        val probe = GalaxyUsbEnvironmentProbe(app)
        val receiverCount = org.robolectric.Shadows.shadowOf(app).registeredReceivers.size
        probe.observe({ true }, items::add, durationMs = 0)
        assertEquals(receiverCount, org.robolectric.Shadows.shadowOf(app).registeredReceivers.size)
        assertEquals(1, items.size)
        assertEquals("USB-OBSERVE-0", items.single().id)
        assertEquals("baseline", items.single().facts["usbChanges"])
        assertEquals("APP_UID_READ_ONLY", items.single().facts["usbScope"])
        val frozen = items.single().json().toString()
        app.sendBroadcast(Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"))
        assertEquals(frozen, items.single().json().toString())
        probe.observe({ false }, { fail("取消后不得发布") }, durationMs = 0)
        assertEquals(before, RemoteLogUpload.status)
    }

    @Test fun logAndReportKeepEvidenceIncludingUnknownsWithoutDeviceIdentity() {
        val item = L7ProbeItem("USB-NODE-0", "USB-NODE-0", "CARPLAY_ENV", L7ProbeOutcome.NOT_OBSERVED, "USB_EVIDENCE_ONLY",
            mapOf("usbVisibility" to "NOT_VISIBLE_AT_PATH", "usbSource" to "/dev/ipodout0", "usbAccessRead" to "DENIED",
                "usbEvents" to "attach=1,detach=1,state=0", "usbNetwork" to "usb0:up=true,v6ll=1",
                "serial" to "private-phone", "usbServiceIdentity" to "UNVERIFIED"))
        val report = L7ProbeReport(UUID.randomUUID().toString(), "env", "test", 1, L7ProbePhase.COMPLETED, listOf(item), 1)
        val lines = L7ProbeLog.lines(report).joinToString("\n")
        assertTrue(lines.contains("usbVisibility=NOT_VISIBLE_AT_PATH"))
        assertTrue(lines.contains("usbEvents=attach=1,detach=1,state=0"))
        assertTrue(lines.contains("usbSource=/dev/ipodout0"))
        assertTrue(lines.contains("usbServiceIdentity=UNVERIFIED"))
        assertFalse(lines.contains("private-phone"))
        assertEquals(report, L7ProbeReport.read(report.json()))
    }

    @Test fun requestedHierarchyReturnsToEnvironmentThenDebug() {
        assertEquals("settings-debug-carplay", L7Routes.normalize("settings-debug-carplay"))
        assertEquals("settings-debug-environment", L7Routes.back("settings-debug-carplay"))
        assertEquals("settings-debug", L7Routes.back("settings-debug-environment"))
        assertEquals("settings", L7Routes.back("settings-debug"))
    }
}
