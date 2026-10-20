package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.diagnostics.DiagnosticChannel
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import com.shilapi.xcertplay.diagnostics.DiagnosticSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** 会话独占证据与日志通道；人工反馈携带当时摘要，不依赖初始化日志仍在轮转文件中。 */
internal class GalaxyMediaReportEvidence(
    val id: Long,
    localPlayback: Boolean,
    private val current: () -> Boolean,
    log: (String) -> Unit,
    private val requestCheckpoint: (Long, Long) -> Unit = { _, _ -> },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val synthetic: Boolean = false,
) {
    enum class Event { SUMMARY, OBSERVATION, CHECKPOINT, STALE_CHECKPOINT, STOP, RELEASE }
    enum class Target { CENTER, CLUSTER }
    enum class Comparison { UNTESTED, OTHER_WORKS, OTHER_FAILS }
    enum class Seen { UNOBSERVED, CORRECT, MISSING, STALE, WRONG }
    data class Snapshot(val owner: GalaxyMediaReportEvidence, val revision: Long, val value: CarPlayNowPlaying,
        val artwork: Boolean, val closed: Boolean, val facts: Map<String, Long>)
    private var value = CarPlayNowPlaying()
    private var artwork = false
    private var artworkVersion: String? = null
    private var revision = 0L
    private var closed = false
    private var lastSummary: Long? = null
    private var observation = 0L
    private var focusAt: Long? = null
    private val facts = mutableMapOf("synthetic" to flag(synthetic), "localPlayback" to flag(localPlayback), "ready" to -1L,
        "tokenValid" to -1L, "registered" to 0L, "sourceAccepted" to -1L, "focus" to 0L, "requestResult" to -1L,
        "stateResult" to -1L, "requestCount" to 0L, "stateCount" to 0L, "failures" to 0L, "observedRevision" to -1L,
        "dimService" to 0L, "catalog" to 0L, "sdkProvider" to -1L, "registrationParams" to 0L)
    private val channel = DiagnosticChannel(id, DiagnosticSink { event ->
        val fields = event.metrics.entries.joinToString(" ") { (key, number) -> "$key=${label(key, number)}" }
        log("MEDIA_REPORT session=${event.session} seq=${event.sequence} elapsedMs=${event.elapsedMillis} " +
            "downstream=UNVERIFIED $fields")
    })

    fun activate() { currentEvidence.set(this) }
    @Synchronized fun snapshot() = Snapshot(this, revision, value, artwork, closed, facts.toMap())
    fun available() = active === this && !snapshot().closed && current()

    @Synchronized fun input(next: CarPlayNowPlaying, hasArtwork: Boolean, coverVersion: String? = null) {
        if (closed) return
        if (next.copy(elapsedMillis = null) != value.copy(elapsedMillis = null) || hasArtwork != artwork || coverVersion != artworkVersion ||
            synthetic && next.elapsedMillis != value.elapsedMillis) revision++
        value = next; artwork = hasArtwork; artworkVersion = coverVersion
        val now = clock()
        if (lastSummary == null || now - lastSummary!! >= 30_000) {
            lastSummary = now
            emit(Event.SUMMARY)
        }
    }

    @Synchronized fun ready(ready: Boolean) {
        if (closed) return
        facts["ready"] = flag(ready)
        if (!ready) {
            listOf("tokenValid", "sourceAccepted", "requestResult", "stateResult").forEach { facts[it] = -1L }
            facts["registered"] = 0
            facts["catalog"] = 0
            listOf("catalogPresent", "registeredSourcePresent", "runtimeSourcePresent", "uiSourcePresent", "dimPublish").forEach { facts[it] = -1L }
            facts["focus"] = 0
            focusAt = null
        }
    }
    @Synchronized fun details(values: Map<String, Long>, source: Int) {
        if (closed) return
        facts.putAll(values.filterKeys { it in DETAIL_KEYS })
        facts["source"] = source.toLong()
    }
    @Synchronized fun result(stage: String, accepted: Boolean) {
        if (closed) return
        val field = when (stage) {
            "register" -> "tokenValid"
            "registrationActive" -> "registered"
            "sourceList" -> "sourceAccepted"
            "requestPlay" -> "requestResult"
            "updateState" -> "stateResult"
            else -> return
        }
        facts[field] = flag(accepted)
        if (stage == "requestPlay" || stage == "updateState") {
            val count = if (stage == "requestPlay") "requestCount" else "stateCount"
            facts[count] = facts.getValue(count) + 1
        }
    }
    @Synchronized fun focus(own: Boolean?) {
        if (closed) return
        facts["focus"] = when (own) { null -> 0; true -> 1; false -> 2 }
        focusAt = if (own == null) null else clock()
    }
    @Synchronized fun failure(stage: String, error: Throwable) {
        if (closed) return
        facts["failures"] = facts.getValue("failures") + 1
        facts["failureStage"] = STAGES.indexOf(stage).toLong()
        facts["failureType"] = FAULTS.indexOf(error.javaClass.simpleName).toLong()
    }

    fun observe(expected: Snapshot, target: Target, title: Seen, cover: Seen, playback: Seen, comparison: Comparison = Comparison.UNTESTED): Boolean {
        if (!available() || listOf(title, cover, playback).all { it == Seen.UNOBSERVED }) return false
        val stamp = synchronized(this) {
            if (closed || active !== this || expected.owner !== this || expected.revision != revision) return false
            facts["observedRevision"] = revision
            val stamp = ++observation
            emit(Event.OBSERVATION, mapOf("observation" to stamp, "target" to target.ordinal.toLong(),
                "title" to title.ordinal.toLong(), "cover" to cover.ordinal.toLong(), "playback" to playback.ordinal.toLong(), "comparison" to comparison.ordinal.toLong()))
            stamp
        }
        // 只排队只读焦点核对，不请求播放、切源或等待 Binder 返回。
        runCatching { requestCheckpoint(stamp, expected.revision) }
        return true
    }
    @Synchronized fun checkpoint(observation: Long, expectedRevision: Long) {
        if (closed) return
        emit(if (expectedRevision == revision) Event.CHECKPOINT else Event.STALE_CHECKPOINT,
            mapOf("observation" to observation, "expectedRevision" to expectedRevision))
    }
    @Synchronized fun stop() {
        if (closed) return
        closed = true
        currentEvidence.compareAndSet(this, null)
        emit(Event.STOP)
    }
    @Synchronized fun released() { emit(Event.RELEASE); channel.close() }

    private fun emit(event: Event, extra: Map<String, Long> = emptyMap()) {
        val data = facts + mapOf("phoneKnown" to flag(value.playbackKnown), "playing" to flag(value.playing),
            "titlePresent" to flag(!value.title.isNullOrBlank()), "artworkAvailable" to flag(artwork),
            "focusAgeMs" to (focusAt?.let { clock() - it } ?: -1L),
            "progressMs" to (value.elapsedMillis ?: -1), "closed" to flag(closed)) + extra
        // 通道每条最多十二个数值字段；分片共享会话、曲目版本、事件与反馈编号。
        data.entries.chunked(7).forEachIndexed { part, entries ->
            channel.emit(DiagnosticEvent.Component.MEDIA, DiagnosticEvent.Kind.STATE, DiagnosticEvent.State.UNKNOWN,
                entries.associate { it.toPair() } + mapOf("event" to event.ordinal.toLong(), "revision" to revision,
                    "part" to part.toLong(), "observation" to (extra["observation"] ?: 0L)))
        }
    }

    companion object {
        private val currentEvidence = java.util.concurrent.atomic.AtomicReference<GalaxyMediaReportEvidence?>()
        val active get() = currentEvidence.get()
        val catalogResults = listOf("UNKNOWN", "ADDED", "UPDATED", "PRESENT", "NO_CONTROLLER",
            "INCOMPATIBLE_CONTROLLER", "UNAVAILABLE_CLASS", "UNAVAILABLE_METHOD", "UNAVAILABLE_ACCESS",
            "UNAVAILABLE_CALL", "UNAVAILABLE_LINKAGE", "STALE", "CONFIG_AMBIGUOUS", "CONFIG_UNAVAILABLE", "INVALID_ENTRY", "INVALID_SOURCE",
            "READBACK_CHANGED", "READBACK_MISSING", "READBACK_MISMATCH")
        val serviceResults = listOf("UNKNOWN", "VISIBLE", "NOT_VISIBLE_OR_UNINSTALLED", "QUERY_DENIED", "QUERY_FAILED")
        private val STAGES = listOf("initialize", "register", "sourceList", "requestPlay", "currentSource",
            "queryFocus", "queryFocusAfterRequest", "observationFocus", "prepare", "updateState", "updateProgress")
        private val FAULTS = listOf("ClassNotFoundException", "NoClassDefFoundError", "NoSuchMethodException",
            "NoSuchFieldException", "IllegalStateException", "IllegalArgumentException", "SecurityException",
            "DeadObjectException", "RemoteException", "InvocationTargetException")
        private val DETAIL_KEYS = setOf("sdkProvider", "registrationParams", "catalog", "dimService",
            "dimEnabled", "dimExported", "dimPermissionDeclared", "dimPermissionGranted", "dimPublish",
            "catalogPresent", "registeredSourcePresent", "runtimeSourcePresent", "uiSourcePresent")
        private fun flag(value: Boolean) = if (value) 1L else 0L
        private fun label(key: String, value: Long): String = when (key) {
            "failureStage" -> STAGES.getOrNull(value.toInt())
            "failureType" -> FAULTS.getOrNull(value.toInt())
            "event" -> Event.entries.getOrNull(value.toInt())?.name
            "comparison" -> Comparison.entries.getOrNull(value.toInt())?.name
            "target" -> Target.entries.getOrNull(value.toInt())?.name
            "title", "cover", "playback" -> Seen.entries.getOrNull(value.toInt())?.name
            "focus" -> listOf("UNKNOWN", "OWN", "OTHER").getOrNull(value.toInt())
            "sdkProvider" -> listOf("APP_OR_FRAMEWORK", "com.geely.linkhmi").getOrNull(value.toInt())
            "catalog" -> catalogResults.getOrNull(value.toInt())
            "dimService" -> serviceResults.getOrNull(value.toInt())
            "dimPublish" -> when (value) { 0L -> "WAITING"; 1L -> "CONNECTED"; 2L -> "SENT_TO_FACTORY_SERVICE"; else -> "UNAVAILABLE_OR_FAILED" }
            else -> value.toString()
        } ?: "OTHER"
    }
}
