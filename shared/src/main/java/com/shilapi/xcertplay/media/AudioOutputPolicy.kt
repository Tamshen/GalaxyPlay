package com.shilapi.xcertplay.media

import android.media.AudioAttributes

/** 播放用途与协议角色分开：用户改输出策略时，Siri 仍保有助手的混音优先级。 */
enum class AudioOutputRole(val usage: Int, val contentType: Int, val channels: Int) {
    MEDIA(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, 2),
    ASSISTANT(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH, 1),
    NAVIGATION(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH, 1);

    internal val channel: AudioChannel get() = when (this) {
        MEDIA -> AudioChannel.MEDIA
        ASSISTANT -> AudioChannel.ASSISTANT
        NAVIGATION -> AudioChannel.NAVIGATION
    }
}

/** 0 按原用途内置推荐；1–10 保留旧设置；101–103 明确选择标准用途策略。 */
object AudioOutputPolicy {
    const val BUILTIN = 0
    const val MEDIA = 101
    const val ASSISTANT = 102
    const val NAVIGATION = 103
    val choices: List<Int> = listOf(BUILTIN, MEDIA, ASSISTANT, NAVIGATION) + (1..10)

    fun valid(choice: Int): Boolean = choice == BUILTIN || isLegacy(choice) || choice in MEDIA..NAVIGATION
    fun isLegacy(choice: Int): Boolean = choice in 1..10

    fun usage(role: AudioOutputRole, choice: Int): Int = when (choice) {
        MEDIA -> AudioOutputRole.MEDIA.usage
        ASSISTANT -> AudioOutputRole.ASSISTANT.usage
        NAVIGATION -> AudioOutputRole.NAVIGATION.usage
        else -> role.usage
    }

    internal fun usage(channel: AudioChannel, choice: Int): Int = when (channel) {
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        else -> usage(role(channel), choice)
    }

    internal fun routingChannel(channel: AudioChannel, choice: Int): AudioChannel = when {
        channel == AudioChannel.PHONE -> channel
        choice == MEDIA -> AudioChannel.MEDIA
        choice == ASSISTANT -> AudioChannel.ASSISTANT
        choice == NAVIGATION -> AudioChannel.NAVIGATION
        else -> channel
    }

    private fun role(channel: AudioChannel): AudioOutputRole = when (channel) {
        AudioChannel.MEDIA -> AudioOutputRole.MEDIA
        AudioChannel.ASSISTANT -> AudioOutputRole.ASSISTANT
        AudioChannel.NAVIGATION -> AudioOutputRole.NAVIGATION
        AudioChannel.PHONE -> error("电话不参与三用途试听")
    }
}
