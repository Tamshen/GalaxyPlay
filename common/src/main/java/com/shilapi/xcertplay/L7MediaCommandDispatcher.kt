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
) {
    fun dispatch(index: Int, source: String) {
        val expected = owner()
        val accepted = expected != null && current() && accept(index, source)
        log("Audio: media input source=$source index=$index accepted=$accepted")
        if (!accepted || expected == null) return
        before(index) {
            if (!current() || owner() !== expected) {
                log("Audio: media command source=$source index=$index drop=STALE_SESSION")
            } else {
                if (index == CarPlayMediaButton.PLAY) resume()
                val consumed = local(index)
                val queued = !consumed && send(index, expected)
                log("Audio: media command source=$source index=$index localConsumed=$consumed phoneQueued=$queued")
            }
        }
    }
}
