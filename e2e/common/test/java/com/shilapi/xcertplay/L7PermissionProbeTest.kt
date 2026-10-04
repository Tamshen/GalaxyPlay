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
    private fun context(declared: Boolean, granted: Boolean, mode: Int = AppOpsManager.MODE_DEFAULT): Context {
        val pm = mock(PackageManager::class.java)
        `when`(pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)).thenReturn(PackageInfo().apply {
            requestedPermissions = if (declared) arrayOf(Manifest.permission.CAMERA) else emptyArray()
        })
        `when`(pm.getPermissionInfo(Manifest.permission.CAMERA, 0)).thenReturn(PermissionInfo().apply {
            name = Manifest.permission.CAMERA; packageName = "android"; protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        })
        val ops = mock(AppOpsManager::class.java)
        `when`(ops.unsafeCheckOpNoThrow(anyString(), anyInt(), anyString())).thenReturn(mode)
        return object : ContextWrapper(app) {
            override fun getPackageManager() = pm
            override fun checkSelfPermission(permission: String) = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
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
}
