package com.shilapi.xcertplay

/** 当前 L7 EAS 服务的音乐缓存接受在线源 6，未包含 CarPlay 源 13；不借用其他应用身份。 */
internal object L7MediaSourcePolicy {
    fun resolve(easSupport: Int?, info: Class<*>): Int {
        if (easSupport != 1) return L7MediaCenterPort.CARPLAY_SOURCE
        val value = info.getField("SOURCE_TYPE_ONLINE").getInt(null)
        check(value == 6) { "SDK_SOURCE_CONTRACT_MISMATCH" }
        return value
    }
}
