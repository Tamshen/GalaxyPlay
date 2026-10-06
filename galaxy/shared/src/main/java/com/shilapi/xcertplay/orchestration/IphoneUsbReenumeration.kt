package com.shilapi.xcertplay.orchestration

/** 重枚举期间旧描述符不能再次触发配置请求；路径复用须先确认移除或配置已就绪。 */
internal class IphoneUsbReenumeration {
    private var source: String? = null
    private var sourceRemoved = false
    private var transitionCompleted = false
    private val existingOtherDevices = mutableSetOf<String>()

    @Synchronized fun begin(device: String, attachedDevices: Set<String> = setOf(device)) {
        source = device
        sourceRemoved = false
        transitionCompleted = false
        existingOtherDevices.clear()
        existingOtherDevices.addAll(attachedDevices - device)
    }

    @Synchronized fun completeTransition() { transitionCompleted = source != null }
    @Synchronized fun isActive(): Boolean = source != null
    @Synchronized fun isSource(device: String): Boolean = source == device

    @Synchronized fun observe(devices: Set<String>) {
        if (source != null && source !in devices) sourceRemoved = true
        existingOtherDevices.retainAll(devices)
    }

    @Synchronized fun detached(device: String): Boolean {
        if (source != device) return false
        sourceRemoved = true
        return true
    }

    @Synchronized fun accepts(device: String, configurationReady: Boolean): Boolean =
        source != null && transitionCompleted &&
            (if (device == source) sourceRemoved || configurationReady else device !in existingOtherDevices)

    @Synchronized fun clear() {
        source = null
        sourceRemoved = false
        transitionCompleted = false
        existingOtherDevices.clear()
    }
}
