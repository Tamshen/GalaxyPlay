package com.shilapi.xcertplay

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7SpecialAccessProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun usageAccessUsesItsExplicitOpAndKeepsDefaultAndUnknownModesSeparate() {
        val ops = mock(AppOpsManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.APP_OPS_SERVICE) ops else super.getSystemService(name)
        }
        val probe = L7SpecialAccessProbe(context)
        fun state(mode: Int, grant: Boolean?) = run {
            `when`(ops.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), eq(app.packageName))).thenReturn(mode)
            probe.inspect(Manifest.permission.PACKAGE_USAGE_STATS, grant)["specialAccess"]
        }
        assertEquals("ALLOWED", state(AppOpsManager.MODE_ALLOWED, false))
        assertEquals("DENIED", state(AppOpsManager.MODE_IGNORED, true))
        assertEquals("DENIED", state(AppOpsManager.MODE_ERRORED, true))
        assertEquals("ALLOWED", state(AppOpsManager.MODE_DEFAULT, true))
        assertEquals("DENIED", state(AppOpsManager.MODE_DEFAULT, false))
        assertEquals("UNKNOWN", state(AppOpsManager.MODE_DEFAULT, null))
        assertEquals("UNKNOWN", state(AppOpsManager.MODE_FOREGROUND, true))
    }

    @Test @Config(shadows = [WriteSettingsShadow::class])
    fun overlayAndSettingsUseDedicatedQueriesEvenIfOrdinaryPermissionIsDenied() {
        ShadowSettings.setCanDrawOverlays(true)
        WriteSettingsShadow.allowed = true
        val probe = L7SpecialAccessProbe(app)
        assertEquals("ALLOWED", probe.inspect(Manifest.permission.SYSTEM_ALERT_WINDOW, false)["specialAccess"])
        assertEquals("ALLOWED", probe.inspect(Manifest.permission.WRITE_SETTINGS, false)["specialAccess"])
        ShadowSettings.setCanDrawOverlays(false)
        WriteSettingsShadow.allowed = false
        assertEquals("DENIED", probe.inspect(Manifest.permission.SYSTEM_ALERT_WINDOW, true)["specialAccess"])
        assertEquals("DENIED", probe.inspect(Manifest.permission.WRITE_SETTINGS, true)["specialAccess"])
    }

    @Test fun installAccessFailuresPreserveExceptionTypeWithoutPrivateMessages() {
        val pm = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) { override fun getPackageManager() = pm }
        val probe = L7SpecialAccessProbe(context)
        `when`(pm.canRequestPackageInstalls()).thenReturn(true)
        assertEquals("ALLOWED", probe.inspect(Manifest.permission.REQUEST_INSTALL_PACKAGES, false)["specialAccess"])
        `when`(pm.canRequestPackageInstalls()).thenThrow(SecurityException("private-secret"))
        val facts = probe.inspect(Manifest.permission.REQUEST_INSTALL_PACKAGES, true)
        assertEquals("UNKNOWN", facts["specialAccess"])
        assertEquals("SecurityException", facts["specialAccessExceptionType"])
        assertFalse(facts.toString().contains("private-secret"))
    }

    @Test fun allFilesAccessIsNotQueriedBeforeAndroid11() {
        val facts = L7SpecialAccessProbe(app).inspect(Manifest.permission.MANAGE_EXTERNAL_STORAGE, true)
        assertEquals("NOT_APPLICABLE", facts["specialAccess"])
        assertEquals("REQUIRES_API_30", facts["specialAccessReason"])
    }

    @Test @Config(sdk = [30], shadows = [AllFilesShadow::class]) fun allFilesAccessUsesAndroid11SpecialAccessState() {
        AllFilesShadow.allowed = true
        val probe = L7SpecialAccessProbe(app)
        assertEquals("ALLOWED", probe.inspect(Manifest.permission.MANAGE_EXTERNAL_STORAGE, false)["specialAccess"])
        AllFilesShadow.allowed = false
        assertEquals("DENIED", probe.inspect(Manifest.permission.MANAGE_EXTERNAL_STORAGE, true)["specialAccess"])
    }

    // 当前 Robolectric 没有这两项授权的 setter，用最小替身覆盖允许和拒绝。
    @Implements(Settings.System::class)
    class WriteSettingsShadow : ShadowSettings.ShadowSystem() {
        companion object {
            var allowed = false
            @JvmStatic @Implementation fun canWrite(context: Context) = allowed
        }
    }

    @Implements(Environment::class)
    class AllFilesShadow : ShadowEnvironment() {
        companion object {
            var allowed = false
            @JvmStatic @Implementation(minSdk = 30) fun isExternalStorageManager() = allowed
        }
    }
}
