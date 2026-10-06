package com.shilapi.xcertplay.media

/** 有地址时按类型／地址跨编号重匹配；无地址时必须同时匹配编号、类型与名称。 */
data class NavigationOutputDevice(val id: Int, val type: Int, val address: String, val name: String) {
    fun match(devices: List<NavigationOutputDevice>): NavigationOutputDevice? {
        val matches = devices.filter { candidate ->
            candidate.type == type && if (address.isNotBlank()) candidate.address == address
            else candidate.id == id && candidate.name == name && candidate.address.isBlank()
        }
        return matches.singleOrNull()
    }
}
