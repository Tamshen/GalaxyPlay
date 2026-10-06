package com.shilapi.xcertplay

import android.content.SharedPreferences

/** 与自动连接配置共用一次同步提交，进程突然退出后也能在启动前阻断重试。 */
internal class L7StartupRecovery(private val prefs: SharedPreferences) {
    enum class Exit { CRASH, CLEAN, UNKNOWN }
    val pending get() = prefs.getBoolean("recovery_pending", false)
    val previousPid get() = prefs.getInt("recovery_pid", 0)
    val previousStart get() = prefs.getLong("recovery_started", 0)
    val blocked get() = prefs.getBoolean("recovery_blocked", false)
    val notice get() = prefs.getBoolean("recovery_notice", false)
    val failures get() = prefs.getInt("recovery_failures", 0)
    val lastType get() = prefs.getString("recovery_type", "unknown").orEmpty()
    val lastFrames get() = prefs.getString("recovery_frames", "").orEmpty()

    @Synchronized fun begin(now: Long, pid: Int, exit: Exit): Boolean {
        val automatic = prefs.getBoolean("auto_connect", false)
        val failed = pending && (prefs.getBoolean("recovery_crashed", false) || exit != Exit.CLEAN)
        val count = if (blocked) failures else if (failed) (failures + 1).coerceAtMost(LIMIT) else 0
        val stop = blocked || automatic && count >= LIMIT
        val edit = prefs.edit().putInt("recovery_failures", count)
            .putBoolean("recovery_pending", automatic && !stop).putBoolean("recovery_crashed", false)
            .putLong("recovery_started", now).putInt("recovery_pid", pid)
        if (stop) {
            edit.putBoolean("auto_connect", false).putBoolean("recovery_blocked", true)
            if (!blocked) edit.putBoolean("recovery_notice", true)
        }
        // 写入未确认时，本进程也不得自动重试；调用方保留内存闸门。
        return edit.commit()
    }

    @Synchronized fun arm(now: Long, pid: Int): Boolean {
        if (blocked || !prefs.getBoolean("auto_connect", false)) return true
        if (pending) return true
        return prefs.edit().putBoolean("recovery_pending", true).putBoolean("recovery_crashed", false)
            .putInt("recovery_pid", pid).putLong("recovery_started", now).commit()
    }

    @Synchronized fun recordCrash(error: Throwable) {
        if (blocked || !prefs.getBoolean("auto_connect", false)) return
        // 只保存异常类型和本项目帧，不保存异常消息、参数、设备信息或凭据。
        val causes = generateSequence(error) { it.cause }.take(4).toList()
        val frames = causes.flatMap { it.stackTrace.toList() }.filter { it.className.startsWith("com.shilapi.") }.take(8)
            .joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }.take(1200)
        prefs.edit().putBoolean("recovery_pending", true).putBoolean("recovery_crashed", true)
            .putString("recovery_type", causes.joinToString(" > ") { it.javaClass.name }.take(320))
            .putString("recovery_frames", frames).commit()
    }

    @Synchronized fun healthy() {
        if (blocked || prefs.getBoolean("recovery_crashed", false)) return
        prefs.edit().putBoolean("recovery_pending", false).putBoolean("recovery_crashed", false)
            .putInt("recovery_failures", 0).commit()
    }

    @Synchronized fun setEnabled(value: Boolean, now: Long, pid: Int): Boolean {
        return prefs.edit().putBoolean("auto_connect", value).putBoolean("recovery_blocked", false)
            .putBoolean("recovery_notice", false).putInt("recovery_failures", 0)
            .putBoolean("recovery_crashed", false).putBoolean("recovery_pending", value)
            .putInt("recovery_pid", pid).putLong("recovery_started", now).commit()
    }

    fun acknowledge() { prefs.edit().putBoolean("recovery_notice", false).commit() }

    companion object {
        const val LIMIT = 3
        fun classify(reason: Int, status: Int): Exit = when (reason) {
            4, 5, 6, 7 -> Exit.CRASH // Java、native、ANR、初始化失败。
            2 -> if (status in setOf(6, 7, 8, 11)) Exit.CRASH else Exit.CLEAN
            0, 13 -> Exit.UNKNOWN
            else -> Exit.CLEAN // 主动停止、系统回收、权限变化等不累计为崩溃。
        }
    }
}
