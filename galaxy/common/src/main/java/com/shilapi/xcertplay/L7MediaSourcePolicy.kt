package com.shilapi.xcertplay

/** 当前 L7 EAS 服务的音乐缓存接受在线源 6，未包含旧参考源 13；不借用其他应用身份。 */
internal object L7MediaSourcePolicy {
    fun resolve(easSupport: Int?, info: Class<*>): Int {
        if (easSupport != 1) return L7MediaCenterPort.CARPLAY_SOURCE
        val online = info.getField("SOURCE_TYPE_ONLINE").getInt(null)
        check(online == 6) { "SDK_SOURCE_CONTRACT_MISMATCH" }
        return online
    }
}
