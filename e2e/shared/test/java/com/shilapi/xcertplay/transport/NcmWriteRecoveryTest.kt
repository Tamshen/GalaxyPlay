package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class NcmWriteRecoveryTest {
    private class Pipe {
        var result: Int? = null
        var block: (() -> Unit)? = null
        val connection = mock(UsbDeviceConnection::class.java) { call ->
            if (call.method.name == "bulkTransfer") {
                block?.invoke()
                result ?: call.getArgument<Int>(2)
            } else RETURNS_DEFAULTS.answer(call)
        }
        val bridge = NcmUsbBridge(connection, mock(UsbEndpoint::class.java), mock(UsbEndpoint::class.java), null, emptyList(), null)
        fun send() = bridge.send(ByteArray(60), 100)
    }

    @Test fun onlyCompleteWriteAfterZeroOrNegativeCountsAsRecovery() {
        val pipe = Pipe()
        try {
            repeat(3) { pipe.send() }
            assertEquals(0, pipe.bridge.writeRecoveryCount)
            pipe.result = -1; repeat(3) { pipe.send() }
            pipe.result = 0; pipe.send()
            assertEquals(0, pipe.bridge.writeRecoveryCount)
            pipe.result = null; repeat(3) { pipe.send() }
            assertEquals(1, pipe.bridge.writeRecoveryCount)
            pipe.result = 0; pipe.send(); pipe.result = null; pipe.send()
            assertEquals(2, pipe.bridge.writeRecoveryCount)
        } finally { pipe.bridge.close() }
    }

    @Test fun partialWriteIsAnErrorAndNotARecovery() {
        val pipe = Pipe()
        try {
            pipe.result = -1; pipe.send(); pipe.result = 1
            try { pipe.send(); fail() } catch (_: IphoneUsbException.DeviceUnavailable) { }
            assertEquals(0, pipe.bridge.writeRecoveryCount)
        } finally { pipe.bridge.close() }
    }

    @Test fun closeWhileAWriteCompletesCannotPublishLateRecovery() {
        val pipe = Pipe(); pipe.result = -1; pipe.send(); pipe.result = null
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        pipe.block = { entered.countDown(); assertTrue(finish.await(2, TimeUnit.SECONDS)) }
        val writer = Thread { pipe.send() }
        writer.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        pipe.bridge.close(); finish.countDown(); writer.join(2_000)
        assertFalse(writer.isAlive); assertTrue(pipe.bridge.isClosed)
        assertEquals(0, pipe.bridge.writeRecoveryCount)
    }
}
