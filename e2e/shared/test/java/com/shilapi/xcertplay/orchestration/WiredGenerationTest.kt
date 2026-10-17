package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class WiredGenerationTest {
    private fun resources(c: CarPlayController): WiredResources = ReflectionHelpers.getField(c, "wiredResources")
    private fun generation(c: CarPlayController): Int = ReflectionHelpers.getField<AtomicInteger>(c, "iphoneGeneration").get()
    private fun record() = LockdownPairRecord.restore("test", "test", "00:00:00:00:00:01",
        byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))
    private fun client(): LockdownPairingClient = mock(LockdownPairingClient::class.java).also {
        `when`(it.readDevicePublicKey(anyString(), anyLong(), any<() -> Boolean>() ?: { false })).thenReturn(byteArrayOf(1))
    }
    private fun pair(c: CarPlayController, client: LockdownPairingClient, owner: WiredResources, gen: Int): Any? {
        val session = CarPlayController::class.java.getDeclaredMethod("pairingSession", LockdownPairingClient::class.java,
            WiredResources::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(c, client, owner, gen)
        return session.javaClass.getDeclaredMethod("record").invoke(session)
    }

    @Test fun pairingResultReturnedAfterInvalidationCannotBeSaved() = withController { controller, _ ->
        val saved = mutableListOf<LockdownPairRecord>()
        ReflectionHelpers.setField(controller, "savePairRecord", { value: LockdownPairRecord, _: () -> Boolean -> saved.add(value); Unit })
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val client = client()
        `when`(client.pair(anyString(), anyString(), anyString(), anyLong(), any<() -> Boolean>() ?: { false })).thenAnswer {
            entered.countDown(); assertTrue(finish.await(2, TimeUnit.SECONDS)); PairedRecord(record(), null)
        }
        val owner = resources(controller); val gen = generation(controller)
        var failure: Throwable? = null
        val worker = Thread { try { pair(controller, client, owner, gen) } catch (e: InvocationTargetException) { failure = e.cause } }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "invalidateIphonePermission")
        finish.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertSame(LockdownPairingException.Cancelled, failure)
        assertTrue(saved.isEmpty())
    }

    @Test fun pairingPollUsesResourceOwnershipAsWellAsControllerClose() = withController { controller, _ ->
        val owner = resources(controller); val gen = generation(controller)
        val client = client()
        `when`(client.pair(anyString(), anyString(), anyString(), anyLong(), any<() -> Boolean>() ?: { false })).thenAnswer {
            val cancelled = it.getArgument<() -> Boolean>(4)
            assertFalse(cancelled())
            owner.close()
            assertTrue(cancelled())
            throw LockdownPairingException.Cancelled
        }
        try { pair(controller, client, owner, gen); fail() }
        catch (e: InvocationTargetException) { assertSame(LockdownPairingException.Cancelled, e.cause) }
    }

    @Test fun oldListenerCannotActivateAReplacementAttempt() = withController { controller, _ ->
        val method = CarPlayController::class.java.getDeclaredMethod("wiredSessionListener", WiredResources::class.java,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        val old = method.invoke(controller, resources(controller), generation(controller)) as AirPlaySessionListener
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "invalidateIphonePermission")
        val session = mock(AirPlaySession::class.java)
        old.onSessionActive(session)
        assertNull(ReflectionHelpers.getField<AirPlaySession?>(controller, "activeSession"))
    }

    @Test fun cancellingDuringVpnBindPreventsLateServiceAttach() = withController { controller, _ ->
        val ready = CountDownLatch(1)
        val service = mock(com.shilapi.xcertplay.network.CarPlayVpnService::class.java)
        ReflectionHelpers.setField(controller, "vpnLatch", ready)
        ReflectionHelpers.setField(controller, "vpnBound", true)
        val owner = resources(controller); val gen = generation(controller)
        val method = CarPlayController::class.java.getDeclaredMethod("attachVpn", NcmUsbBridge::class.java,
            ByteArray::class.java, WiredResources::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        var failure: Throwable? = null
        val worker = Thread {
            try { method.invoke(controller, mock(NcmUsbBridge::class.java), ByteArray(6), owner, gen) }
            catch (e: InvocationTargetException) { failure = e.cause }
        }
        worker.start()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < until) Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING, worker.state)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "invalidateIphonePermission")
        ReflectionHelpers.setField(controller, "vpnService", service)
        ready.countDown(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertSame(LockdownPairingException.Cancelled, failure)
        verifyNoInteractions(service)
        ReflectionHelpers.setField(controller, "vpnService", null)
        ReflectionHelpers.setField(controller, "vpnBound", false)
    }

    @Test fun recoveryEventsAreConsumedOnceAndSuppressedAfterControlOrCancellation() = withController { controller, _ ->
        var count = 0L
        var connected = false
        val ncm = mock(NcmUsbBridge::class.java) { call ->
            if (call.method.name == "getWriteRecoveryCount") count else RETURNS_DEFAULTS.answer(call)
        }
        val service = mock(com.shilapi.xcertplay.network.CarPlayVpnService::class.java) { call ->
            if (call.method.name == "hasWiredControl") connected else RETURNS_DEFAULTS.answer(call)
        }
        ReflectionHelpers.setField(controller, "vpnService", service)
        val method = CarPlayController::class.java.getDeclaredMethod("wiredDataPathRecovery", NcmUsbBridge::class.java,
            com.shilapi.xcertplay.iap2.session.Iap2Session::class.java, WiredResources::class.java,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val signal = method.invoke(controller, ncm,
            mock(com.shilapi.xcertplay.iap2.session.Iap2Session::class.java), resources(controller), generation(controller)) as () -> Boolean
        assertFalse(signal()); count++; assertTrue(signal()); assertFalse(signal())
        connected = true; count++; assertFalse(signal())
        connected = false; assertFalse(signal())
        ReflectionHelpers.setField(controller, "activeSession", mock(AirPlaySession::class.java))
        count++; assertFalse(signal())
        ReflectionHelpers.setField(controller, "activeSession", null)
        assertFalse(signal())
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "invalidateIphonePermission")
        count++; assertFalse(signal())
        ReflectionHelpers.setField(controller, "vpnService", null)
    }

    @Test fun pendingDiskCommitCannotBlockTheCloseRequest() = withController { controller, _ ->
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val closed = CountDownLatch(1)
        ReflectionHelpers.setField(controller, "savePairRecord", { _: LockdownPairRecord, _: () -> Boolean ->
            entered.countDown(); assertTrue(finish.await(2, TimeUnit.SECONDS))
        })
        val client = client()
        `when`(client.pair(anyString(), anyString(), anyString(), anyLong(), any<() -> Boolean>() ?: { false }))
            .thenReturn(PairedRecord(record(), null))
        val owner = resources(controller); val gen = generation(controller)
        val worker = Thread { try { pair(controller, client, owner, gen) } catch (_: InvocationTargetException) { } }
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { controller.close(); closed.countDown() }
        closer.start()
        try { assertTrue("关闭请求不能等待磁盘提交", closed.await(500, TimeUnit.MILLISECONDS)) }
        finally { finish.countDown(); worker.join(2_000); closer.join(2_000) }
        assertFalse(worker.isAlive); assertFalse(closer.isAlive)
    }
}
