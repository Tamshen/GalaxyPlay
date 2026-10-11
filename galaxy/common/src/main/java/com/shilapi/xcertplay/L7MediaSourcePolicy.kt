package com.shilapi.xcertplay

/** 当前 L7 EAS 服务的音乐缓存接受在线源 6，未包含旧参考源 13；不借用其他应用身份。 */
internal object L7MediaSourcePolicy {
    fun resolve(easSupport: Int?, info: Class<*>,
                channel: GalaxyMediaReportingChannel.Mode = GalaxyMediaReportingChannel.Mode.CURRENT): Int {
        if (channel == GalaxyMediaReportingChannel.Mode.CURRENT && easSupport != 1)
            return L7MediaCenterPort.CARPLAY_SOURCE
        val local = channel == GalaxyMediaReportingChannel.Mode.LOCAL
        val value = info.getField(if (local) "SOURCE_TYPE_LOCAL" else "SOURCE_TYPE_ONLINE").getInt(null)
        check(value == if (local) 0 else 6) { "SDK_SOURCE_CONTRACT_MISMATCH" }
        return value
    }
}
