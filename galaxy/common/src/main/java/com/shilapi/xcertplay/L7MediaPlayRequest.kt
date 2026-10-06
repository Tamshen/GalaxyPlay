package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** 仅由媒体 worker 使用；按真实播放转换或显式选源申请，不随歌曲／进度更新抢源。 */
internal class L7MediaPlayRequest(private val maxAttempts: Int = 3) {
    private var phonePlaying: Boolean? = null
    private var explicit = false
    var revision = 0L
        private set
    var attempts = 0
        private set
    var accepted = false
        private set
    val canRequest: Boolean get() = (phonePlaying == true || explicit) && !accepted && attempts < maxAttempts

    fun observe(value: CarPlayNowPlaying) {
        if (!value.playbackKnown) {
            if (phonePlaying != null) {
                phonePlaying = null
                if (!explicit) reset()
            }
            return
        }
        if (phonePlaying == value.playing) return
        // 选源后的播放回报确认同一次操作，不能再发一轮播放权申请。
        if (explicit && (value.playing || phonePlaying == null)) {
            phonePlaying = value.playing
            if (value.playing) explicit = false
            return
        }
        phonePlaying = value.playing
        reset()
    }

    fun select() { reset(); explicit = true }

    fun begin(): Boolean {
        if (!canRequest) return false
        attempts++
        return true
    }

    fun complete(requestRevision: Long, result: Boolean) {
        if (revision == requestRevision) accepted = result
    }

    fun confirm() { accepted = true }

    /** 已取得的控制权被原车接走后，须等待新的播放转换或用户操作。 */
    fun yield() { reset(); attempts = maxAttempts }

    fun reset() { revision++; attempts = 0; accepted = false; explicit = false }
}
