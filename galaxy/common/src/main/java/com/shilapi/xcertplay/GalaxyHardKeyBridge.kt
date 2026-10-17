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

/** 按已核对的 Android 契约订阅；IPC 与控制发送分离，超时立即撤销回调资格。 */
internal class GalaxyHardKeyBridge(
    context: Context,
    private val current: () -> Boolean,
    private val event: (Int, Int) -> Unit,
    private val lost: () -> Unit,
    private val report: (String) -> Unit = L7DebugLog::record,
    private val worker: java.util.concurrent.Executor = ipc,
) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val cleaned = AtomicBoolean()
    @Volatile private var bound = false
    @Volatile private var remote: IBinder? = null
    @Volatile private var attempted = false
    @Volatile private var registered = false
    private val timeout = Runnable { if (!registered) fail("TIMEOUT") }
    private val death = IBinder.DeathRecipient { fail("BINDER_DIED") }
    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val key = data.readInt()
            if (data.dataAvail() != 0) return false
            if (!closed.get() && registered && current()) runCatching { event(code, key) }.onFailure { record("INPUT_FAILED") }
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed.get() || !current()) { close(); return }
            if (remote != null) return
            remote = binder
            submit {
                try {
                    if (closed.get() || !current()) return@submit
                    check(binder.interfaceDescriptor == SERVICE)
                    binder.linkToDeath(death, 0)
                    attempted = true
                    registration(binder, 1)
                    if (closed.get() || !current()) cleanup()
                    else { registered = true; main.removeCallbacks(timeout); record("REGISTERED") }
                } catch (_: Exception) { fail("REGISTER_FAILED"); cleanup() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail("DISCONNECTED")
        override fun onBindingDied(name: ComponentName) = fail("BINDING_DIED")
        override fun onNullBinding(name: ComponentName) = fail("NULL_BINDING")
    }

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::start); return }
        if (closed.get() || bound || !current()) return
        if (!lease.compareAndSet(null, this)) { fail("IPC_BUSY"); return }
        main.postDelayed(timeout, 3000)
        try {
            bound = app.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
            if (!bound) fail("UNAVAILABLE") else if (closed.get()) unbind()
        } catch (_: Exception) { fail("BIND_FAILED") }
    }

    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { fail("IPC_BUSY") }
    }
    private fun record(result: String) { runCatching { report("HARDKEY result=$result") } }
    private fun fail(reason: String) { record(reason); close() }
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
                if (attempted) runCatching { registration(binder, 2) }.onFailure { record("UNREGISTER_FAILED") }
                runCatching { binder.unlinkToDeath(death, 0) }
            }
        } finally { remote = null; lease.compareAndSet(this, null) }
    }
    private fun unbind() {
        if (bound) { bound = false; runCatching { app.unbindService(connection) } }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        registered = false
        main.removeCallbacks(timeout)
        runCatching(lost)
        if (Looper.myLooper() == Looper.getMainLooper()) unbind() else main.post(::unbind)
        if (lease.get() === this || remote != null) submit(::cleanup)
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
