package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.view.View
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 本地测试与真实 Siri 上行分开显示；页面离开或后台立即请求停止，不自动续录。 */
internal class L7VoiceInputDebugPage(private val activity: Activity, parent: LinearLayout,
    private val grant: () -> Unit, onLogs: () -> Unit,
    private val test: L7VoiceInputTest = L7VoiceInputTest(L7VoiceInputTest.SystemAccess(activity))) : Closeable {
    private lateinit var guide: L7DebugGuide
    private var guideLocalStarted: Boolean
        get() = guide.memory.getBoolean("localStarted")
        set(value) { guide.memory.putBoolean("localStarted", value) }
    private var guideRequest: String?
        get() = guide.memory.getString("request")
        set(value) { guide.memory.putString("request", value) }
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = true
    private var disposed = false
    private var sourceIndex = 0
    private var revision = -1L
    private var previous: List<Any?>? = null
    private val permission: L7SettingRow
    private val source: L7SettingRow
    private val toggle: L7SettingRow
    private val level: L7SettingRow
    private val siri: L7SettingRow
    private val uplink: L7SettingRow
    private val details: L7DebugDetails
    private val voiceResults = mutableListOf<L7SettingRow>()
    private var requestId: String? = null
    private var requestOwner: Any? = null
    private var phoneOutcome: Boolean? = null
    private val tick = object : Runnable {
        override fun run() { if (!disposed && foreground) { update(); handler.postDelayed(this, 250) } }
    }

    init {
        guide = L7DebugGuide(activity, parent, "VOICE", listOf(
            L7DebugGuide.Step(R.string.debug_guide_prepare, R.string.debug_guide_prepare_body),
            L7DebugGuide.Step(R.string.debug_guide_local, R.string.debug_guide_local_body, { if (permitted()) toggle else permission },
                { guideLocalStarted && !test.snapshot.busy && test.snapshot.bytes > 0 }, { guideLocalStarted = false }),
            L7DebugGuide.Step(R.string.debug_guide_siri, R.string.debug_guide_siri_body, { siri },
                { requestId != null && requestId != guideRequest && phoneOutcome != null }, { guideRequest = requestId }),
            L7DebugGuide.Step(R.string.debug_guide_logs, R.string.debug_guide_logs_body)
        ))
        L7VoiceDiagnostics.initialize(activity)
        var permissionRow: L7SettingRow? = null
        L7SettingsSection.add(parent, text(R.string.l7_voice_permission), footer = text(R.string.l7_voice_privacy)) { card ->
            permissionRow = L7Components.actionRow(activity, text(R.string.l7_voice_permission),
                text(R.string.l7_voice_permission_hint)) { if (!permitted()) grant() }.also(card::addView)
        }
        permission = permissionRow!!
        var sourceRow: L7SettingRow? = null
        var toggleRow: L7SettingRow? = null
        var levelRow: L7SettingRow? = null
        L7SettingsSection.add(parent, text(R.string.l7_voice_local), footer = text(R.string.l7_voice_local_hint)) { card ->
            sourceRow = L7Components.actionRow(activity, text(R.string.l7_voice_source)) { chooseSource() }.also(card::addView)
            toggleRow = L7Components.actionRow(activity, text(R.string.l7_voice_start)) { toggleTest() }.also(card::addView)
            levelRow = L7SettingRow(activity, text(R.string.l7_voice_level)).also(card::addView)
        }
        source = sourceRow!!; toggle = toggleRow!!; level = levelRow!!
        var siriRow: L7SettingRow? = null
        var uplinkRow: L7SettingRow? = null
        L7SettingsSection.add(parent, text(R.string.l7_voice_carplay), footer = text(R.string.l7_voice_uplink_hint)) { card ->
            siriRow = L7Components.actionRow(activity, text(R.string.l7_voice_siri)) { requestSiri() }.also(card::addView)
            uplinkRow = L7SettingRow(activity, text(R.string.l7_voice_uplink)).also(card::addView)
            voiceResults.add(L7Components.actionRow(activity, text(R.string.l7_voice_result_ok)) { observePhone(true) }.also(card::addView))
            voiceResults.add(L7Components.actionRow(activity, text(R.string.l7_voice_result_bad)) { observePhone(false) }.also(card::addView))
            card.addView(L7Components.actionRow(activity, text(R.string.l7_voice_logs)) { onLogs() })
            card.addView(L7Components.actionRow(activity, text(R.string.l7_voice_clear)) {
                L7VoiceDiagnostics.store.clear(); update()
            })
        }
        siri = siriRow!!; uplink = uplinkRow!!
        details = L7DebugDetails(activity, parent)
        update()
        handler.postDelayed(tick, 250)
    }

    private fun chooseSource() {
        if (test.snapshot.busy || disposed) return
        L7Components.select(activity, text(R.string.l7_voice_source), SOURCES.map { text(it.first) }, sourceIndex,
            text(R.string.language_apply)) { if (!disposed) { sourceIndex = it; update() } }
    }

    private fun toggleTest() {
        if (!foreground || disposed || !L7Agreement.canUse(activity)) return
        if (test.snapshot.busy) test.stop()
        else if (test.start(SOURCES[sourceIndex].second)) { guideLocalStarted = true; requestId = null; requestOwner = null }
        update()
    }

    private fun requestSiri() {
        if (!foreground || disposed || !L7Agreement.canUse(activity) || test.snapshot.busy || !permitted() ||
            requestId != null && phoneOutcome == null &&
                CarPlayBackgroundSession.snapshot()?.controller?.activeMediaSessionOwner() === requestOwner) return
        val session = CarPlayBackgroundSession.snapshot()
        if (session?.controller?.hasActiveSession() != true || session.sink.hasMicrophoneUplink()) return
        val owner = session.controller.activeMediaSessionOwner() ?: return
        val queued = session.controller.requestSiri()
        phoneOutcome = null; uplink.setFeedback("")
        requestId = if (queued) "${android.os.SystemClock.elapsedRealtime()}-${requestSerial.incrementAndGet()}" else null
        requestOwner = if (queued) owner else null
        L7VoiceDiagnostics.store.record("VOICE_SIRI phase=${if (queued) "QUEUED" else "REJECTED"} request=${requestId ?: "none"}")
        siri.setFeedback(text(if (queued) R.string.l7_voice_siri_queued else R.string.l7_voice_siri_failed), !queued)
        update()
    }

    fun permissionResult(granted: Boolean) {
        if (disposed) return
        permission.setFeedback(text(if (granted) R.string.l7_voice_permission_ready else R.string.l7_voice_permission_denied), !granted)
        update()
    }

    fun update() {
        if (disposed) return
        guide.update()
        val state = test.snapshot
        val session = CarPlayBackgroundSession.snapshot()
        val owner = session?.controller?.activeMediaSessionOwner()
        if (requestId != null && (owner !== requestOwner || session?.controller?.hasActiveSession() != true)) {
            requestId = null; requestOwner = null; phoneOutcome = null
            siri.setFeedback(""); uplink.setFeedback("")
        }
        val active = session?.controller?.hasActiveSession() == true
        val capturing = session?.sink?.hasMicrophoneUplink() == true
        val trace = L7VoiceDiagnostics.store.snapshot()
        // 空闲时不重复布局，避免持续触发无障碍更新，也便于系统 UI 检查取得稳定快照。
        val current = listOf(state, active, capturing, permitted(), foreground, sourceIndex, trace.revision,
            requestId, phoneOutcome, session?.controller?.activeMediaSessionOwner())
        if (current == previous) return
        previous = current
        permission.setValue(text(if (permitted()) R.string.l7_voice_granted else R.string.l7_voice_missing))
        permission.isEnabled = !permitted() && foreground
        source.setValue(text(SOURCES[sourceIndex].first))
        source.isEnabled = !state.busy && foreground
        toggle.titleView.text = text(if (state.busy) R.string.l7_voice_stop else R.string.l7_voice_start)
        toggle.isEnabled = foreground && (state.busy || (permitted() && !capturing))
        toggle.setValue(text(PHASES.getValue(state.phase)))
        toggle.setFeedback(when {
            capturing && !state.busy -> text(R.string.l7_voice_occupied)
            state.phase == L7VoiceInputTest.Phase.INTERRUPTED -> text(R.string.l7_voice_interrupted)
            state.phase == L7VoiceInputTest.Phase.FAILED -> text(R.string.l7_voice_retry)
            else -> ""
        }, state.phase == L7VoiceInputTest.Phase.FAILED)
        level.setValue(when {
            state.silenced -> text(R.string.l7_voice_system_muted)
            state.phase == L7VoiceInputTest.Phase.CAPTURING -> activity.getString(R.string.l7_voice_input_level, (state.peak * 100 / 32768).coerceIn(0, 100))
            state.bytes > 0 -> text(R.string.l7_voice_data_received)
            else -> text(R.string.l7_voice_speak_hint)
        })
        level.setFeedback(activity.getString(R.string.l7_voice_elapsed, state.elapsedMs / 1000))
        siri.isEnabled = foreground && active && permitted() && !state.busy && !capturing && (requestId == null || phoneOutcome != null)
        siri.setValue(text(when {
            !active -> R.string.l7_voice_disconnected
            state.busy -> R.string.l7_voice_stop_first
            capturing -> R.string.l7_voice_uplink_active
            !permitted() -> R.string.l7_voice_missing
            else -> R.string.l7_voice_connected
        }))
        uplink.setValue(text(if (!active) R.string.l7_voice_disconnected else if (capturing) R.string.l7_voice_phone_recording else R.string.l7_voice_phone_ready))
        val sameRequest = requestId != null && requestOwner != null && session?.controller?.activeMediaSessionOwner() === requestOwner
        voiceResults.forEach {
            it.isEnabled = sameRequest && foreground && !state.busy
            it.visibility = if (sameRequest) View.VISIBLE else View.GONE
        }
        if (revision == trace.revision) return
        revision = trace.revision
        details.update(activity.getString(R.string.l7_voice_stats, state.elapsedMs, state.bytes, state.rms, state.peak, state.zeroPercent) + "\n" +
            "source=${SOURCES[sourceIndex].second} route=${state.routeType} reason=${state.reason} code=${state.code}\n" + trace.lines.joinToString("\n"))
    }

    private fun observePhone(responded: Boolean) {
        val session = CarPlayBackgroundSession.snapshot()
        val request = requestId ?: return
        if (disposed || !foreground || requestOwner == null || test.snapshot.busy ||
            session?.controller?.activeMediaSessionOwner() !== requestOwner) return
        L7VoiceDiagnostics.store.record("VOICE_SIRI phase=USER_OBSERVATION request=$request result=${if (responded) "RESPONDED" else "MISSING_OR_ABNORMAL"} origin=MANUAL")
        phoneOutcome = responded
        uplink.setFeedback(text(if (responded) R.string.l7_voice_response_saved else R.string.l7_voice_no_response_saved))
        update()
    }

    fun background() { guide.background(); foreground = false; handler.removeCallbacks(tick); test.stop() }
    fun resume() { if (!disposed) { guide.resume(); foreground = true; handler.removeCallbacks(tick); handler.post(tick) } }
    override fun close() { guide.close(); disposed = true; foreground = false; handler.removeCallbacks(tick); test.close() }
    private fun permitted() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun text(id: Int) = activity.getString(id)

    private companion object {
        val requestSerial = java.util.concurrent.atomic.AtomicLong()
        val SOURCES = listOf(R.string.l7_voice_source_normal to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            R.string.l7_voice_source_raw to MediaRecorder.AudioSource.MIC, R.string.l7_voice_source_call to MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        val PHASES = mapOf(L7VoiceInputTest.Phase.IDLE to R.string.l7_voice_idle,
            L7VoiceInputTest.Phase.STARTING to R.string.l7_voice_starting,
            L7VoiceInputTest.Phase.CAPTURING to R.string.l7_voice_capturing,
            L7VoiceInputTest.Phase.STOPPING to R.string.l7_voice_stopping,
            L7VoiceInputTest.Phase.STOPPED to R.string.l7_voice_stopped,
            L7VoiceInputTest.Phase.COMPLETE to R.string.l7_voice_complete,
            L7VoiceInputTest.Phase.INTERRUPTED to R.string.l7_voice_interrupted,
            L7VoiceInputTest.Phase.FAILED to R.string.l7_voice_failed)
    }
}
