package com.shilapi.xcertplay

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 服务端边界替身；真实 Android Parcel、Binder、后台注册与生命周期检查。 */
internal object GalaxyHardKeyAndroidCheck {
    fun run(context: Context, test: Instrumentation) {
        val registered = CountDownLatch(1); val unregistered = CountDownLatch(1)
        var callback: IBinder? = null
        val remote = object : Binder() {
            init { attachInterface(null, GalaxyHardKeyBridge.SERVICE) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(GalaxyHardKeyBridge.SERVICE)
                callback = data.readStrongBinder()
                reply!!.writeNoException()
                if (code == 2) unregistered.countDown()
                return true
            }
        }
        val boundary = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                check(intent.component == GalaxyHardKeyBridge.COMPONENT)
                connection.onServiceConnected(GalaxyHardKeyBridge.COMPONENT, remote)
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val gate = L7MediaCommandGate()
        val current = AtomicBoolean(true)
        val sent = CopyOnWriteArrayList<Int>()
        val bridge = GalaxyHardKeyBridge(boundary, current::get, { event, key ->
            gate.nativeEvent(event, key)?.let { if (gate.accept(it, "native-key:$key")) sent.add(it) }
        }, gate::clearNative, { if (it == "HARDKEY result=REGISTERED") registered.countDown() })
        fun input(event: Int) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(GalaxyHardKeyBridge.CALLBACK); data.writeInt(200087)
                check(callback!!.transact(event, data, reply, 0)); reply.readException(); check(reply.readInt() == 0)
            } finally { data.recycle(); reply.recycle() }
        }
        try {
            test.runOnMainSync { bridge.start() }
            check(registered.await(4, TimeUnit.SECONDS)) { "NATIVE_REGISTER_TIMEOUT" }
            input(1); input(1)
            check(sent.size == 1)
            check(!gate.accept(sent.single(), "window-key:KEYCODE_MEDIA_NEXT"))
            input(2); current.set(false); input(1)
            bridge.close(); check(unregistered.await(3, TimeUnit.SECONDS)); input(1)
            check(sent.size == 1)
        } finally { bridge.close(); test.waitForIdleSync() }
    }
}
