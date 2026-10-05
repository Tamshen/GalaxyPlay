package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7VendorServiceProbeTest {
    private val manager = mock(PackageManager::class.java)
    private val context = mock(Context::class.java).also { `when`(it.packageManager).thenReturn(manager) }
    private val component = ComponentName(L7VendorServiceProbe.PACKAGE, L7VendorServiceProbe.SERVICE)

    private fun installed(metadata: Bundle? = null, permission: String? = null, exported: Boolean = true) {
        `when`(manager.getApplicationInfo(L7VendorServiceProbe.PACKAGE, PackageManager.GET_META_DATA))
            .thenReturn(ApplicationInfo().apply { enabled = true; metaData = metadata })
        `when`(manager.getServiceInfo(component, 0)).thenReturn(ServiceInfo().apply {
            enabled = false; this.exported = exported; this.permission = permission
        })
    }

    @Test fun visibleServiceDoesNotImplyAuthorizationAndDoesNotBind() {
        installed(Bundle().apply { putInt("EAS_SUPPORT", 0) }, "vendor.permission.MEDIA", false)
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("VISIBLE", facts["mediaServiceQuery"])
        assertEquals("false", facts["mediaServiceExported"])
        assertEquals("false", facts["mediaServiceEnabled"])
        assertEquals("vendor.permission.MEDIA", facts["mediaServicePermission"])
        assertEquals("UNTESTED_NO_BINDER_CALL", facts["mediaServiceAuthorization"])
        assertEquals("0", facts["mediaProviderEasSupport"])
        verify(context, times(2)).getPackageManager()
        verifyNoMoreInteractions(context)
    }

    @Test fun absentMetadataAndNoComponentPermissionStaySeparateFromZeroAndAdmission() {
        installed()
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("false", facts["mediaProviderEasSupportPresent"])
        assertEquals("ABSENT", facts["mediaProviderEasSupportReason"])
        assertNull(facts["mediaProviderEasSupport"])
        assertEquals("false", facts["mediaServicePermissionDeclared"])
        assertNull(facts["mediaServicePermission"])
        assertEquals("UNTESTED_NO_BINDER_CALL", facts["mediaServiceAuthorization"])
    }

    @Test fun invalidMetadataIsNotInventedAsZero() {
        installed(Bundle().apply { putString("EAS_SUPPORT", "invalid") })
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("true", facts["mediaProviderEasSupportPresent"])
        assertEquals("VALUE_NOT_INTEGER", facts["mediaProviderEasSupportReason"])
        assertNull(facts["mediaProviderEasSupport"])
    }

    @Test fun unavailablePackageIsNotDeclaredAbsentOrUnauthorized() {
        `when`(manager.getApplicationInfo(L7VendorServiceProbe.PACKAGE, PackageManager.GET_META_DATA))
            .thenThrow(PackageManager.NameNotFoundException("private content"))
        `when`(manager.getServiceInfo(component, 0)).thenThrow(PackageManager.NameNotFoundException())
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("NOT_VISIBLE_OR_UNINSTALLED", facts["mediaProviderQuery"])
        assertEquals("NOT_VISIBLE_OR_UNINSTALLED", facts["mediaServiceQuery"])
        assertFalse(facts.values.any { it?.contains("private content") == true })
    }

    @Test fun packageQueryDeniedDoesNotSkipIndependentComponentQuery() {
        installed()
        `when`(manager.getApplicationInfo(L7VendorServiceProbe.PACKAGE, PackageManager.GET_META_DATA))
            .thenThrow(SecurityException("private content"))
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("QUERY_DENIED", facts["mediaProviderQuery"])
        assertEquals("VISIBLE", facts["mediaServiceQuery"])
        assertEquals("SecurityException", facts["mediaProviderExceptionType"])
    }

    @Test fun componentFailureDoesNotEraseVisiblePackageEvidence() {
        installed(Bundle().apply { putInt("EAS_SUPPORT", 1) })
        `when`(manager.getServiceInfo(component, 0)).thenThrow(IllegalStateException("private content"))
        val facts = L7VendorServiceProbe.inspect(context)
        assertEquals("VISIBLE", facts["mediaProviderQuery"])
        assertEquals("1", facts["mediaProviderEasSupport"])
        assertEquals("QUERY_FAILED", facts["mediaServiceQuery"])
        assertEquals("IllegalStateException", facts["mediaServiceExceptionType"])
    }
}
