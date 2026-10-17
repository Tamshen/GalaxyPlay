package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class LockdownPhoneIdentityTest {
    private class Phone(value: String = "<data>AQID</data>") {
        var cancelled = false
        var cancelOnConnect = false
        var cancelOnRead = false
        var offset = 0
        val sent = mutableListOf<ByteArray>()
        private val xml = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>Value</key>$value</dict></plist>".toByteArray()
        private val response = ByteBuffer.allocate(4 + xml.size).putInt(xml.size).put(xml).array()
        val connection = mock(Iap2UsbMuxTcpConnection::class.java) { call ->
            when (call.method.name) {
                "send" -> { sent += call.getArgument<ByteArray>(0); null }
                "recv" -> {
                    val end = minOf(response.size, offset + call.getArgument<Int>(0))
                    response.copyOfRange(offset, end).also { offset = end; if (cancelOnRead) cancelled = true }
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        val host = mock(Iap2UsbMuxHost::class.java) { call ->
            if (call.method.name == "connect") {
                assertTrue(call.getArgument<Long>(1) in 1..500)
                if (cancelOnConnect) cancelled = true
                connection
            } else RETURNS_DEFAULTS.answer(call)
        }
        fun read() = LockdownPairingClient(host).readDevicePublicKey("test", 500) { cancelled }
    }

    @Test fun readsCurrentPhoneKeyAndClosesItsOwnConnection() {
        val phone = Phone()
        assertArrayEquals(byteArrayOf(1, 2, 3), phone.read())
        verify(phone.connection).close()
        assertTrue(phone.sent.any { String(it).contains("DevicePublicKey") })
    }

    @Test fun cancelledConnectCannotSendARequest() {
        val phone = Phone(); phone.cancelOnConnect = true
        try { phone.read(); fail() } catch (_: LockdownPairingException.Cancelled) { }
        assertTrue(phone.sent.isEmpty()); verify(phone.connection).close()
    }

    @Test fun latePublicKeyAfterCancellationCannotBeReturned() {
        val phone = Phone(); phone.cancelOnRead = true
        try { phone.read(); fail() } catch (_: LockdownPairingException.Cancelled) { }
        verify(phone.connection).close()
    }

    @Test fun nonDataAndEmptyKeysAreRejected() {
        for (value in listOf("<string>bad</string>", "<data></data>")) {
            val phone = Phone(value)
            try { phone.read(); fail() } catch (_: LockdownPairingException.InvalidResponse) { }
            verify(phone.connection).close()
        }
    }
}
