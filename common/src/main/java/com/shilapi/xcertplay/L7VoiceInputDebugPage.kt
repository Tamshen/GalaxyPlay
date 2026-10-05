package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 本地测试与真实 Siri 上行分开显示；页面离开或后台立即请求停止，不自动续录。 */
internal class L7VoiceInputDebugPage(private val activity: Activity, parent: LinearLayout,
    private val grant: () -> Unit, onLogs: () -> Unit,
    private val test: L7VoiceInputTest = L7VoiceInputTest(L7VoiceInputTest.SystemAccess(activity))) : Closeable {
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
    private val records: L7SettingsCard
    private val tick = object : Runnable {
        override fun run() { if (!disposed && foreground) { update(); handler.postDelayed(this, 250) } }
    }

    init {
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
            card.addView(L7Components.actionRow(activity, text(R.string.l7_voice_logs)) { onLogs() })
            card.addView(L7Components.actionRow(activity, text(R.string.l7_voice_clear)) {
                L7VoiceDiagnostics.store.clear(); update()
            })
        }
        siri = siriRow!!; uplink = uplinkRow!!
        records = L7SettingsSection.add(parent, text(R.string.l7_voice_events)) { }
        update()
        handler.postDelayed(tick, 250)
    }

    private fun chooseSource() {
        if (test.snapshot.busy || disposed) return
        L7Components.select(activity, text(R.string.l7_voice_source), SOURCES.map { it.first }, sourceIndex,
            text(R.string.language_apply)) { if (!disposed) { sourceIndex = it; update() } }
    }

    private fun toggleTest() {
        if (!foreground || disposed || !L7Agreement.canUse(activity)) return
        if (test.snapshot.busy) test.stop()
        else test.start(SOURCES[sourceIndex].second)
        update()
    }

    private fun requestSiri() {
        if (!foreground || disposed || !L7Agreement.canUse(activity) || test.snapshot.busy || !permitted()) return
        val session = CarPlayBackgroundSession.snapshot()
        if (session?.controller?.hasActiveSession() != true || session.sink.hasMicrophoneUplink()) return
        val queued = session.controller.requestSiri()
        L7VoiceDiagnostics.store.record("VOICE_SIRI phase=${if (queued) "QUEUED" else "REJECTED"}")
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
        val state = test.snapshot
        val session = CarPlayBackgroundSession.snapshot()
        val active = session?.controller?.hasActiveSession() == true
        val capturing = session?.sink?.hasMicrophoneUplink() == true
        val trace = L7VoiceDiagnostics.store.snapshot()
        // 空闲时不重复布局，避免持续触发无障碍更新，也便于系统 UI 检查取得稳定快照。
        val current = listOf(state, active, capturing, permitted(), foreground, sourceIndex, trace.revision)
        if (current == previous) return
        previous = current
        permission.setValue(text(if (permitted()) R.string.l7_voice_granted else R.string.l7_voice_missing))
        permission.isEnabled = !permitted() && foreground
        source.setValue(SOURCES[sourceIndex].first)
        source.isEnabled = !state.busy && foreground
        toggle.titleView.text = text(if (state.busy) R.string.l7_voice_stop else R.string.l7_voice_start)
        toggle.isEnabled = foreground && (state.busy || (permitted() && !capturing))
        toggle.setValue(text(PHASES.getValue(state.phase)))
        toggle.setFeedback(when {
            capturing && !state.busy -> text(R.string.l7_voice_occupied)
            state.phase == L7VoiceInputTest.Phase.INTERRUPTED -> text(R.string.l7_voice_interrupted)
            state.phase == L7VoiceInputTest.Phase.FAILED -> activity.getString(R.string.l7_voice_failure,
                state.reason, state.code?.toString() ?: "none")
            else -> ""
        }, state.phase == L7VoiceInputTest.Phase.FAILED)
        level.setValue(activity.getString(R.string.l7_voice_stats, state.elapsedMs, state.bytes,
            state.rms, state.peak, state.zeroPercent))
        level.setFeedback(activity.getString(R.string.l7_voice_route, state.routeType?.toString() ?: "—",
            text(if (state.silenced) R.string.l7_voice_yes else R.string.l7_voice_no)))
        siri.isEnabled = foreground && active && permitted() && !state.busy && !capturing
        siri.setValue(text(when {
            !active -> R.string.l7_voice_disconnected
            state.busy -> R.string.l7_voice_stop_first
            capturing -> R.string.l7_voice_uplink_active
            !permitted() -> R.string.l7_voice_missing
            else -> R.string.l7_voice_connected
        }))
        uplink.setValue(if (trace.microphone.isEmpty()) text(R.string.l7_voice_empty_uplink)
            else trace.microphone.entries.joinToString(" · ") { "${it.key}=${it.value}" })
        if (revision == trace.revision) return
        revision = trace.revision
        records.removeAllViews()
        if (trace.lines.isEmpty()) records.addView(L7SettingRow(activity, text(R.string.l7_voice_empty)))
        else trace.lines.takeLast(6).asReversed().forEach { records.addView(L7SettingRow(activity, it)) }
    }

    fun background() { foreground = false; handler.removeCallbacks(tick); test.stop() }
    fun resume() { if (!disposed) { foreground = true; handler.removeCallbacks(tick); handler.post(tick) } }
    override fun close() { disposed = true; foreground = false; handler.removeCallbacks(tick); test.close() }
    private fun permitted() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun text(id: Int) = activity.getString(id)

    private companion object {
        val SOURCES = listOf("VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC, "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
