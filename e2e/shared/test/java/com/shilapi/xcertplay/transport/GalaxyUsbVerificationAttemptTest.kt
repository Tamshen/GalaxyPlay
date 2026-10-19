package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GalaxyUsbVerificationAttemptTest {
    private class Fixture {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val logs = mutableListOf<String>()
        var cancel = false
        var support = true
        var role = 0
        var stageError: String? = null
        var restoreFails = false
        var onActivate: () -> Unit = {}
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {}
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = null
            override fun close() { events += "stream-close" }
        }
        val platform = object : GalaxyUsbVerificationAttempt.Platform {
            private fun event(name: String) { events += name; if (stageError == name) throw IOException("private-device-secret") }
            override fun prepare() = event("prepare")
            override fun checkpoint() = event("checkpoint")
            override fun activate() { event("activate"); onActivate() }
            override fun awaitConfigured(cancelled: () -> Boolean) = event("configured")
            override fun openStream(): BlockingDuplexByteStream { event("open"); return stream }
            override fun restore() { events += "restore"; if (restoreFails) throw IOException("private") }
            override fun close() { events += "lease-close" }
        }
        val phone = object : GalaxyUsbVerificationAttempt.Phone {
            override fun capability(): Boolean { events += "capability"; return support }
            override fun switchRole(): Int { events += "role"; return role }
            override fun close() { events += "phone-close" }
        }
        fun attempt() = GalaxyUsbVerificationAttempt(platform, phone, { cancel }, { logs += it })
    }

    @Test fun unsupportedEnvironmentDoesNotTouchPhoneOrWriteProperties() {
        val f = Fixture().apply { stageError = "prepare" }
        assertThrows(GalaxyUsbVerificationFailure::class.java) { f.attempt().open() }
        assertFalse(f.events.contains("capability")); assertFalse(f.events.contains("role"))
        assertFalse(f.events.contains("checkpoint")); assertFalse(f.events.contains("restore"))
        assertFalse(f.logs.joinToString().contains("private"))
    }
    @Test fun brokenDiagnosticConsumerCannotPreventConnectionOrRestoration() {
        val f = Fixture()
        val attempt = GalaxyUsbVerificationAttempt(f.platform, f.phone, { false }, { throw IllegalStateException() })
        assertSame(f.stream, attempt.open()); attempt.close()
        assertEquals(1, f.events.count { it == "restore" })
    }
    @Test fun unsupportedPhoneDoesNotSwitchOrCheckpoint() {
        val f = Fixture().apply { support = false }
        val e = assertThrows(GalaxyUsbVerificationFailure::class.java) { f.attempt().open() }
        assertEquals(GalaxyUsbVerificationFailure.Reason.PHONE_CAPABILITY_REJECTED, e.reason)
        assertFalse(f.events.contains("checkpoint")); assertFalse(f.events.contains("activate"))
    }
    @Test fun rejectedRoleStillRestoresButDoesNotConfigureGadget() {
        val f = Fixture().apply { role = -1 }
        assertThrows(GalaxyUsbVerificationFailure::class.java) { f.attempt().open() }
        assertTrue(f.events.indexOf("checkpoint") < f.events.indexOf("role"))
        assertFalse(f.events.contains("activate")); assertEquals(1, f.events.count { it == "restore" })
    }
    @Test fun partialActivationAndOpenFailuresRestore() {
        for (stage in listOf("activate", "configured", "open")) {
            val f = Fixture().apply { stageError = stage }
            assertThrows(GalaxyUsbVerificationFailure::class.java) { f.attempt().open() }
            assertEquals(1, f.events.count { it == "restore" })
        }
    }
    @Test fun closeStopsStreamBeforeRestoringAndIsIdempotent() {
        val f = Fixture(); val attempt = f.attempt()
        assertSame(f.stream, attempt.open()); attempt.close(); attempt.close()
        assertTrue(f.events.indexOf("stream-close") < f.events.indexOf("restore"))
        assertEquals(1, f.events.count { it == "restore" })
        assertEquals(1, f.events.count { it == "stream-close" })
    }
    @Test fun cancellationDuringActivationDoesNotOpenOrPublishReady() {
        val f = Fixture().apply { onActivate = { cancel = true } }
        val e = assertThrows(GalaxyUsbVerificationFailure::class.java) { f.attempt().open() }
        assertEquals(GalaxyUsbVerificationFailure.Reason.CANCELLED, e.reason)
        assertFalse(f.events.contains("open")); assertTrue(f.events.contains("restore"))
    }
    @Test fun restorationFailureIsNotHiddenAndRetiredAttemptCannotWriteAgain() {
        val f = Fixture().apply { stageError = "activate"; restoreFails = true }
        val attempt = f.attempt()
        assertEquals(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED,
            assertThrows(GalaxyUsbVerificationFailure::class.java) { attempt.open() }.reason)
        f.restoreFails = false; attempt.close()
        assertEquals(1, f.events.count { it == "restore" })
    }
    @Test fun concurrentCloseMarksCancellationBeforeWaitingForActivation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val f = Fixture().apply { onActivate = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
        val attempt = f.attempt(); val error = AtomicReference<Throwable>()
        val opener = Thread { try { attempt.open() } catch (e: Throwable) { error.set(e) } }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { attempt.close() }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        val waiting = closer.state == Thread.State.BLOCKED
        release.countDown(); opener.join(2000); closer.join(2000)
        assertTrue(waiting)
        assertFalse(opener.isAlive); assertFalse(closer.isAlive)
        assertTrue(error.get() is GalaxyUsbVerificationFailure)
        assertFalse(f.events.contains("open")); assertEquals(1, f.events.count { it == "restore" })
    }
}
