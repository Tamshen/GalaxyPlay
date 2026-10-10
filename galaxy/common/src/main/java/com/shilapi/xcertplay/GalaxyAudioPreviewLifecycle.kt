package com.shilapi.xcertplay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.Closeable

/** 草稿 Context 也绑定真实宿主；离开前台停止试听，销毁宿主关闭弹窗。 */
internal class GalaxyAudioPreviewLifecycle(context: Context, stop: () -> Unit, dismiss: () -> Unit) : Closeable {
    private val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    private val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(owner: Activity) { if (owner === activity) stop() }
        override fun onActivityDestroyed(owner: Activity) { if (owner === activity) dismiss() }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityResumed(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
    init { activity?.application?.registerActivityLifecycleCallbacks(callback) }
    override fun close() { activity?.application?.unregisterActivityLifecycleCallbacks(callback) }
}
