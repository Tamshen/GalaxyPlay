package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.GalaxySteeringSetupLog.Stage

/** 按已核对的 Android 契约订阅；IPC 与控制发送分离，超时立即撤销回调资格。 */
internal class GalaxyHardKeyBridge(
    context: Context,
    private val current: () -> Boolean,
    private val event: (Int, Int) -> Unit,
    private val lost: () -> Unit,
    private val report: (String) -> Unit = L7DebugLog.logger(context),
    private val worker: java.util.concurrent.Executor = ipc,
    private val diagnostics: GalaxySteeringSetupLog = GalaxySteeringSetupLog(report),
) : Closeable {
    private val app = context.applicationContext
    private var waitingLogged = false
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val cleaned = AtomicBoolean()
    private val cleanupComplete = AtomicBoolean()
    private val unbindComplete = AtomicBoolean()
    private val finished = AtomicBoolean()
    @Volatile private var bound = false
    @Volatile private var remote: IBinder? = null
    @Volatile private var attempted = false
    @Volatile private var registered = false
    @Volatile private var deadline = 0L
    private val timeout = Runnable { if (!registered) fail(Stage.TIMEOUT) }
    private val retry = Runnable { start() }
    private val death = IBinder.DeathRecipient { fail(Stage.BINDER_DIED) }
    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val key = data.readInt()
            if (data.dataAvail() != 0) { record(Stage.MALFORMED_INPUT); return false }
            val active = if (!closed.get() && registered) valid() else null
            val accepted = active == true
            diagnostics.step(if (accepted) Stage.INPUT else Stage.INPUT_DROPPED,
                mapOf("event" to code.toLong(), "code" to key.toLong(), "registered" to if (registered) 1L else 0L,
                    "closed" to if (closed.get()) 1L else 0L) +
                    (active?.let { mapOf("current" to if (it) 1L else 0L) } ?: emptyMap()))
            if (accepted) runCatching { event(code, key) }.onFailure { diagnostics.step(Stage.INPUT_FAILED, error = it) }
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed.get() || !valid()) { close(); return }
            if (remote != null) return
            remote = binder
            record(Stage.CONNECTED)
            submit {
                try {
                    if (closed.get() || !valid()) return@submit
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) { fail(Stage.TIMEOUT); return@submit }
                    check(binder.interfaceDescriptor == SERVICE)
                    record(Stage.DESCRIPTOR_OK)
                    binder.linkToDeath(death, 0)
                    attempted = true
                    record(Stage.REGISTER_BEGIN)
                    registration(binder, 1)
                    if (closed.get() || !valid() || android.os.SystemClock.elapsedRealtime() >= deadline) {
                        fail(Stage.LATE_REGISTER); cleanup()
                    }
                    else { registered = true; main.removeCallbacks(timeout); record(Stage.REGISTERED) }
                } catch (error: Exception) { fail(Stage.REGISTER_FAILED, error); cleanup() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail(Stage.DISCONNECTED)
        override fun onBindingDied(name: ComponentName) = fail(Stage.BINDING_DIED)
        override fun onNullBinding(name: ComponentName) = fail(Stage.NULL_BINDING)
    }

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::start); return }
        if (closed.get() || bound) return
        if (!valid()) { close(); return }
        if (deadline == 0L) {
            diagnostics.step(Stage.START, mapOf("generation" to L7SteeringDiagnostics.store.snapshot().generation, "budgetMs" to 3000L))
            deadline = android.os.SystemClock.elapsedRealtime() + 3000
            main.postDelayed(timeout, 3000)
        }
        if (!lease.compareAndSet(null, this)) {
            if (!waitingLogged) { waitingLogged = true; record(Stage.WAIT_LEASE) }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) fail(Stage.IPC_BUSY)
            else { main.removeCallbacks(retry); main.postDelayed(retry, 100) }
            return
        }
        try {
            record(Stage.BIND_BEGIN)
            bound = app.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
            if (!bound) fail(Stage.UNAVAILABLE) else { record(Stage.BOUND); if (closed.get()) unbind() }
        } catch (error: Exception) { fail(Stage.BIND_FAILED, error) }
    }

    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { fail(Stage.IPC_BUSY) }
    }
    private fun record(result: Stage) = diagnostics.step(result)
    private fun valid() = runCatching(current).getOrDefault(false)
    private fun fail(reason: Stage, error: Throwable? = null) { diagnostics.step(reason, error = error); close() }
    private fun registration(binder: IBinder, code: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE)
            data.writeStrongBinder(callback)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
    private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        try {
            remote?.let { binder ->
                if (attempted) runCatching {
                    record(Stage.UNREGISTER_BEGIN); registration(binder, 2); record(Stage.UNREGISTERED)
                }.onFailure { diagnostics.step(Stage.UNREGISTER_FAILED, error = it) }
                runCatching { binder.unlinkToDeath(death, 0) }
            }
        } finally {
            remote = null; lease.compareAndSet(this, null)
            cleanupComplete.set(true)
            finishDiagnostics()
        }
    }
    private fun unbind() {
        try {
            if (bound) { bound = false; runCatching { app.unbindService(connection); record(Stage.UNBOUND) }
                .onFailure { diagnostics.step(Stage.UNBIND_FAILED, error = it) } }
        } finally { unbindComplete.set(true); finishDiagnostics() }
    }
    private fun finishDiagnostics() {
        if (cleanupComplete.get() && unbindComplete.get() && finished.compareAndSet(false, true)) {
            record(Stage.RELEASED); diagnostics.close()
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        record(Stage.CLOSE_REQUESTED)
        registered = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
        runCatching(lost)
        if (Looper.myLooper() == Looper.getMainLooper()) unbind() else main.post(::unbind)
        if (remote == null) cleanup() else submit(::cleanup)
    }

    companion object {
        val COMPONENT = ComponentName("com.autolink.hardkeyservice", "com.autolink.hardkeyservice.HardKeyAdapterService")
        const val SERVICE = "com.autolink.adapterbinder.IHardKeyService"
        const val CALLBACK = "com.autolink.adapterbinder.IHardKeyCallback"
        private val lease = java.util.concurrent.atomic.AtomicReference<GalaxyHardKeyBridge?>()
        // 驱动 IPC 无法被 Java 超时强制中断；全进程只允许一个工作线程及四个待办。
        private val ipc = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "galaxy-hardkey-ipc").apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }
    }
}
