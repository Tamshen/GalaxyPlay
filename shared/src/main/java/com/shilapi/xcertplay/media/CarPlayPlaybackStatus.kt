package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** 保留手机增量歌曲信息；playbackKnown 区分首次暂停与尚未收到播放状态。 */
data class CarPlayNowPlaying(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artworkTransferId: Int? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val playing: Boolean = false,
    val playbackKnown: Boolean = false,
)

/**
 * 保留 NowPlayingUpdate（0x5001）中申请的字段；缺失字段沿用旧值，显式空字符串清除旧值。
 */
class CarPlayPlaybackStatus {
    var nowPlaying = CarPlayNowPlaying()
        private set

    val playing: Boolean get() = nowPlaying.playing

    /** 字段或已知播放状态发生变化时返回完整状态，否则不重复发布。 */
    fun acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val media = runCatching { body.optionalGroup(MEDIA_ITEM) }.getOrNull()
        val playback = runCatching { body.optionalGroup(PLAYBACK) }.getOrNull()
        val previous = nowPlaying
        val next = previous.copy(
            title = media.updatedString(TITLE, previous.title),
            album = media.updatedString(ALBUM, previous.album),
            artist = media.updatedString(ARTIST, previous.artist),
            artworkTransferId = media.updatedU8(ARTWORK_TRANSFER_ID, previous.artworkTransferId),
            sourceApp = playback.updatedString(SOURCE_APP, previous.sourceApp),
            durationMillis = media.updatedU32(DURATION, previous.durationMillis),
            elapsedMillis = playback.updatedU32(ELAPSED, previous.elapsedMillis),
            playing = playback.updatedStatus(previous.playing),
            playbackKnown = previous.playbackKnown ||
                runCatching { playback?.optionalU8(STATUS) != null }.getOrDefault(false),
        )
        if (next == previous) return null
        nowPlaying = next
        return next
    }

    /** 首次明确状态或播放变化才回传，不能吞掉首次暂停，也不能因进度更新恢复焦点。 */
    fun accept(frame: Iap2Frame): Boolean? {
        val previous = nowPlaying
        val next = acceptUpdate(frame) ?: return null
        return next.playing.takeIf { next.playbackKnown && (!previous.playbackKnown || it != previous.playing) }
    }

    /** 会话结束时清除已知状态，下个会话的首次暂停仍需发布。 */
    fun clear(): Boolean? {
        val wasPlaying = playing
        nowPlaying = nowPlaying.copy(playing = false, playbackKnown = false)
        return if (wasPlaying) false else null
    }

    /** 会话结束时清除歌曲信息与已知播放状态。 */
    fun clearAll(): CarPlayNowPlaying? {
        if (nowPlaying == CarPlayNowPlaying()) return null
        nowPlaying = CarPlayNowPlaying()
        return nowPlaying
    }

    private fun Iap2BodyReader?.updatedString(id: Int, previous: String?): String? {
        if (this == null || !has(id)) return previous
        return runCatching { string(id).trim().ifEmpty { null } }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU32(id: Int, previous: Long?): Long? {
        if (this == null || !has(id)) return previous
        return runCatching { u32(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU8(id: Int, previous: Int?): Int? {
        if (this == null || !has(id)) return previous
        return runCatching { u8(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedStatus(previous: Boolean): Boolean {
        if (this == null || !has(STATUS)) return previous
        return runCatching { u8(STATUS) == STATUS_PLAYING }.getOrElse { previous }
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_ITEM = 0
        private const val PLAYBACK = 1

        private const val TITLE = 1
        private const val DURATION = 4
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val ARTWORK_TRANSFER_ID = 26

        private const val STATUS = 0
        private const val ELAPSED = 1
        private const val SOURCE_APP = 7
        private const val STATUS_PLAYING = 1
    }
}
