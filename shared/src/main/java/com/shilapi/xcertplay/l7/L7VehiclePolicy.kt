package com.shilapi.xcertplay.l7

/** L7 不接入上游 BYD 厂商服务，旧设置也不能重新启用这些车辆能力。 */
object L7VehiclePolicy {
    const val BYD_FEATURES_ENABLED = false
    // L7 热点由用户管理，覆盖安装遗留的上游开关也不能触发自动开启。
    const val AUTOMATIC_HOTSPOT_ENABLED = false
}
