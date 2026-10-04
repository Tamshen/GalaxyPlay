package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build

/** 保留上游共享核心的属性读取边界；L7 最低 Android 10，实际使用音轨返回的属性。 */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured
