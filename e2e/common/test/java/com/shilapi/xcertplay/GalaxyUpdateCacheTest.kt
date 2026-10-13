package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyUpdateCacheTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var file: File
    private lateinit var release: GalaxyUpdateRelease
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "app-updates").apply { mkdirs() }
        GalaxyUpdateCache.clear(context)
        file = File(root, "download-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        release = GalaxyUpdateManifest.parse(updateManifest(pkg = context.packageName).toString(), GalaxyUpdateManifest.endpoint, context.packageName)
        installed(146)
    }
    @Suppress("DEPRECATION") private fun installed(code: Int) {
        shadowOf(context.packageManager).installPackage(context.packageManager.getPackageInfo(context.packageName, 0).apply { versionCode = code })
    }
    @Test fun readyMetadataSurvivesNewContextAndPreservesRelease() {
        GalaxyUpdateCache.save(context, file, release)
        val pending = GalaxyUpdateCache.load(context.createPackageContext(context.packageName, 0))!!
        assertEquals(file.canonicalFile, pending.file.canonicalFile)
        assertEquals(release, pending.release)
    }
    @Test fun completedVersionRemovesMetadataAndOwnedApk() {
        GalaxyUpdateCache.save(context, file, release); installed(147)
        assertNull(GalaxyUpdateCache.load(context)); assertFalse(file.exists())
        assertFalse(File(root, "pending.json").exists())
    }
    @Test fun expiredOrDamagedMetadataIsNotRestored() {
        GalaxyUpdateCache.save(context, file, release)
        File(root, "pending.json").setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        assertNull(GalaxyUpdateCache.load(context))
        assertFalse(file.exists())
        File(root, "pending.json").writeText("{" + "x".repeat(GalaxyUpdateManifest.MAX_METADATA_SIZE))
        assertNull(GalaxyUpdateCache.load(context)); assertFalse(File(root, "pending.json").exists())
    }

    @Test fun reopeningWithoutRecordCleansExpiredOrphansButKeepsRecentAndUnrelatedFiles() {
        val old = File(root, "download-old.partial").apply {
            writeText("partial"); setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        }
        val unrelated = File(root, "keep.txt").apply { writeText("keep"); setLastModified(1) }
        assertNull(GalaxyUpdateCache.load(context))
        assertFalse(old.exists()); assertTrue(file.exists()); assertTrue(unrelated.exists())
    }

    @Test fun activeTransferAndValidPendingPackageAreNotDeletedWhenTheirFilesAreOld() {
        file.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        GalaxyUpdateFiles.protect(file).use {
            assertNull(GalaxyUpdateCache.load(context)); assertTrue(file.exists())
        }
        GalaxyUpdateCache.save(context, file, release)
        GalaxyUpdateFiles.cleanup(root)
        assertTrue(file.exists()); assertNotNull(GalaxyUpdateCache.load(context))
        GalaxyUpdateCache.clear(context)
        assertNull(GalaxyUpdateCache.load(context)); assertFalse(file.exists())
    }

    @Test fun symlinkCannotCauseOutsideCacheFileToBeDeleted() {
        val outside = File(context.cacheDir, "outside.apk").apply { writeText("private"); setLastModified(1) }
        val link = File(root, "download-link.apk")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        GalaxyUpdateFiles.cleanup(root)
        assertTrue(outside.exists())
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
    }

    @Test fun activeSystemInstallerKeepsItsPackageEvenIfPendingRecordExpires() {
        GalaxyUpdateCache.save(context, file, release)
        assertTrue(GalaxyUpdateFiles.beginInstall(file))
        try {
            assertFalse(GalaxyUpdateFiles.beginInstall(file))
            File(root, "pending.json").setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
            assertNull(GalaxyUpdateCache.load(context)); assertTrue(file.exists())
        } finally { GalaxyUpdateFiles.finishInstall() }
        file.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        assertNull(GalaxyUpdateCache.load(context)); assertFalse(file.exists())
    }
    @Test fun missingOrChangedApkIsNotRestored() {
        GalaxyUpdateCache.save(context, file, release); file.writeText("changed")
        assertNull(GalaxyUpdateCache.load(context))
        GalaxyUpdateCache.save(context, file, release); file.delete()
        assertNull(GalaxyUpdateCache.load(context))
    }
    @Test fun cachedPathCannotEscapePrivateUpdateDirectory() {
        GalaxyUpdateCache.save(context, file, release)
        val record = File(root, "pending.json")
        record.writeText(JSONObject(record.readText()).put("file", "../outside.apk").toString())
        assertNull(GalaxyUpdateCache.load(context))
        try { GalaxyUpdateCache.save(context, File(context.cacheDir, "outside.apk"), release); fail("必须拒绝外部文件") }
        catch (error: GalaxyUpdateException) { assertEquals(GalaxyUpdateFailure.STORAGE, error.reason) }
    }
}
