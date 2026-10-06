package com.shilapi.xcertplay

import android.app.Application
import com.shilapi.xcertplay.l7.VehicleOutputCapabilities

/** 上游算法测试的能力宿主；Shell/车辆接口仍由各测试替身拦截。 */
class UpstreamVehicleApplication : Application(), VehicleOutputCapabilities {
    override val bydOutputsEnabled = true
}
