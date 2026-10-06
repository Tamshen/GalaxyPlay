package com.shilapi.xcertplay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.shilapi.xcertplay.host.R

/** 只观察本应用的 Activity，避免应用内菜单与桌面悬浮入口同时出现。 */
internal object L7DesktopNavigation : Application.ActivityLifecycleCallbacks {
    private var registered = false
    private val started = mutableSetOf<Activity>()
    var onVisibilityChanged: (() -> Unit)? = null
    val appVisible get() = started.isNotEmpty()
    private fun prefs(context: Context) = context.getSharedPreferences("l7_floating_navigation", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("desktop", true)

    fun attach(activity: Activity) {
        if (!activity.resources.getBoolean(R.bool.config_l7_product_ui) || registered) return
        activity.application.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    fun ensure(activity: Activity) {
        if (!activity.resources.getBoolean(R.bool.config_l7_product_ui)) return
        if (!enabled(activity) || !L7Agreement.canUse(activity) || L7AppExit.exiting) {
            stop(activity)
            return
        }
        if (Settings.canDrawOverlays(activity)) {
            runCatching { activity.startForegroundService(Intent(activity, L7DesktopNavigationService::class.java)) }
                .onFailure { L7DebugLog.record("桌面悬浮菜单启动失败 ${it.javaClass.simpleName}") }
        }
    }

    fun setEnabled(activity: Activity, enabled: Boolean) {
        prefs(activity).edit().putBoolean("desktop", enabled).apply()
        if (enabled && !Settings.canDrawOverlays(activity)) permission(activity) else ensure(activity)
    }

    fun permission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { L7DebugLog.record("无法打开悬浮权限设置 ${it.javaClass.simpleName}") }
    }

    fun stop(context: Context) { context.stopService(Intent(context, L7DesktopNavigationService::class.java)) }

    override fun onActivityStarted(activity: Activity) { started.add(activity); onVisibilityChanged?.invoke() }
    override fun onActivityStopped(activity: Activity) { started.remove(activity); onVisibilityChanged?.invoke() }
    override fun onActivityDestroyed(activity: Activity) { started.remove(activity); onVisibilityChanged?.invoke() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
