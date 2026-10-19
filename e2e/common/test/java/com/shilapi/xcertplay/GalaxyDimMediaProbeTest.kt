package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyDimMediaProbeTest {
    private val manager = mock(PackageManager::class.java)
    private val context = mock(Context::class.java).also { `when`(it.packageManager).thenReturn(manager) }
    private val service = ComponentName(GalaxyDimMediaProbe.PACKAGE, GalaxyDimMediaProbe.SERVICE)
    @Test fun visibleProtectedServiceReportsPermissionWithoutBindingOrSending() {
        `when`(manager.getServiceInfo(service, 0)).thenReturn(ServiceInfo().apply {
            enabled = true; exported = true; permission = "vendor.permission.DIM"
        })
        `when`(context.checkSelfPermission("vendor.permission.DIM")).thenReturn(PackageManager.PERMISSION_DENIED)
        val facts = GalaxyDimMediaProbe.inspect(context)
        assertEquals(1L, facts["dimService"])
        assertEquals(1L, facts["dimEnabled"])
        assertEquals(0L, facts["dimPermissionGranted"])
        verify(context).packageManager
        verify(context).checkSelfPermission("vendor.permission.DIM")
        verifyNoMoreInteractions(context)
    }
    @Test fun invisibleDeniedAndFailedQueriesStayDistinctWithoutExceptionPayload() {
        for ((error, result) in listOf(PackageManager.NameNotFoundException("private-name") to 2L,
            SecurityException("private-name") to 3L, IllegalStateException("private-name") to 4L)) {
            doThrow(error).`when`(manager).getServiceInfo(service, 0)
            assertEquals(mapOf("dimService" to result), GalaxyDimMediaProbe.inspect(context))
        }
    }
}
