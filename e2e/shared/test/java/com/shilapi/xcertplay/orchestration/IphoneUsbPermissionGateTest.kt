package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class IphoneUsbPermissionGateTest {
    @Test fun repeatedDiscoveryKeepsOneOutstandingRequest() {
        val gate = IphoneUsbPermissionGate()
        val request = gate.begin("device-a")!!
        assertNull(gate.begin("device-a"))
        assertTrue(gate.isPending(request))
    }

    @Test fun oldDeviceResultCannotConsumeNewDeviceRequest() {
        val gate = IphoneUsbPermissionGate()
        val old = gate.begin("device-a")!!
        val current = gate.begin("device-b")!!
        assertFalse(gate.complete(old.device, old.id))
        assertFalse(gate.complete(old.device, current.id))
        assertTrue(gate.isPending(current))
        assertTrue(gate.complete(current.device, current.id))
    }

    @Test fun reconnectToSameUsbPathRejectsPreviousGrantAndTimeout() {
        val gate = IphoneUsbPermissionGate()
        val old = gate.begin("device-a")!!
        gate.invalidate()
        val current = gate.begin("device-a")!!
        assertFalse(gate.isPending(old))
        assertFalse(gate.complete(old.device, old.id))
        assertTrue(gate.complete(current.device, current.id))
        assertFalse(gate.complete(current.device, current.id))
    }

    @Test fun broadcastAndPermissionPollConsumeOneResult() {
        val gate = IphoneUsbPermissionGate()
        val request = gate.begin("device-a")!!
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val workers = List(2) {
            Thread {
                start.await()
                if (gate.complete(request.device, request.id)) accepted.incrementAndGet()
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join(1000); assertFalse(it.isAlive) }
        assertEquals(1, accepted.get())
    }

    @Test fun cancelInvalidatesPendingRequestWithoutAcceptingAnythingElse() {
        val gate = IphoneUsbPermissionGate()
        val request = gate.begin("device-a")!!
        gate.invalidate()
        assertFalse(gate.complete(request.device, request.id))
        assertFalse(gate.complete("device-a", 0))
    }
}
