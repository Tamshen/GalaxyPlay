package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.P2pResetReason

/** 系统清理结果转为可执行提示，不把系统忙推断成特定应用占用。 */
internal object GalaxyP2pRecoveryFeedback {
    fun describe(context: Context, reason: P2pResetReason?): String {
        val cause = when (reason) {
            P2pResetReason.SYSTEM_BUSY -> R.string.galaxy_p2p_reset_busy
            P2pResetReason.REMOVE_REJECTED -> R.string.galaxy_p2p_reset_rejected
            P2pResetReason.REMOVE_TIMEOUT -> R.string.galaxy_p2p_reset_timeout
            P2pResetReason.GROUP_REMAINED -> R.string.galaxy_p2p_reset_remaining
            else -> R.string.galaxy_p2p_reset_existing
        }
        return context.getString(R.string.galaxy_p2p_reset_guidance, context.getString(cause))
    }
}
