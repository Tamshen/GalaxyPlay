package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** iAP2 手机播放状态独立于音频流存在；首次状态也要回传，增量帧不重置已有状态。 */
class CarPlayPlaybackStatus {
    private var known = false
    var playing = false
        private set

    /** 首次明确状态或之后的变化，其余帧不触发重复焦点恢复。 */
    fun accept(frame: Iap2Frame): Boolean? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val status = runCatching {
            Iap2BodyReader.of(frame).optionalGroup(PLAYBACK)?.optionalU8(STATUS)
        }.getOrNull() ?: return null
        val next = status == STATUS_PLAYING
        if (known && next == playing) return null
        known = true
        playing = next
        return next
    }

    /** 会话结束清除已知状态；下个会话的首次暂停仍需发布。 */
    fun clear(): Boolean? {
        known = false
        if (!playing) return null
        playing = false
        return false
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
    }
}
