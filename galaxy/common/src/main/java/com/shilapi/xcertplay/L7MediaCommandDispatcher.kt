package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** 蓝牙等待任务绑定具体手机会话；同一控制器重连也不能把旧播放发给新手机。 */
internal class L7MediaCommandDispatcher(
    private val owner: () -> Any?,
    private val current: () -> Boolean,
    private val accept: (Int, String) -> Boolean,
    private val before: (Int, () -> Unit) -> Unit,
    private val local: (Int) -> Boolean,
    private val send: (Int, Any) -> Boolean,
    private val resume: () -> Unit,
    private val log: (String) -> Unit = L7DebugLog::record,
    private val traceSend: ((Int, Any, L7SteeringTrace) -> Boolean)? = null,
    private val traceBefore: ((Int, () -> Unit, (String) -> Unit) -> Unit)? = null,
) {
    fun dispatch(index: Int, source: String, trace: L7SteeringTrace? = null) {
        val expected = owner()
        val accepted = expected != null && current() && accept(index, source)
        log("Audio: media input source=$source index=$index accepted=$accepted")
        if (!accepted) {
            trace?.step("DROP", when { expected == null -> "NO_SESSION"; !current() -> "STALE_CONTROLLER"; else -> "CROSS_ORIGIN_DUPLICATE" })
            return
        }
        trace?.step("GATE_ACCEPTED")
        trace?.step("WAIT_BEGIN", "BluetoothMediaGuard")
        val action = {
            if (!current() || owner() !== expected) {
                trace?.step("DROP", "STALE_SESSION")
                log("Audio: media command source=$source index=$index drop=STALE_SESSION")
            } else {
                trace?.step("WAIT_END")
                if (index == CarPlayMediaButton.PLAY) resume()
                val consumed = local(index)
                trace?.step("LOCAL", "consumed=$consumed")
                val queued = !consumed && if (trace != null && traceSend != null) traceSend.invoke(index, expected, trace) else send(index, expected)
                if (consumed) trace?.step("LOCAL_CONSUMED")
                else if (queued) trace?.step("PHONE_QUEUED")
                else if (traceSend == null) trace?.step("DROP", "QUEUE_REJECTED")
                log("Audio: media command source=$source index=$index localConsumed=$consumed phoneQueued=$queued")
            }
        }
        if (trace != null && traceBefore != null) traceBefore.invoke(index, action) { trace.step("DROP", it) }
        else before(index, action)
    }
}
