package com.shilapi.xcertplay

import android.os.Bundle

/** 只保存步骤与人工操作进度；恢复不会重启录音、连接、上报或上传。 */
internal class L7DebugGuideStore {
    class State(var index: Int = -1, var run: Long = 0, var skipped: Int = 0, val memory: Bundle = Bundle())
    private val states = linkedMapOf<String, State>()
    fun state(kind: String) = states.getOrPut(kind) { State() }
    fun save(out: Bundle) {
        out.putBundle("debug_guides", Bundle().apply {
            states.forEach { (kind, state) -> putBundle(kind, Bundle().apply {
                putInt("index", state.index); putLong("run", state.run); putInt("skipped", state.skipped)
                putBundle("memory", Bundle(state.memory))
            }) }
        })
    }
    fun restore(saved: Bundle?) {
        saved?.getBundle("debug_guides")?.let { value -> value.keySet().take(12).forEach { kind ->
            value.getBundle(kind)?.let { states[kind] = State(it.getInt("index", -1), it.getLong("run"),
                it.getInt("skipped"), it.getBundle("memory") ?: Bundle()) }
        } }
    }
}
