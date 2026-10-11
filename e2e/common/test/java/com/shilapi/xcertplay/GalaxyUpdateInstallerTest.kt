package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyUpdateInstallerTest {
    @Test fun activeCarPlayBlocksInstallerAndPermissionRequests() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = host.get()
        var message = 0
        val installer = GalaxyUpdateInstaller(activity, { message = it }, { true })
        host.start().resume()
        installer.install(File(activity.cacheDir, "none.apk"))
        assertEquals(R.string.update_end_connection, message)
        assertNull(shadowOf(activity).nextStartedActivity)
        host.pause().stop().destroy()
    }
    @Test fun permissionRequiresExplicitClickAndCancelDoesNotLaunchSettings() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = host.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(false)
        val launches = mutableListOf<Intent>()
        var requestCode = 0
        var status = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
                launches += contract.createIntent(activity, input)
            }
        }
        val installer = GalaxyUpdateInstaller(activity, { status = it }, { false }, registry)
        host.start().resume()
        installer.install(File(activity.cacheDir, "none.apk"))
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(launches.isEmpty())
        installer.install(File(activity.cacheDir, "none.apk"))
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, launches.size)
        val intent = launches.single()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${activity.packageName}", intent.dataString)
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        registry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        assertEquals(R.string.update_permission_ready, status)
        assertEquals(1, launches.size)
        host.pause().stop().destroy()
    }
    @Test fun installerSharesOnlyPrivateUpdateCacheThroughReadGrant() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = host.get()
        val file = File(activity.cacheDir, "app-updates/update.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val intent = GalaxyUpdateInstaller.intent(activity, file)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${activity.packageName}.app-updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        try { GalaxyUpdateInstaller.intent(activity, File(activity.cacheDir, "unrelated.txt")); fail("unrelated cache exposed") }
        catch (_: IllegalArgumentException) { }
        file.delete(); host.destroy()
    }
}
