package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable

/** L7 车机语音广播转成既有 CarPlay 语音键；不改原车品牌状态或套用 UCar 按键编号。 */
internal class L7SteeringWheel(
    private val context: Context,
    private val sessionActive: () -> Boolean,
    private val assistantActive: () -> Boolean,
    private val voicePress: () -> Boolean,
) : Closeable {
    private var registered = false
    private var closed = false
    private var lastEvent: Long? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered || intent.action != ACTION) return
            press(intent.getIntExtra("type", 0), "broadcast")
        }
    }

    /** 标准语音键与广播共用去重，避免车机同时发送两种输入时再次切换 Siri。 */
    fun onVoiceKey(): Boolean = press(3, "hardware-key")

    private fun press(type: Int, source: String): Boolean {
        val active = sessionActive()
        val assistant = assistantActive()
        L7DebugLog.record("Control: wheel input source=$source type=$type session=$active assistant=$assistant closed=$closed")
        val reason = when {
            closed -> "CLOSED"
            !active -> "NO_SESSION"
            type !in 3..4 -> "UNSUPPORTED_TYPE"
            type == 4 && !assistant -> "SHORT_PRESS_INACTIVE"
            else -> null
        }
        if (reason != null) { L7DebugLog.record("Control: wheel drop reason=$reason"); return false }
        val now = SystemClock.elapsedRealtime()
        if (lastEvent?.let { now - it < 300 } == true) {
            L7DebugLog.record("Control: wheel drop reason=DUPLICATE")
            return true
        }
        lastEvent = now
        val sent = voicePress()
        L7DebugLog.record("Control: wheel voice source=$source type=$type sent=$sent")
        return sent
    }

    fun start() {
        if (registered || closed) return
        registered = true
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        }.onFailure {
            registered = false
            L7DebugLog.record("Control: wheel receiver unavailable=${it.javaClass.simpleName}")
        }
    }

    override fun close() {
        closed = true
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object { const val ACTION = "action_steering_wheel_controller_event" }
}
