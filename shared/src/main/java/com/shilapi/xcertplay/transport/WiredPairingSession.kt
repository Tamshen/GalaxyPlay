package com.shilapi.xcertplay.transport

import java.security.MessageDigest

/** 一次 USB 尝试共用身份查询与 Pair 预算；缓存只保存配对材料，不保存活动连接。 */
internal class WiredPairingSession(
    private val client: LockdownPairingClient,
    private val label: String,
    private val hostId: String,
    private val systemBuid: String,
    timeoutMillis: Long,
    private val isCurrent: () -> Boolean,
    private val load: (ByteArray, () -> Boolean) -> LockdownPairRecord?,
    private val save: (LockdownPairRecord, () -> Boolean) -> Unit,
    private val clear: (ByteArray, () -> Boolean) -> Unit,
    private val report: (String) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private val deadline = clock() + timeoutMillis * 1_000_000L
    private var phoneKey: ByteArray? = null
    private var currentRecord: LockdownPairRecord? = null
    private var cached = false
    private var repaired = false

    private fun checkCurrent() {
        if (!isCurrent()) throw LockdownPairingException.Cancelled
    }
    private fun remaining(): Long {
        val millis = (deadline - clock()) / 1_000_000L
        if (millis <= 0) throw LockdownPairingException.TimedOut
        return millis
    }
    private fun event(message: String) { runCatching { report(message) } }
    private fun <T> timed(stage: String, operation: () -> T): T {
        checkCurrent()
        remaining()
        val start = clock()
        var succeeded = false
        try {
            val result = operation()
            try { checkCurrent(); remaining() } catch (error: Throwable) {
                if (result is AutoCloseable) runCatching { result.close() }
                throw error
            }
            succeeded = true
            return result
        } finally {
            event("wired stage=$stage result=${if (succeeded) "COMPLETED" else "FAILED"} elapsedMs=${(clock() - start) / 1_000_000L}")
        }
    }

    fun record(): LockdownPairRecord {
        checkCurrent()
        currentRecord?.let { return it }
        val key = phoneKey ?: timed("PHONE_IDENTITY") {
            client.readDevicePublicKey(label, minOf(remaining(), 5_000L)) { !isCurrent() }
        }.also { phoneKey = it.copyOf() }
        val found = timed("PAIR_CACHE") { load(key.copyOf(), isCurrent) }
        val valid = found?.takeIf { MessageDigest.isEqual(it.devicePublicKeyPem, key) }
        event("wired stage=PAIR_CACHE outcome=${if (valid != null) "HIT" else if (found == null) "MISS" else "INVALID"}")
        cached = valid != null
        return (valid ?: newRecord(key)).also { currentRecord = it }
    }

    private fun newRecord(key: ByteArray): LockdownPairRecord {
        val record = timed("PAIR") { client.pair(label, hostId, systemBuid, remaining()) { !isCurrent() }.pairRecord }
        if (!MessageDigest.isEqual(record.devicePublicKeyPem, key))
            throw LockdownPairingException.InvalidResponse("Phone identity changed during pairing")
        timed("PAIR_SAVE") { save(record, isCurrent) }
        return record
    }

    fun openCarKit(carKit: LockdownCarKitClient): BlockingDuplexByteStream {
        var record = record()
        try {
            return timed("CARKIT_OPEN") { carKit.open(record, label) { !isCurrent() } }
        } catch (error: Throwable) {
            if (!cached || repaired || !rejected(error)) throw error
            checkCurrent()
            repaired = true
            event("wired stage=PAIR_CACHE outcome=REJECTED")
            timed("PAIR_CLEAR") { clear(phoneKey!!.copyOf(), isCurrent) }
            record = newRecord(phoneKey!!)
            currentRecord = record
            cached = false
            return timed("CARKIT_OPEN") { carKit.open(record, label) { !isCurrent() } }
        }
    }

    private fun rejected(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", true) || message.contains("InvalidHostID", true)) return true
            cause = cause.cause
        }
        return false
    }
}
