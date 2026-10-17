package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WiredPairingSessionTest {
    private class Attempt(cached: Boolean) {
        var now = 0L
        var current = true
        var identityDelayMs = 0L
        var pairs = 0
        var reads = 0
        var writes = 0
        var clears = 0
        var opens = 0
        var reject = 0
        var cancelPair = false
        var pairBudget = 0L
        val key = byteArrayOf(1)
        val record = record(key)
        var saved: LockdownPairRecord? = if (cached) record else null
        val logs = mutableListOf<String>()
        val stream = mock(BlockingDuplexByteStream::class.java)
        val client = mock(LockdownPairingClient::class.java) { call ->
            when (call.method.name) {
                "readDevicePublicKey" -> { reads++; now += identityDelayMs * 1_000_000L; key.copyOf() }
                "pair" -> {
                    pairs++; pairBudget = call.getArgument(3)
                    if (cancelPair) current = false
                    PairedRecord(record, null)
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        val carKit = mock(LockdownCarKitClient::class.java) { call ->
            if (call.method.name == "open") {
                opens++
                if (reject-- > 0) throw IphoneUsbException.Protocol("InvalidPairRecord")
                stream
            } else RETURNS_DEFAULTS.answer(call)
        }
        val session = WiredPairingSession(client, "test", "test", "test", 1_000, { current },
            { _, guard -> check(guard()); saved }, { value, guard -> check(guard()); writes++; saved = value },
            { _, guard -> check(guard()); clears++; saved = null }, logs::add, { now })
    }
    companion object {
        private fun record(key: ByteArray) = LockdownPairRecord.restore("host", "buid", "00:00:00:00:00:01",
            key, byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))
    }

    @Test fun cacheHitDoesNotGeneratePairOrWriteDiskAndReadsIdentityOnce() {
        val attempt = Attempt(true)
        assertSame(attempt.record, attempt.session.record())
        assertSame(attempt.stream, attempt.session.openCarKit(attempt.carKit))
        assertEquals(1, attempt.reads); assertEquals(0, attempt.pairs); assertEquals(0, attempt.writes)
        assertTrue(attempt.logs.any { "outcome=HIT" in it })
        assertFalse(attempt.logs.any { "stage=PAIR " in it })
    }

    @Test fun cacheMissPairsOnceAndPersistsBeforeOpeningCarKit() {
        val attempt = Attempt(false)
        attempt.session.openCarKit(attempt.carKit)
        assertEquals(1, attempt.pairs); assertEquals(1, attempt.writes); assertEquals(1, attempt.opens)
        val save = attempt.logs.indexOfFirst { "stage=PAIR_SAVE" in it }
        assertTrue(save >= 0 && save < attempt.logs.indexOfFirst { "stage=CARKIT_OPEN" in it })
    }

    @Test fun wrongCachedKeyIsNotUsed() {
        val attempt = Attempt(true); attempt.saved = record(byteArrayOf(7))
        attempt.session.record()
        assertEquals(1, attempt.pairs)
        assertTrue(attempt.logs.any { "outcome=INVALID" in it })
    }

    @Test fun rejectedCacheClearsOnlyOnceAndPairsAgainWithTheSameIdentity() {
        val attempt = Attempt(true); attempt.reject = 1
        attempt.session.openCarKit(attempt.carKit)
        assertEquals(1, attempt.clears); assertEquals(1, attempt.pairs); assertEquals(2, attempt.opens)
        assertEquals(1, attempt.reads)
        attempt.reject = 1
        try { attempt.session.openCarKit(attempt.carKit); fail() } catch (_: IphoneUsbException.Protocol) { }
        assertEquals(1, attempt.clears); assertEquals(1, attempt.pairs)
    }

    @Test fun identityQueryCostConsumesTheOriginalPairingBudget() {
        val attempt = Attempt(false); attempt.identityDelayMs = 400
        attempt.session.record()
        assertEquals(600, attempt.pairBudget)
        assertTrue(attempt.logs.any { "stage=PHONE_IDENTITY result=COMPLETED elapsedMs=400" in it })
    }

    @Test fun expiredIdentityLookupCannotProceedToPair() {
        val attempt = Attempt(false); attempt.identityDelayMs = 1_001
        try { attempt.session.record(); fail() } catch (_: LockdownPairingException.TimedOut) { }
        assertEquals(0, attempt.pairs); assertEquals(0, attempt.writes)
    }

    @Test fun cancelledPairCannotPersistOrOpenCarKit() {
        val attempt = Attempt(false); attempt.cancelPair = true
        try { attempt.session.openCarKit(attempt.carKit); fail() } catch (_: LockdownPairingException.Cancelled) { }
        assertEquals(0, attempt.writes); assertEquals(0, attempt.opens)
    }
}
