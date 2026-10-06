package com.shilapi.xcertplay.orchestration

/** 设备路径仅在内存比较；请求编号隔离重连、重复广播与轮询，不进入公开日志。 */
internal class IphoneUsbPermissionGate {
    data class Request(val id: Long, val device: String)
    private var sequence = 0L
    private var pending: Request? = null

    @Synchronized fun begin(device: String): Request? {
        if (pending?.device == device) return null
        return Request(++sequence, device).also { pending = it }
    }

    @Synchronized fun isPending(request: Request): Boolean = pending == request

    @Synchronized fun complete(device: String, id: Long): Boolean {
        if (pending?.let { it.id == id && it.device == device } != true) return false
        pending = null
        return true
    }

    @Synchronized fun invalidate() { pending = null }
}
