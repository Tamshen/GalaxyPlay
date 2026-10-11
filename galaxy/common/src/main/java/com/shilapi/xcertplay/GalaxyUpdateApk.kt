package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class GalaxyUpdateFileProvider : FileProvider()

/** 清单和哈希不替代 APK 身份校验；仅接受本应用签名的更高版本。 */
internal object GalaxyUpdateApk {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: GalaxyUpdateRelease, request: GalaxyUpdateRequest) {
        updateRequire(file.length() == release.size, GalaxyUpdateFailure.INTEGRITY)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                request.check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        updateRequire(GalaxyUpdateTransfer.hex(digest.digest()) == release.sha256, GalaxyUpdateFailure.INTEGRITY)
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw GalaxyUpdateException(GalaxyUpdateFailure.APK)
        validate(archive, installed, release, Build.VERSION.SDK_INT)
    }

    internal fun validate(archive: PackageInfo, installed: PackageInfo, release: GalaxyUpdateRelease, sdk: Int) {
        updateRequire(archive.packageName == installed.packageName && archive.packageName == release.packageName &&
            archive.longVersionCode == release.code && archive.versionName == release.version &&
            release.newerThan(installed.longVersionCode), GalaxyUpdateFailure.APK)
        val minimum = archive.applicationInfo?.minSdkVersion ?: throw GalaxyUpdateException(GalaxyUpdateFailure.APK)
        updateRequire(minimum <= sdk && (release.minSdk == null || release.minSdk == minimum), GalaxyUpdateFailure.APK)
        val current = installed.signingInfo ?: throw GalaxyUpdateException(GalaxyUpdateFailure.SIGNATURE)
        val next = archive.signingInfo ?: throw GalaxyUpdateException(GalaxyUpdateFailure.SIGNATURE)
        val signers = current.apkContentsSigners?.toSet().orEmpty()
        updateRequire(signers.isNotEmpty(), GalaxyUpdateFailure.SIGNATURE)
        val valid = if (current.hasMultipleSigners() || next.hasMultipleSigners()) {
            signers == next.apkContentsSigners?.toSet()
        } else {
            next.signingCertificateHistory?.toSet()?.containsAll(signers) == true
        }
        updateRequire(valid, GalaxyUpdateFailure.SIGNATURE)
    }
}
