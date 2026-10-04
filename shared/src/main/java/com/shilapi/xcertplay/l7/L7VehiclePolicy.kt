package com.shilapi.xcertplay.l7

/** L7 不接入上游 BYD 厂商服务，旧设置也不能重新启用这些车辆能力。 */
object L7VehiclePolicy {
    const val BYD_FEATURES_ENABLED = false
    // 禁用上游 BYD 自动热点开关；L7 原生热点由自身无线连接预检管理。
    const val AUTOMATIC_HOTSPOT_ENABLED = false
}
