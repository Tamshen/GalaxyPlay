package com.shilapi.xcertplay

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyStorageUsageTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun reportsFileCategoriesWithoutCountingSymlinkTargetsOrReadingFileBodies() {
        val before = GalaxyStorageUsage.read(app)
        val log = File(app.filesDir, "logs/usage.log").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1000)) }
        val cache = File(app.cacheDir, "usage-cache.tmp").apply { writeBytes(ByteArray(2000)) }
        val setting = File(app.filesDir, "usage-setting.json").apply { writeBytes(ByteArray(3000)) }
        val link = File(app.cacheDir, "outside-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), setting.toPath())
        try {
            val after = GalaxyStorageUsage.read(app)
            assertEquals(6000L, after.total - before.total)
            assertEquals(1000L, after.logs - before.logs)
            assertEquals(2000L, after.cache - before.cache)
            assertEquals(3000L, after.other - before.other)
            assertTrue(after.memory >= 0)
        } finally { log.delete(); cache.delete(); setting.delete(); link.delete() }
    }

    @Test fun temporaryCleanupKeepsActiveSnapshotArtworkAndTransferButRemovesOrphans() {
        val active = File(app.cacheDir, "log-upload-snapshots/upload-active").apply { mkdirs(); resolve("batch.bin").writeText("active") }
        val orphan = File(app.cacheDir, "log-upload-snapshots/upload-orphan").apply { mkdirs(); resolve("index.db").writeText("orphan") }
        val orphanApk = File(app.cacheDir, "app-updates/download-orphan.apk").apply {
            parentFile!!.mkdirs(); writeText("unrecorded"); setLastModified(System.currentTimeMillis() - 120_000)
        }
        val artwork = File(app.cacheDir, "l7-media-artwork/11111111-1111-1111-1111-111111111111").apply { mkdirs(); resolve("cover.jpg").writeText("active") }
        val partial = File(app.cacheDir, "app-updates/download-old.partial").apply { parentFile!!.mkdirs(); writeText("active"); setLastModified(1) }
        GalaxyTemporaryFiles.protect(active).use {
            GalaxyTemporaryFiles.protect(artwork).use {
                GalaxyUpdateFiles.protect(partial).use {
                    GalaxyTemporaryFiles.cleanup(app)
                    assertTrue(active.exists()); assertTrue(artwork.exists()); assertTrue(partial.exists())
                    assertFalse(orphan.exists())
                    assertFalse(orphanApk.exists())
                }
            }
        }
        GalaxyTemporaryFiles.cleanup(app)
        assertFalse(active.exists()); assertFalse(artwork.exists()); assertFalse(partial.exists())
    }

    @Test fun uploadTemporaryBudgetFailsExplicitlyWithoutRemovingTheLocalSourceLog() {
        val source = File(app.filesDir, "logs/debug.log").apply { parentFile!!.mkdirs(); writeText("keep all local evidence") }
        val snapshot = File(app.cacheDir, "log-upload-snapshots/upload-budget").apply { mkdirs() }
        RandomAccessFile(File(snapshot, "batches.bin"), "rw").use { it.setLength(GalaxyTemporaryFiles.SNAPSHOT_BUDGET + 1) }
        try {
            assertTrue(runCatching { GalaxyTemporaryFiles.checkSnapshot(snapshot) }.isFailure)
            assertEquals("keep all local evidence", source.readText())
        } finally { snapshot.deleteRecursively(); source.delete() }
    }
}
