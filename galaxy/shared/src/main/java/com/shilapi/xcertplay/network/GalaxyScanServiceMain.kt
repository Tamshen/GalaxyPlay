package com.shilapi.xcertplay.network

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Android 11 扫描服务控制入口；停止扫描实现，不切换 Wi-Fi、P2P 接口或已有连接。 */
object GalaxyScanServiceMain {
    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(12_000); exitProcess(2) }, "galaxy-scanner-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            require(Process.myUid() in setOf(0, 2000))
            require(args.size == 1)
            if (Build.VERSION.SDK_INT != 30) GalaxyScanState.UNSUPPORTED
            else execute(GalaxyScanAction.valueOf(args[0]))
        } catch (_: Throwable) { GalaxyScanState.UNKNOWN }
        println("${GalaxyScanServiceProtocol.HEADER}|${result.name}")
        exitProcess(0)
    }

    private fun inspect(): GalaxyScanState {
        val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "wifiscanner")
            .redirectErrorStream(true).start()
        return try {
            val state = process.inputStream.bufferedReader().use { GalaxyScanServiceProtocol.scannerState(it.lineSequence()) }
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) state else GalaxyScanState.UNKNOWN
        } finally { process.destroy() }
    }

    private fun execute(action: GalaxyScanAction): GalaxyScanState {
        if (action == GalaxyScanAction.INSPECT) return inspect()
        // CMD_ENABLE / CMD_DISABLE 要求 NETWORK_STACK；本车型维护通道以 root 运行。
        if (Process.myUid() != 0) return GalaxyScanState.DENIED
        val desired = if (action == GalaxyScanAction.ENABLE) GalaxyScanState.ENABLED else GalaxyScanState.DISABLED
        val before = inspect()
        if (before == desired) return before
        if (before !in setOf(GalaxyScanState.ENABLED, GalaxyScanState.DISABLED)) return before
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifiscanner") as? IBinder ?: return GalaxyScanState.UNSUPPORTED
        val service = Class.forName("android.net.wifi.IWifiScanner\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val getter = service.javaClass.getMethod("getMessenger").apply { isAccessible = true }
        val remote = getter.invoke(service) as? Messenger ?: return GalaxyScanState.UNSUPPORTED
        val async = Class.forName("com.android.internal.util.AsyncChannel")
        fun channel(name: String) = async.getField(name).getInt(null)
        val scanner = Class.forName("android.net.wifi.WifiScanner")
        val connected = CountDownLatch(1)
        var accepted = false
        val thread = HandlerThread("galaxy-scanner-control").apply { start() }
        val local = Messenger(Handler(thread.looper) { message ->
            if (message.what == channel("CMD_CHANNEL_FULLY_CONNECTED")) {
                accepted = message.arg1 == 0
                connected.countDown()
            }
            true
        })
        fun send(what: Int) = remote.send(Message.obtain().apply { this.what = what; replyTo = local })
        return try {
            send(channel("CMD_CHANNEL_FULL_CONNECTION"))
            if (!connected.await(1, TimeUnit.SECONDS) || !accepted) return GalaxyScanState.UNKNOWN
            send(scanner.getField(if (action == GalaxyScanAction.ENABLE) "CMD_ENABLE" else "CMD_DISABLE").getInt(null))
            // 此接口是异步消息，没有成功回执；必须回读状态，不能把发送成功当作停扫成功。
            repeat(4) {
                Thread.sleep(150)
                if (inspect() == desired) return desired
            }
            GalaxyScanState.UNKNOWN
        } finally {
            runCatching { send(channel("CMD_CHANNEL_DISCONNECTED")) }
            thread.quitSafely()
        }
    }
}
