package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

/** Uses the car's saved hotspot configuration. This never stops or reconfigures the hotspot. */
object CarHotspotTethering {
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context)

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(context: Context, isCancelled: () -> Boolean, log: (String) -> Unit): Result =
        enable(15_000L, isCancelled, { permitted(context) }, { CarHotspotStatus.isEnabled(context) }) { receiver ->
            val service = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }
                .get(context.getSystemService(ConnectivityManager::class.java))
                ?: throw NoSuchMethodException("Connectivity service unavailable")
            service.javaClass.getMethod(
                "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(service, 0, receiver, false, context.packageName)
        }.also { log("car hotspot auto-enable: ${it.diagnostic}") }

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
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isCancelled()) return Result.CANCELLED
            if (isEnabled() == true) return Result.READY
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
