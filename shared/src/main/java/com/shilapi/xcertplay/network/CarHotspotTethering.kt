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

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(context: Context, isCancelled: () -> Boolean, log: (String) -> Unit): Result =
        enable(15_000L, isCancelled, { permitted(context) }, { CarHotspotStatus.isEnabled(context) }) { receiver ->
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
        start: (ResultReceiver) -> Unit,
    ): Result {
        if (isCancelled()) return Result.CANCELLED
        if (isEnabled() == true) return Result.READY
        if (!canWrite()) return Result.PERMISSION_REQUIRED
        if (isEnabled() == null) return Result.UNSUPPORTED
        val response = AtomicInteger(-1)
        try {
            if (isCancelled()) return Result.CANCELLED
            start(object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    response.set(resultCode)
                }
            })
        } catch (error: InvocationTargetException) {
            return if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
        } catch (_: ReflectiveOperationException) {
            return Result.UNSUPPORTED
        } catch (_: SecurityException) {
            return Result.PERMISSION_REQUIRED
        } catch (_: RuntimeException) {
            return Result.FAILED
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isCancelled()) return Result.CANCELLED
            if (isEnabled() == true) return Result.READY
            if (response.get() == 14 || response.get() == 15) return Result.PERMISSION_REQUIRED
            if (response.get() > 0) return Result.FAILED
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) return Result.TIMED_OUT
            try {
                Thread.sleep(minOf(250L, remainingMillis))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
    }
}
