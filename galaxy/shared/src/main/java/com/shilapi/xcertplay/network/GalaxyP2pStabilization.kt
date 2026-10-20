package com.shilapi.xcertplay.network

import java.io.InterruptedIOException

/** 仅建组后补写广播元素时留出实验性稳定窗口；不当作手机可见或已接入的证明。 */
internal object GalaxyP2pStabilization {
    fun await(cancelled: () -> Boolean, log: (String) -> Unit,
              nowNs: () -> Long = System::nanoTime, sleep: (Long) -> Unit = Thread::sleep) {
        val started = nowNs()
        runCatching { log("P2P_PREPARE stage=STABILIZING targetMs=2000 reason=POST_CREATE_CHANGED airBroadcast=UNVERIFIED") }
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw InterruptedIOException("P2P preparation cancelled")
            val left = 2_000_000_000L - (nowNs() - started)
            if (left <= 0) break
            sleep(minOf(50, (left / 1_000_000).coerceAtLeast(1)))
        }
        runCatching { log("P2P_PREPARE stage=STABILIZED elapsedMs=${(nowNs() - started) / 1_000_000} airBroadcast=UNVERIFIED") }
    }
}
