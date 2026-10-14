package com.shilapi.xcertplay.network

/** 地区由用户明确选择；中文语言或车型标识不证明实际使用地点。 */
enum class GalaxyP2pRegion(val policy: P2pChannelPolicy) {
    MAINLAND_CHINA(P2pChannelPolicy(listOf(149, 153, 157, 161, 165) + (1..11))),
    OTHER_CONSERVATIVE(P2pChannelPolicy((1..11).toList()));

    companion object {
        fun fromStored(value: String?): GalaxyP2pRegion = if (value == null) MAINLAND_CHINA
            else entries.firstOrNull { it.name == value } ?: OTHER_CONSERVATIVE
    }
}
