package com.shilapi.xcertplay

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7PermissionProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun context(declared: Boolean, granted: Boolean, mode: Int = AppOpsManager.MODE_DEFAULT,
        name: String = Manifest.permission.CAMERA, protection: Int = PermissionInfo.PROTECTION_DANGEROUS,
        failure: Exception? = null): Context {
        val pm = mock(PackageManager::class.java)
        `when`(pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)).thenReturn(PackageInfo().apply {
            requestedPermissions = if (declared) arrayOf(name) else emptyArray()
        })
        `when`(pm.getPermissionInfo(name, 0)).thenReturn(PermissionInfo().apply {
            this.name = name; packageName = "android"; protectionLevel = protection
        })
        val ops = mock(AppOpsManager::class.java)
        `when`(ops.unsafeCheckOpNoThrow(anyString(), anyInt(), anyString())).thenReturn(mode)
        return object : ContextWrapper(app) {
            override fun getPackageManager() = pm
            override fun checkSelfPermission(permission: String): Int {
                failure?.let { throw it }
                return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            override fun getSystemService(name: String): Any? = if (name == Context.APP_OPS_SERVICE) ops else super.getSystemService(name)
        }
    }

    @Test fun grantedPermissionRemainsObservedUntilAnInterfaceIsTested() {
        val item = L7PermissionProbe(context(true, true)).inspect(Manifest.permission.CAMERA)
        assertEquals(L7ProbeOutcome.OBSERVED, item.result)
        assertEquals("GRANTED_NOT_CALLED", item.reason)
        assertEquals("NOT_RUN", item.facts["effectiveCall"])
        assertEquals("DEFAULT", item.facts["appOpMode"])
    }

    @Test fun declarationGrantAndAppOpsRestrictionsRemainDistinct() {
        assertEquals("NOT_DECLARED", L7PermissionProbe(context(false, false)).inspect(Manifest.permission.CAMERA).reason)
        assertEquals(L7ProbeOutcome.UNKNOWN, L7PermissionProbe(context(false, false)).inspect(Manifest.permission.CAMERA).result)
        assertEquals("NOT_GRANTED", L7PermissionProbe(context(true, false)).inspect(Manifest.permission.CAMERA).reason)
        val restricted = L7PermissionProbe(context(true, true, AppOpsManager.MODE_IGNORED)).inspect(Manifest.permission.CAMERA)
        assertEquals("APP_OP_RESTRICTED", restricted.reason)
        assertEquals("true", restricted.facts["granted"])
    }

    @Test fun unavailableDefinitionsAreUnknownAndSuspiciousNamesStayUnmodified() {
        val probe = L7PermissionProbe(app)
        val name = "android. permission. BLUETOOTH_CONNECT"
        assertTrue(name in probe.names)
        val item = probe.inspect(name)
        assertEquals(L7ProbeOutcome.UNKNOWN, item.result)
        assertNull(item.facts["definitionVisible"])
        assertEquals(name, item.facts["rawName"])
        assertTrue(item.facts["staticWarnings"].orEmpty().contains("WHITESPACE_IN_NAME"))
    }

    @Test fun specialAccessOverridesOrdinaryGrantWithoutClaimingAnInterfaceCall() {
        for (name in listOf(Manifest.permission.WRITE_SETTINGS, Manifest.permission.SYSTEM_ALERT_WINDOW)) {
            val permission = L7PermissionProbe(context(true, false, name = name)) { _, _ ->
                mapOf("specialAccess" to "ALLOWED")
            }.inspect(name)
            assertEquals("false", permission.facts["granted"])
            assertEquals("SPECIAL_ACCESS_ALLOWED", permission.reason)
            assertEquals(L7ProbeStatus.GRANTED, L7ProbeStatus.of(permission))
            assertEquals("NOT_RUN", permission.facts["effectiveCall"])
            assertEquals("SPECIAL_ACCESS_DENIED", L7PermissionProbe(context(true, true, name = name)) { _, _ ->
                mapOf("specialAccess" to "DENIED")
            }.inspect(name).reason)
        }
    }

    @Test fun protectionFlagsAndQueryFailuresRemainExplicitWithoutExceptionMessages() {
        val ctx = context(true, true, protection = 18 or 0x40000000)
        val item = L7PermissionProbe(ctx).inspect(Manifest.permission.CAMERA)
        assertEquals("SIGNATURE", item.facts["protectionBase"])
        assertEquals("PRIVILEGED|UNKNOWN_BITS:0x40000000", item.facts["protectionFlags"])
        val failed = L7PermissionProbe(context(true, false, failure = SecurityException("private-secret")))
            .inspect(Manifest.permission.CAMERA)
        assertEquals("QUERY_FAILED", failed.reason)
        assertEquals("SecurityException", failed.facts["grantExceptionType"])
        assertNull(failed.facts["granted"])
        assertFalse(failed.json().toString().contains("private-secret"))
    }

    @Test fun newerStandardPermissionsAreInapplicableButVendorUnknownsStayUnknown() {
        val probe = L7PermissionProbe(app)
        assertEquals(L7ProbeOutcome.NOT_APPLICABLE, probe.inspect(Manifest.permission.NEARBY_WIFI_DEVICES).result)
        assertEquals(L7ProbeOutcome.NOT_APPLICABLE, probe.inspect(Manifest.permission.MANAGE_EXTERNAL_STORAGE).result)
        assertEquals(L7ProbeOutcome.UNKNOWN, probe.inspect("com.huawei.permission.HISIGHT_ACCESS").result)
        val backported = L7PermissionProbe(context(true, true, name = Manifest.permission.BLUETOOTH_CONNECT))
            .inspect(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals("GRANTED_NOT_CALLED", backported.reason)
    }

    @Test fun definitionAndAppOpsExceptionsCannotBecomeGrantOrUnsupportedResults() {
        val ctx = context(true, true)
        `when`(ctx.packageManager.getPermissionInfo(Manifest.permission.CAMERA, 0))
            .thenThrow(SecurityException("private-secret"))
        val definition = L7PermissionProbe(ctx).inspect(Manifest.permission.CAMERA)
        assertEquals("QUERY_FAILED", definition.reason)
        assertEquals("SecurityException", definition.facts["definitionExceptionType"])
        val blockedOps = object : ContextWrapper(context(true, true)) {
            override fun getSystemService(name: String): Any? = throw SecurityException("private-secret")
        }
        val op = L7PermissionProbe(blockedOps).inspect(Manifest.permission.CAMERA)
        assertEquals("QUERY_FAILED", op.reason)
        assertEquals("SecurityException", op.facts["appOpExceptionType"])
        assertEquals("true", op.facts["granted"])
        assertEquals(L7ProbeStatus.ERROR, L7ProbeStatus.of(op))
        assertFalse(op.json().toString().contains("private-secret"))
    }

    @Test fun catalogIncludesAllDocumentedSourcesWithoutTreatingComponentProtectionAsARequest() {
        val probe = L7PermissionProbe(app)
        assertTrue(probe.names.containsAll(listOf("android.permission.CONNECTIVITY_INTERNAL",
            "com.huawei.permission.HISIGHT_ACCESS", "huawei.permission.GET_DISTRIBUTED_DEVICE_INFO")))
        val provider = probe.inspect("com.huawei.dmsdpdevice.permission.DMSDP_DEVICE_INTERFACE")
        assertEquals("huawei_dmsdp", provider.facts["reference.definitions"])
        assertEquals("huawei_dmsdp", provider.facts["reference.accessProtections"])
        assertEquals("", provider.facts["reference.requests"])
        assertEquals("false", provider.facts["declared"])
        assertTrue(probe.inspect("android.Manifest.permission.MASTER_CLEAR").facts["staticWarnings"]!!
            .contains("NON_STANDARD_PERMISSION_NAMESPACE"))
    }
}
