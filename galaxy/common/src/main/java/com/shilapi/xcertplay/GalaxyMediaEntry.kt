package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** 原厂播放入口只返回当前会话；过期卡片不能自动连接或恢复已经停止的手机会话。 */
internal object GalaxyMediaEntry {
    const val PLAYER_ACTION = "com.ecarx.carplay.action.OPEN_CURRENT_PLAYER"

    fun pendingIntent(context: Context, player: Boolean): PendingIntent = PendingIntent.getActivity(
        context, if (player) 43 else 42,
        Intent(context, GalaxySettingsActivity::class.java).putExtra("page", "home")
            .apply { if (player) action = PLAYER_ACTION }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun handle(intent: Intent, active: Boolean, stopping: Boolean, open: () -> Unit): Boolean {
        if (intent.action != PLAYER_ACTION) return false
        // 消费一次，界面重建或重复 onResume 不再执行跳转。
        intent.action = null
        if (active && !stopping) open()
        return true
    }
}
