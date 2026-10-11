package com.shilapi.xcertplay

/** QQ 已有本客户端播放权时不重复申请；网易云元数据选源仍需实际播放权。 */
internal object GalaxyOemMediaPublication {
    fun requestPlay(channel: GalaxyMediaReportingChannel.Mode, ownPackage: String,
                    focus: () -> String?, request: () -> Boolean): Boolean =
        if (channel != GalaxyMediaReportingChannel.Mode.CURRENT && focus() == ownPackage) true else request()

    fun beforeState(channel: GalaxyMediaReportingChannel.Mode, localPlayback: Boolean, ownPackage: String,
                    focus: () -> String?, currentSource: () -> Unit) {
        // 来源更新会受原车焦点校验；蓝牙承载与其他来源焦点下只缓存元数据。
        if (channel == GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT && localPlayback && focus() == ownPackage)
            currentSource()
    }
}
