package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyHardKeyBridgeTest {
    private class Boundary : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var connection: ServiceConnection? = null
        var available = true
        var denied = false
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(GalaxyHardKeyBridge.COMPONENT, intent.component)
            if (denied) throw SecurityException()
            connection = conn
            return available
        }
        override fun unbindService(conn: ServiceConnection) { unbound++ }
    }
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.addLast(task) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Remote(descriptor: String = GalaxyHardKeyBridge.SERVICE) : Binder() {
        val codes = mutableListOf<Int>()
        var callback: IBinder? = null
        var registering: () -> Unit = {}
        var reject = false
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(GalaxyHardKeyBridge.SERVICE)
            callback = data.readStrongBinder()
            codes.add(code)
            if (reject) return false
            if (code == 1) registering()
            reply!!.writeNoException()
            return true
        }
        fun input(code: Int, key: Int) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(GalaxyHardKeyBridge.CALLBACK); data.writeInt(key)
                assertTrue(callback!!.transact(code, data, reply, 0)); reply.readException()
                assertEquals(0, reply.readInt())
            } finally { data.recycle(); reply.recycle() }
        }
    }
    private fun scenario(check: (Boundary, Worker, GalaxyHardKeyBridge, MutableList<Pair<Int, Int>>, MutableList<String>, () -> Unit) -> Unit) {
        val app = Boundary(); val worker = Worker(); val events = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>(); var current = true
        val bridge = GalaxyHardKeyBridge(app, { current }, { event, key -> events.add(event to key) }, {}, logs::add, worker)
        try { check(app, worker, bridge, events, logs) { current = false } }
        finally { bridge.close(); worker.drain(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun absentOrDeniedServiceKeepsStandardFallbackWithoutCallingIpc() {
        for (denied in listOf(false, true)) scenario { app, worker, bridge, events, logs, _ ->
            app.available = false; app.denied = denied
            bridge.start(); worker.drain()
            assertTrue(events.isEmpty())
            assertTrue(logs.any { "UNAVAILABLE" in it || "BIND_FAILED" in it })
        }
    }
    @Test fun descriptorMismatchAndRejectedRegistrationCloseSafely() {
        for (mismatch in listOf(false, true)) scenario { app, worker, bridge, events, logs, _ ->
            bridge.start()
            val remote = Remote(if (mismatch) "different" else GalaxyHardKeyBridge.SERVICE).apply { reject = true }
            app.connection!!.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, remote)
            assertTrue(remote.codes.isEmpty())
            worker.drain(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(events.isEmpty()); assertTrue(logs.any { "REGISTER_FAILED" in it })
            assertEquals(1, app.unbound)
        }
    }
    @Test fun realParcelCallbackIsScopedAndUnregisteredExactlyOnce() = scenario { app, worker, bridge, events, _, stale ->
        bridge.start(); val remote = Remote()
        app.connection!!.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, remote)
        worker.drain(); remote.input(1, 200087); remote.input(2, 200087)
        assertEquals(listOf(1 to 200087, 2 to 200087), events)
        stale(); remote.input(1, 200088)
        bridge.close(); worker.drain(); remote.input(1, 200087)
        bridge.close(); worker.drain()
        assertEquals(2, events.size); assertEquals(listOf(1, 2), remote.codes); assertEquals(1, app.unbound)
    }
    @Test fun timeoutDuringRegistrationRejectsLateSuccessAndCleansSubscription() = scenario { app, worker, bridge, events, logs, _ ->
        bridge.start(); val remote = Remote()
        remote.registering = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)) }
        app.connection!!.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, remote)
        worker.drain(); remote.input(1, 200087)
        assertTrue(events.isEmpty()); assertEquals(listOf(1, 2), remote.codes)
        assertTrue(logs.any { "TIMEOUT" in it }); assertFalse(logs.any { "REGISTERED" in it })
    }
    @Test fun disconnectAndLateConnectionCannotRestartClosedAttempt() = scenario { app, worker, bridge, events, _, _ ->
        bridge.start(); val remote = Remote()
        app.connection!!.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, remote); worker.drain()
        app.connection!!.onBindingDied(GalaxyHardKeyBridge.COMPONENT); worker.drain()
        app.connection!!.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, Remote()); worker.drain()
        remote.input(1, 200087)
        assertTrue(events.isEmpty()); assertEquals(listOf(1, 2), remote.codes)
    }
}
