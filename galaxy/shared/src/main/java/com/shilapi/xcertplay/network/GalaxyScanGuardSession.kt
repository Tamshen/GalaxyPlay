package com.shilapi.xcertplay.network

/** 串行 worker 独占；先确认原状态并持久化恢复责任，再改变扫描服务。 */
internal class GalaxyScanGuardSession(
    private val request: (GalaxyScanAction) -> GalaxyScanState,
    private val pending: () -> Boolean,
    private val savePending: (Boolean) -> Boolean,
    private val report: (String) -> Unit,
) {
    private var attempts = 0
    private var active = false
    var state = GalaxyScanState.UNKNOWN
        private set

    fun start() {
        if (active) return
        active = true
        attempts = 0
        check()
    }

    fun check() {
        if (!active) return
        state = request(GalaxyScanAction.INSPECT)
        if (state == GalaxyScanState.ENABLED && attempts < 3) {
            // 已关闭的扫描由其他所有者管理；原状态未知时也不写入、不猜测恢复值。
            if (!pending() && !savePending(true)) { emit("JOURNAL_FAILED"); return }
            attempts++
            state = request(GalaxyScanAction.DISABLE)
            emit("DISABLE_RESULT")
        } else emit(if (state == GalaxyScanState.ENABLED) "REASSERT_LIMIT" else "READBACK")
    }

    fun stop(): Boolean {
        active = false
        return recover()
    }

    fun recover(): Boolean {
        if (active || !pending()) return true
        state = request(GalaxyScanAction.ENABLE)
        val restored = state == GalaxyScanState.ENABLED && savePending(false)
        emit(if (restored) "RESTORED" else "RESTORE_PENDING")
        return restored
    }

    private fun emit(stage: String) = report("stage=$stage service=$state attempts=$attempts " +
        "restorePending=${pending()} evidence=SCANNER_STATE_READBACK")
}
