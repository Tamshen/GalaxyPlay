package com.shilapi.xcertplay

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import androidx.core.content.ContextCompat

/** 其他车型仅观察输入，不消费事件、不猜测按键映射；只读取明确的数字字段。 */
internal class VehicleSteeringCapture(
    private val model: () -> L7AudioTemplates.Model,
    private val connected: () -> Boolean,
    private val begin: (String, Int, String) -> L7SteeringTrace,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var windowAt = Long.MIN_VALUE
    private var count = 0
    private var suppressed = 0

    fun key(event: KeyEvent, source: String) {
        // 不采集文字输入、字符载荷或设备标识，包括 ACTION_MULTIPLE 的字符事件。
        if (event.action == KeyEvent.ACTION_MULTIPLE || event.isPrintingKey || event.unicodeChar != 0) return
        observe(source, "code=${event.keyCode} scan=${event.scanCode} action=${event.action} repeat=${event.repeatCount} eventMs=${event.eventTime}",
            if (CarPlayMediaButton.forKeyCode(event.keyCode) != null) "KNOWN_MEDIA" else "UNMAPPED_KEY")
    }

    fun broadcast(intent: Intent) {
        if (intent.action != L7SteeringWheel.ACTION) return
        val type = try { number(intent.extras, "type") } catch (_: Exception) { "unreadable" }
        observe("vehicle-broadcast", "type=$type", if (type == "3" || type == "4") "VOICE_CANDIDATE" else "UNMAPPED_TYPE")
    }

    fun customAction(bundle: Bundle?) {
        observe("oem-custom-action", "code=${number(bundle, Intent.ACTION_MEDIA_BUTTON)} action=${number(bundle, Intent.ACTION_SEND)}",
            "UNMAPPED_CUSTOM_ACTION")
    }

    private fun number(bundle: Bundle?, key: String): String = try {
        @Suppress("DEPRECATION")
        when (val value = bundle?.get(key)) { null -> "missing"; is Int -> value.toString(); else -> "invalid" }
    } catch (_: Exception) { "unreadable" }

    @Synchronized private fun observe(source: String, detail: String, classification: String) {
        if (model() == L7AudioTemplates.Model.L7) return
        val now = clock()
        if (windowAt == Long.MIN_VALUE || now - windowAt >= 1000) {
            windowAt = now
            count = 0
            if (suppressed > 0) begin("vehicle-input-limit", -1, "suppressed=$suppressed").step("RATE_LIMIT", "suppressed=$suppressed")
            suppressed = 0
        }
        if (++count > 40) { suppressed++; return }
        begin(source, -1, "$detail session=${connected()}").step("OBSERVE_ONLY", classification)
    }
}

internal object VehicleSteeringInputLog {
    @Volatile private var capture: VehicleSteeringCapture? = null
    private var app: Context? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (allowed()) capture?.broadcast(intent) }
    }

    @Synchronized fun initialize(context: Context) {
        if (L7AppExit.exiting || registered) return
        val owner = context.applicationContext
        app = owner
        capture = VehicleSteeringCapture({ L7AudioTemplates.model(owner) },
            { L7SteeringDiagnostics.store.snapshot().connected }, L7SteeringDiagnostics::begin)
        runCatching {
            ContextCompat.registerReceiver(owner, receiver, IntentFilter(L7SteeringWheel.ACTION), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }.onFailure { L7SteeringDiagnostics.store.state("vehicleBroadcastObserver", "available=false exception=${it.javaClass.simpleName}") }
    }

    private fun allowed(): Boolean = app?.let { !L7AppExit.exiting && L7Agreement.accepted(it) } == true
    fun key(event: KeyEvent, source: String) { if (allowed()) capture?.key(event, source) }
    fun customAction(bundle: Bundle?) { if (allowed()) capture?.customAction(bundle) }

    @Synchronized fun close() {
        capture = null
        if (registered) runCatching { app?.unregisterReceiver(receiver) }
        registered = false
        app = null
    }
}
