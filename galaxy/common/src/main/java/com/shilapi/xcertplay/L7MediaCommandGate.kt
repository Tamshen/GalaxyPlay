package com.shilapi.xcertplay

import android.os.SystemClock

/** 只抑制不同入口在短窗口内重复转发的同一明确命令；同入口连按和相反操作保留。 */
internal class L7MediaCommandGate(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var lastIndex: Int? = null
    private var lastOrigin: String? = null
    private var lastAt = 0L
    @Synchronized fun accept(index: Int, source: String): Boolean {
        val origin = when {
            source == "mediacenter" -> "OEM"
            source.startsWith("window-key") -> "WINDOW"
            else -> "STANDARD"
        }
        val now = clock()
        if (index == lastIndex && origin != lastOrigin && now - lastAt in 0..80) return false
        lastIndex = index
        lastOrigin = origin
        lastAt = now
        return true
    }
}
