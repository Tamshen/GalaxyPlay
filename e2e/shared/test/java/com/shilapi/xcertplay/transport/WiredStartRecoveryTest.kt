package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.atomic.AtomicBoolean

class WiredStartRecoveryTest {
    private class Loop(val availability: Boolean = true, val closeAfter: Int = 10) {
        var closed = false
        var reads = 0
        var signal = false
        var cancelled = false
        var afterRead: () -> Unit = {}
        val sent = mutableListOf<Iap2Frame>()
        val polls = mutableListOf<Long>()
        val session = mock(Iap2Session::class.java) { call ->
            when (call.method.name) {
                "awaitReady" -> true
                "isClosed" -> closed
                "send" -> { (call.arguments.firstOrNull() as? Iap2Frame)?.let(sent::add); null }
                "recv" -> {
                    polls += call.getArgument<Long>(0)
                    reads++
                    when {
                        reads == 1 -> Iap2Frame(0x1d02, ByteArray(0))
                        reads == 2 && availability -> Iap2Frame(0x4300, ByteArray(0))
                        else -> { signal = true; afterRead(); if (reads >= closeAfter) closed = true; null }
                    }
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        fun run(timeout: Long = 2_000, recovery: (() -> Boolean)? = { signal.also { signal = false } }) =
            Iap2WiredControlClient(session, mock(Iap2MfiAuthenticationClient::class.java)).run(
                Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
                Iap2WiredCarPlayEndpoint(listOf("fe80::2"), 7000, "test", "1", "00:00:00:00:00:01"),
                500, timeout, dataPathRecovery = recovery, isCancelled = { cancelled })
        val starts get() = sent.filter { it.messageId == 0x4301 }
    }

    @Test fun recoveryResendsSameEndpointAtMostThreeTimesWithBoundedPolls() {
        val loop = Loop()
        val result = loop.run()
        assertEquals(4, loop.starts.size)
        assertEquals(4, result.carPlayStartSessionsSent)
        loop.starts.drop(1).forEach { assertArrayEquals(loop.starts.first().payload, it.payload) }
        assertTrue(loop.polls.drop(1).all { it <= 500 })
    }

    @Test fun recoveryCannotInventTheFirstStartRequest() {
        val loop = Loop(availability = false)
        loop.run()
        assertTrue(loop.starts.isEmpty())
    }

    @Test fun normalWritesAndMissingRecoveryPreserveNormalControl() {
        val loop = Loop()
        loop.run(recovery = null)
        assertEquals(1, loop.starts.size)
    }

    @Test fun cancellationDuringRecoveryCheckCannotSendAnotherRequest() {
        val loop = Loop()
        loop.run(recovery = { if (loop.signal) { loop.cancelled = true; true } else false })
        assertEquals(1, loop.starts.size)
    }

    @Test fun expiredOriginalDeadlineCannotBeExtendedByRecovery() {
        val loop = Loop()
        loop.afterRead = { Thread.sleep(60) }
        val result = loop.run(timeout = 50)
        assertEquals(Iap2WiredControlTerminal.TIMED_OUT, result.terminal)
        assertEquals(1, loop.starts.size)
    }
}
