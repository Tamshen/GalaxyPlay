package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyMediaArtworkAccessTest {
    private val app = RuntimeEnvironment.getApplication()
    private val granted = mutableListOf<Triple<String, Uri, Int>>()
    private val logs = mutableListOf<String>()
    private var denied: String? = null
    private val context = object : ContextWrapper(app) {
        override fun grantUriPermission(toPackage: String, uri: Uri, flags: Int) {
            if (toPackage == denied) throw SecurityException()
            granted += Triple(toPackage, uri, flags)
        }
    }
    private val uri = Uri.parse("content://${app.packageName}.media-artwork/covers/12345678-1234-1234-1234-123456789abc.jpg")

    private fun install(name: String) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name }
        })
    }

    @Test fun grantsFinalFlymeImageReaderAlongsideServicesOnlyForCurrentImage() {
        val readers = setOf("ecarx.xsf.mediacenter", "com.ecarx.sdk.openapi", "com.flyme.auto.mediacontrol")
        (readers + "synthetic.unrelated").forEach(::install)
        GalaxyMediaArtworkAccess.grant(context, uri, 7, logs::add)
        assertEquals(readers, granted.map { it.first }.toSet())
        assertTrue(granted.all { it.second == uri && it.third == Intent.FLAG_GRANT_READ_URI_PERMISSION })
        assertTrue(logs.any { "target=com.flyme.auto.mediacontrol" in it && "result=GRANTED" in it })
        assertFalse(logs.any { "content://" in it })
    }

    @Test fun unavailableOrDeniedServiceDoesNotBlockFinalReader() {
        install("com.ecarx.sdk.openapi"); install("com.flyme.auto.mediacontrol")
        denied = "com.ecarx.sdk.openapi"
        GalaxyMediaArtworkAccess.grant(context, uri, 7, logs::add)
        assertEquals(listOf("com.flyme.auto.mediacontrol"), granted.map { it.first })
        assertTrue(logs.any { "result=FAILED" in it })
    }

    @Test fun cannotGrantOtherProvidersOrFilePaths() {
        install("com.flyme.auto.mediacontrol")
        for (value in listOf(Uri.parse("content://other/private.jpg"), Uri.parse("file:///private.jpg")))
            GalaxyMediaArtworkAccess.grant(context, value, 7, logs::add)
        assertTrue(granted.isEmpty())
        assertTrue(logs.isEmpty())
    }
}
