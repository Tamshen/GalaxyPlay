package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Build
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** 启用 Android 原生热点并等待真实状态；不负责改配置，也不关闭热点。 */
object CarHotspotTethering {
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context) ||
        context.checkSelfPermission("android.permission.TETHER_PRIVILEGED") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 串行处理开启与连接请求，取得锁后再次检查取消。 */
    fun enable(context: Context, isCancelled: () -> Boolean, log: (String) -> Unit): Result =
        enable(15_000L, isCancelled, { permitted(context) }, { CarHotspotStatus.isEnabled(context) },
            { log("car hotspot apState=${CarHotspotStatus.state(context)} $it") }) { receiver ->
            if (Build.VERSION.SDK_INT >= 30) {
                startAndroid11(context, receiver)
                return@enable
            }
            val service = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }
                .get(context.getSystemService(ConnectivityManager::class.java))
                ?: throw NoSuchMethodException("Connectivity service unavailable")
            service.javaClass.getMethod(
                "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(service, 0, receiver, false, context.packageName)
        }.also { log("car hotspot auto-enable: ${it.diagnostic}") }

    private fun startAndroid11(context: Context, receiver: ResultReceiver) {
        val manager = context.getSystemService("tethering") ?: throw NoSuchMethodException("Tethering service unavailable")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { receiver.send(0, null); null }
                "onTetheringFailed" -> { receiver.send(args?.firstOrNull() as? Int ?: 1, null); null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "L7HotspotCallback"
                else -> null
            }
        }
        manager.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackClass)
            .invoke(manager, 0, Executor { it.run() }, callback)
    }

    @Synchronized
    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        log: (String) -> Unit = {},
        start: (ResultReceiver) -> Unit,
    ): Result {
        fun finish(result: Result): Result { log("event=finish result=$result"); return result }
        if (isCancelled()) return finish(Result.CANCELLED)
        val initial = isEnabled()
        log("event=preflight alreadyEnabled=$initial")
        if (initial == true) return finish(Result.READY)
        if (!canWrite()) return finish(Result.PERMISSION_REQUIRED)
        if (initial == null) return finish(Result.UNSUPPORTED)
        val response = AtomicInteger(-1)
        try {
            if (isCancelled()) return finish(Result.CANCELLED)
            log("event=startRequested")
            start(object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    response.set(resultCode)
                    log("event=callback rawCode=$resultCode")
                }
            })
            log("event=startReturned")
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            log("event=startRejected exceptionType=${cause.javaClass.simpleName}")
            return finish(when (cause) {
                is SecurityException -> Result.PERMISSION_REQUIRED
                is ReflectiveOperationException -> Result.UNSUPPORTED
                else -> Result.FAILED
            })
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isCancelled()) return finish(Result.CANCELLED)
            if (isEnabled() == true) {
                log("event=stateConfirmed hotspotEnabled=true")
                return finish(Result.READY)
            }
            if (response.get() == 14 || response.get() == 15) return finish(Result.PERMISSION_REQUIRED)
            if (response.get() > 0) return finish(Result.FAILED)
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) return finish(Result.TIMED_OUT)
            try {
                Thread.sleep(minOf(250L, remainingMillis))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return finish(Result.CANCELLED)
            }
        }
    }
}
