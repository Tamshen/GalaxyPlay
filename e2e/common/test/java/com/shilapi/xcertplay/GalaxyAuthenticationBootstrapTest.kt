package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import com.shilapi.xcertplay.diagnostics.DiagnosticSink
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyAuthenticationBootstrapTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun externalBackendsNeverRequireOrReplaceLocalIdentityOrOtherSettings() {
        val local = localFiles()
        prefs.edit().putString("identity_private", "test-only-old-pairing")
            .putBoolean("wireless_enabled", false).putString("manual_hotspot_ssid", "test-only-network")
            .putString("manual_hotspot_passphrase", "test-only-password").commit()
        val events = mutableListOf<DiagnosticEvent>()
        val bootstrap = GalaxyAuthenticationBootstrap { DiagnosticSink(events::add) }
        for (target in listOf(MfiTarget.USB_CH341, MfiTarget.I2C, MfiTarget.REMOTE)) {
            AirPlayPersistence.saveMfiConfiguration(context, target, "https://test.invalid", "test-only-token")
            val previous = prefs.all.filterKeys { it != "l7_usage_audio_defaults_v1" }
            bootstrap.ensure(context)
            bootstrap.ensure(context)
            assertEquals(previous, prefs.all.filterKeys { it != "l7_usage_audio_defaults_v1" })
            assertEquals(target, AirPlayPersistence.loadMfiTarget(context))
            local.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
        assertEquals(12, events.size)
        assertTrue(events.all { it.component == DiagnosticEvent.Component.AUTHENTICATION && it.session < 0 })
        assertTrue(events.groupBy { it.session }.values.all { it.map { event -> event.sequence } == listOf(1L, 2L) })
        assertEquals(listOf(0L, 1L, 0L, 1L, 0L, 0L), events.filter { it.state == DiagnosticEvent.State.READY }.map { it.metrics["cached"] })
    }

    @Test fun remoteSettingsAreValidatedAgainAfterSuccessfulPreparationAndLogsContainNoValues() {
        val lines = mutableListOf<String>()
        val bootstrap = GalaxyAuthenticationBootstrap { GalaxyDiagnosticSink(lines::add) }
        AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.REMOTE, "https://test.invalid", "test-only-token")
        bootstrap.ensure(context)
        AirPlayPersistence.saveRemoteMfiServer(context, "https://test-only-user:test-only-password@test.invalid")
        assertThrows(Exception::class.java) { bootstrap.ensure(context) }
        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(context))
        assertTrue(lines.last().contains("kind=FAILURE state=FAILED"))
        assertTrue(lines.all { !it.contains("test-only") && !it.contains("test.invalid") && !it.contains("https") })
        AirPlayPersistence.saveRemoteMfiServer(context, "https://test.invalid")
        bootstrap.ensure(context)
        assertTrue(lines.last().contains("state=READY"))
    }

    @Test fun rejectedExistingLocalIdentityRemainsOnDiskAndExternalSwitchCanStillProceed() {
        val local = localFiles()
        val events = mutableListOf<DiagnosticEvent>()
        val bootstrap = GalaxyAuthenticationBootstrap { DiagnosticSink(events::add) }
        assertThrows(Exception::class.java) { bootstrap.ensure(context) }
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        local.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(DiagnosticEvent.State.FAILED, events.last().state)
        AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.USB_CH341)
        bootstrap.reload(context)
        assertEquals(DiagnosticEvent.State.READY, events.last().state)
    }

    @Test fun missingBundledIdentityCleansStagingAndDoesNotCacheFailure() {
        val directory = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        directory.deleteRecursively()
        val bootstrap = GalaxyAuthenticationBootstrap { DiagnosticSink.NONE }
        repeat(2) {
            assertThrows(Exception::class.java) { bootstrap.ensure(context) }
            assertFalse(directory.exists())
            assertFalse(File(context.noBackupFilesDir, "offline-mfi-staging").exists())
        }
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
    }

    @Test fun diagnosticsCannotBlockAuthenticationEvenWhenFactoryOrConsumerThrows() {
        AirPlayPersistence.saveMfiConfiguration(context, MfiTarget.USB_CH341)
        GalaxyAuthenticationBootstrap { error("test-only-factory-error") }.ensure(context)
        GalaxyAuthenticationBootstrap { DiagnosticSink { error("test-only-consumer-error") } }.ensure(context)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
    }

    private fun localFiles(): Map<File, ByteArray> {
        val directory = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        directory.mkdirs()
        return listOf("identity.pk8", "certificate.p7b").associate { name ->
            val bytes = "invalid-test-only-material-$name".toByteArray()
            File(directory, name).also { it.writeBytes(bytes) } to bytes
        }
    }
}
