package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class UsbCloseFailuresTest {
    @Test fun ncmCancelAndConnectionFailuresStillCloseTheReadRequest() {
        val connection = mock(UsbDeviceConnection::class.java)
        val request = mock(UsbRequest::class.java)
        `when`(request.cancel()).thenThrow(IllegalStateException())
        doThrow(IllegalStateException()).`when`(connection).close()
        val bridge = NcmUsbBridge(connection, mock(UsbEndpoint::class.java), mock(UsbEndpoint::class.java), null, emptyList(), null)
        ReflectionHelpers.setField(bridge, "readRequest", request)
        try { bridge.close(); fail() } catch (e: IOException) { assertEquals(2, e.suppressed.size) }
        verify(request).close(); verify(connection).close()
        bridge.close(); verify(connection, times(1)).close()
    }

    @Test fun cancellingUsbReadCannotSkipConnectionClose() {
        val connection = mock(UsbDeviceConnection::class.java)
        val request = mock(UsbRequest::class.java)
        `when`(request.cancel()).thenThrow(IllegalStateException())
        val pipe = Iap2UsbSession(connection, mock(UsbEndpoint::class.java), mock(UsbEndpoint::class.java))
        ReflectionHelpers.setField(pipe, "pendingRead", request)
        try { pipe.close(); fail() } catch (_: IOException) { }
        verify(connection).close()
    }

    @Test fun usbmuxStillWaitsForReaderExitWhenPipeCloseThrows() {
        val finished = CountDownLatch(1)
        val reader = Thread { finished.await() }.apply { start() }
        val pipe = mock(Iap2UsbSession::class.java)
        doAnswer { finished.countDown(); throw IllegalStateException() }.`when`(pipe).close()
        val host = Iap2UsbMuxHost::class.java.declaredConstructors.single { it.parameterCount == 3 }
            .apply { isAccessible = true }.newInstance(pipe, 10L, { _: String -> Unit }) as Iap2UsbMuxHost
        ReflectionHelpers.setField(host, "readerThread", reader)
        try { host.close(); fail() } catch (_: IOException) { }
        assertFalse(reader.isAlive)
        host.close(); verify(pipe, times(1)).close()
    }

    @Test fun bothIpv6WorkersAreWaitedForEvenWhenNcmAndTunCloseFail() {
        val ncm = mock(NcmUsbBridge::class.java); val tun = mock(ParcelFileDescriptor::class.java)
        val stopIn = CountDownLatch(1); val stopOut = CountDownLatch(1)
        val inbound = Thread { stopIn.await() }.apply { start() }
        val outbound = Thread { stopOut.await() }.apply { start() }
        doAnswer { stopIn.countDown(); throw IllegalStateException() }.`when`(ncm).close()
        doAnswer { stopOut.countDown(); throw IllegalStateException() }.`when`(tun).close()
        val bridge = Ipv6NcmBridge(ncm, tun, ByteArray(6)) { }
        ReflectionHelpers.getField<AtomicBoolean>(bridge, "running").set(true)
        ReflectionHelpers.setField(bridge, "ncmToTunThread", inbound)
        ReflectionHelpers.setField(bridge, "tunToNcmThread", outbound)
        try { bridge.close(); fail() } catch (e: IOException) { assertEquals(2, e.suppressed.size) }
        assertFalse(inbound.isAlive); assertFalse(outbound.isAlive)
        verify(ncm).close(); verify(tun).close()
    }
}
