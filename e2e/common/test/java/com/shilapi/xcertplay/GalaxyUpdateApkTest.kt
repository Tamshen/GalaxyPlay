package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyUpdateApkTest {
    private val a = Signature(byteArrayOf(1, 2, 3))
    private val b = Signature(byteArrayOf(4, 5, 6))
    private val release = GalaxyUpdateManifest.parse(updateManifest().toString(), GalaxyUpdateManifest.endpoint, "com.ecarx.carplay")
    private fun signing(current: Array<Signature>, history: Array<Signature> = current): SigningInfo = mock(SigningInfo::class.java).apply {
        `when`(apkContentsSigners).thenReturn(current)
        `when`(hasMultipleSigners()).thenReturn(current.size > 1)
        `when`(signingCertificateHistory).thenReturn(history)
    }
    private fun pkg(code: Int, signers: Array<Signature> = arrayOf(a)) = PackageInfo().apply {
        packageName = "com.ecarx.carplay"; versionName = "0.1.119"; versionCode = code
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 29 }
        signingInfo = signing(signers)
    }
    private fun reject(reason: GalaxyUpdateFailure, archive: PackageInfo, installed: PackageInfo = pkg(146)) {
        try { GalaxyUpdateApk.validate(archive, installed, release, 30); fail("incompatible APK accepted") }
        catch (error: GalaxyUpdateException) { assertEquals(reason, error.reason) }
    }
    @Test fun matchingSignerAndHigherVersionAreAccepted() { GalaxyUpdateApk.validate(pkg(147), pkg(146), release, 30) }
    @Test fun foreignPackageAndWrongVersionAreRejected() {
        reject(GalaxyUpdateFailure.APK, pkg(147).apply { packageName = "other" })
        reject(GalaxyUpdateFailure.APK, pkg(148))
        reject(GalaxyUpdateFailure.APK, pkg(147).apply { versionName = "different" })
        reject(GalaxyUpdateFailure.APK, pkg(147), pkg(147))
        reject(GalaxyUpdateFailure.APK, pkg(147), pkg(148))
    }
    @Test fun unsupportedAndFalseMinimumSdkAreRejected() {
        reject(GalaxyUpdateFailure.APK, pkg(147).apply { applicationInfo!!.minSdkVersion = 31 })
        reject(GalaxyUpdateFailure.APK, pkg(147).apply { applicationInfo!!.minSdkVersion = 28 })
    }
    @Test fun unsignedOrDifferentSignerIsRejected() {
        reject(GalaxyUpdateFailure.SIGNATURE, pkg(147, arrayOf(b)))
        reject(GalaxyUpdateFailure.SIGNATURE, pkg(147).apply { signingInfo = null })
        reject(GalaxyUpdateFailure.SIGNATURE, pkg(147), pkg(146).apply { signingInfo = null })
    }
    @Test fun forwardRotationIsAcceptedButOldSignerCannotRollBack() {
        val rotated = pkg(147).apply { signingInfo = signing(arrayOf(b), arrayOf(a, b)) }
        GalaxyUpdateApk.validate(rotated, pkg(146), release, 30)
        reject(GalaxyUpdateFailure.SIGNATURE, pkg(147), pkg(146).apply { signingInfo = signing(arrayOf(b), arrayOf(a, b)) })
    }
    @Test fun multipleSignersRequireExactSet() {
        GalaxyUpdateApk.validate(pkg(147, arrayOf(b, a)), pkg(146, arrayOf(a, b)), release, 30)
        reject(GalaxyUpdateFailure.SIGNATURE, pkg(147, arrayOf(a)), pkg(146, arrayOf(a, b)))
    }
}
