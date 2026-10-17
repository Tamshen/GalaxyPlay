package com.shilapi.xcertplay

import android.os.SystemClock

/** 只抑制不同入口在短窗口内重复转发的同一明确命令；同入口连按和相反操作保留。 */
internal class L7MediaCommandGate(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var lastIndex: Int? = null
    private var lastOrigin: String? = null
    private var lastAt = 0L
    private var lastToggle = false
    private val nativeHeld = mutableMapOf<Int, Int?>()

    /** 仅确认的媒体键按下即发；按住期间的迟到标准副本不会再次触发。 */
    @Synchronized fun nativeEvent(event: Int, key: Int): Int? {
        val index = when (key) {
            200085 -> com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE
            200087 -> com.shilapi.xcertplay.airplay.CarPlayMediaButton.NEXT
            200088 -> com.shilapi.xcertplay.airplay.CarPlayMediaButton.PREVIOUS
            else -> return null
        }
        if (event == 1 && !nativeHeld.containsKey(index)) { nativeHeld[index] = null; return index }
        if (event == 2 || event == 3) {
            if (nativeHeld.remove(index) != null && lastOrigin == "NATIVE") lastAt = clock()
        }
        return null
    }
    @Synchronized fun clearNative() { nativeHeld.clear() }

    @Synchronized fun accept(index: Int, source: String): Boolean {
        val origin = when {
            source.startsWith("native-key") -> "NATIVE"
            source == "mediacenter" -> "OEM"
            source.startsWith("window-key") -> "WINDOW"
            else -> "STANDARD"
        }
        val now = clock()
        val toggle = source.contains("PLAY_PAUSE") || source.contains("HEADSETHOOK") || source == "native-key:200085"
        val held = nativeHeld.entries.firstOrNull { (raw, mapped) -> raw == index || mapped == index ||
            toggle && raw == com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE }
        if (origin != "NATIVE" && held?.value != null) return false
        val duplicate = (index == lastIndex || toggle && lastToggle) && origin != lastOrigin && now - lastAt in 0..80
        if (origin == "NATIVE") {
            val raw = if (toggle) com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE else index
            if (nativeHeld.containsKey(raw)) nativeHeld[raw] = index
        }
        if (duplicate) return false
        lastIndex = index
        lastOrigin = origin
        lastAt = now
        lastToggle = toggle
        return true
    }
}
