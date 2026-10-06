package com.shilapi.xcertplay.orchestration

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 每次有线尝试独立持有打开中的基础管道；迟到包装对象只能交回旧拥有者。 */
internal class WiredResources(private val failure: (String, Throwable) -> Unit = { _, _ -> }) : Closeable {
    private val lock = Any()
    private val active = LinkedHashMap<AutoCloseable, String>()
    private var closed = false
    private val released = CountDownLatch(1)
    val isClosed get() = synchronized(lock) { closed }
    fun own(name: String, resource: AutoCloseable): Boolean {
        synchronized(lock) {
            if (!closed) { active[resource] = name; return true }
        }
        dispose(name, resource)
        return false
    }
    fun transfer(previous: AutoCloseable, name: String, resource: AutoCloseable): Boolean {
        synchronized(lock) {
            if (!closed && active.containsKey(previous)) {
                val replaced = LinkedHashMap<AutoCloseable, String>()
                active.forEach { (key, label) ->
                    if (key === previous) replaced[resource] = name else replaced[key] = label
                }
                active.clear(); active.putAll(replaced)
                return true
            }
        }
        dispose(name, resource)
        return false
    }
    fun release(resource: AutoCloseable) {
        val name = synchronized(lock) { active.remove(resource) } ?: return
        dispose(name, resource)
    }
    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            active.toList().also { active.clear() }
        }
        try {
            // 先关闭基础管道打断握手，再释放依赖其读写的协议包装，不持锁调用驱动。
            owned.forEach { (resource, name) -> dispose(name, resource) }
        } finally { released.countDown() }
    }
    fun awaitClosed(timeoutMillis: Long): Boolean = try {
        released.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    private fun dispose(name: String, resource: AutoCloseable) {
        try { resource.close() } catch (error: Throwable) { runCatching { failure(name, error) } }
    }
}
