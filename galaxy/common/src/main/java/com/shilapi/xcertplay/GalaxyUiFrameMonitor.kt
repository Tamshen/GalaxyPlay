package com.shilapi.xcertplay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Looper
import android.view.Window
import java.lang.ref.WeakReference

/** 默认只保留前台 Activity 的弱引用；手动检测期间才订阅真实 Window 帧，不主动制造绘制。 */
internal object GalaxyUiFrameMonitor : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val counter = GalaxyUiFrameCounter()
    private var application: Application? = null
    private var current = WeakReference<Activity>(null)
    private var enabled = false
    @Volatile private var window: Window? = null
    private var metricsThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    fun install(app: Application) {
        if (application === app) return
        stop()
        application?.unregisterActivityLifecycleCallbacks(this)
        current.clear()
        application = app
        app.registerActivityLifecycleCallbacks(this)
    }
    fun start() {
        if (enabled) return
        metricsThread = HandlerThread("galaxy-ui-frames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        enabled = true
        observe()
    }
    fun stop() {
        enabled = false
        counter.close()
        detach()
        metricsThread?.quitSafely(); metricsThread = null
    }
    fun read() = counter.read()

    private fun observe() {
        detach()
        val activity = current.get()
        val epoch = counter.open(activity != null)
        if (activity == null) return
        // resumed 回调早于首个 DecorView 接入，等当前主线程消息结束后再读真实窗口能力。
        val ref = WeakReference(activity)
        main.post {
            val owner = ref.get()
            if (!enabled || owner == null || current.get() !== owner || counter.read()?.epoch != epoch) return@post
            val target = owner.window
            if (!target.decorView.isHardwareAccelerated) { counter.unavailable(epoch); return@post }
            val callback = Window.OnFrameMetricsAvailableListener { source, _, dropped ->
                if (window === source) counter.frame(epoch, dropped)
            }
            try {
                target.addOnFrameMetricsAvailableListener(callback, Handler(metricsThread!!.looper))
                window = target; listener = callback
            } catch (_: Exception) { counter.unavailable(epoch) }
        }
    }
    private fun detach() {
        main.removeCallbacksAndMessages(null)
        val previous = window; val callback = listener
        window = null; listener = null
        if (previous != null && callback != null)
            runCatching { previous.removeOnFrameMetricsAvailableListener(callback) }
    }
    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        if (enabled) observe()
    }
    override fun onActivityPaused(activity: Activity) {
        if (current.get() !== activity) return
        current.clear()
        if (enabled) observe()
    }
    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
