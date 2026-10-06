package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class WiredResourcesTest {
    @Test fun cancellationClosesOpeningConnectionAndLateNcmWithoutTouchingNewAttempt() {
        val old = WiredResources(); val next = WiredResources()
        val opening = mock(Closeable::class.java); val late = mock(Closeable::class.java)
        val active = mock(Closeable::class.java)
        assertTrue(old.own("OPEN", opening)); assertTrue(next.own("NCM", active))
        old.close()
        assertFalse(old.transfer(opening, "NCM", late))
        assertTrue(old.awaitClosed(0))
        verify(opening).close(); verify(late).close(); verify(active, never()).close()
        next.close(); verify(active).close()
    }
    @Test fun closingOwnedUsbInterruptsRealMuxHandshakeAndFailureDoesNotSkipOtherResources() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pipe = mock(Iap2UsbSession::class.java)
        doAnswer { release.countDown(); null }.`when`(pipe).close()
        `when`(pipe.read(anyLong())).thenAnswer {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            throw IphoneUsbException.DeviceUnavailable("closed test pipe")
        }
        val failures = mutableListOf<String>()
        val owner = WiredResources { name, _ -> failures.add(name) }
        owner.own("USB", pipe)
        val failed = mock(Closeable::class.java)
        doThrow(IOException()).`when`(failed).close()
        val other = mock(Closeable::class.java)
        owner.own("FAILED", failed); owner.own("OTHER", other)
        val worker = Thread {
            try { Iap2UsbMuxHost.open(pipe) } catch (_: IphoneUsbException) { }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            owner.close(); worker.join(1500)
            assertFalse(worker.isAlive)
            assertEquals(listOf("FAILED"), failures)
            verify(other).close()
        } finally { release.countDown(); worker.join(1500); owner.close() }
    }
    @Test fun lateRunStackAfterControllerCloseReleasesUsbAndNcmWithoutProtocolOrStatus() {
        withController { controller, statuses ->
            val resources = ReflectionHelpers.getField<WiredResources>(controller, "wiredResources")
            controller.close(); assertTrue(controller.awaitClosed(4000))
            val count = statuses.size
            val pipe = mock(Iap2UsbSession::class.java); val ncm = mock(NcmUsbBridge::class.java)
            val generation = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "iphoneGeneration").get()
            controller.javaClass.getDeclaredMethod("runStack", Iap2UsbSession::class.java,
                NcmUsbBridge::class.java, WiredResources::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(controller, pipe, ncm, resources, generation)
            verify(pipe).close(); verify(ncm).close()
            verify(pipe, never()).read(anyLong())
            assertEquals(count, statuses.size)
        }
    }
}
