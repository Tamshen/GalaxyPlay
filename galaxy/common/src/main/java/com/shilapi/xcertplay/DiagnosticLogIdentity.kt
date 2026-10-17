package com.shilapi.xcertplay

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 进程随机前缀与计数只标识日志事件，不由设备或用户信息派生。 */
internal object DiagnosticLogIdentity {
    private val process = UUID.randomUUID().toString().replace("-", "").map {
        ('g'.code + it.digitToInt(16)).toChar()
    }.joinToString("")
    private val sequence = AtomicLong()
    data class Event(val id: String, val millis: Long, val sequence: Long)
    private val prefix = Regex("^(?:config_ref=cfg_[g-v]{64} )?(?:\\d{2}:\\d{2}:\\d{2}\\.\\d{3}  )?" +
        "diag_event=(evt_[g-v]{32}_([0-9]+)) event_ms=([0-9]{1,16}) ")

    fun next(): String = "evt_${process}_${sequence.incrementAndGet()}"
    private val safeId = Regex("evt_[g-v]{32}_[0-9]{1,19}")
    fun prefix(id: String, millis: Long) = "diag_event=${id.takeIf(safeId::matches) ?: next()} " +
        "event_ms=${millis.takeIf { it in 0..9_999_999_999_999_999L } ?: 0} "
    fun read(line: String): Event? = prefix.find(line)?.let { match ->
        val counter = match.groupValues[2].toLongOrNull() ?: return null
        val millis = match.groupValues[3].toLongOrNull() ?: return null
        Event(match.groupValues[1], millis, counter)
    }
}
