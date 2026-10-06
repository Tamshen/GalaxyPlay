package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.host.R

/** 应用退到后台时提供返回入口；不创建或恢复 CarPlay 连接。 */
class L7DesktopNavigationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var floating: L7DesktopWindow
    private var exitDialog: AlertDialog? = null
    private val update = object : Runnable {
        override fun run() { sync(); handler.postDelayed(this, 500) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        floating = L7DesktopWindow(this, ::navigate)
        L7DesktopNavigation.onVisibilityChanged = { sync() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!allowed()) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.l7_desktop_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 41, homeIntent("home"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.l7_desktop_notification))
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(41, notification)
        handler.removeCallbacks(update)
        handler.post(update)
        return START_NOT_STICKY
    }

    private fun allowed() = !L7AppExit.exiting && L7Agreement.canUse(this) &&
        L7DesktopNavigation.enabled(this) && Settings.canDrawOverlays(this)

    private fun sync() {
        if (!allowed()) { floating.hide(); stopSelf(); return }
        if (L7DesktopNavigation.appVisible || exitDialog?.isShowing == true) floating.hide()
        else runCatching { floating.show() }.onFailure {
            L7DebugLog.record("桌面悬浮菜单不可用 ${it.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun homeIntent(page: String) = Intent(this, GalaxySettingsActivity::class.java).putExtra("page", page)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun navigate(target: String) {
        if (!allowed()) return
        when (target) {
            "home" -> startActivity(if (CarPlayBackgroundSession.hasSession())
                Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                else homeIntent("home"))
            "settings" -> startActivity(homeIntent("settings"))
            "car-home" -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "exit" -> exitDialog = L7AppExit.confirm(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floating.hide()
        sync()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        L7DesktopNavigation.onVisibilityChanged = null
        exitDialog?.dismiss()
        floating.hide()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "l7_desktop_navigation" }
}
