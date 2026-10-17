package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.host.R
import java.util.concurrent.atomic.AtomicBoolean

/** 手动前台检测服务；无窗口权限继续应用内显示，不持久化开关或自动恢复。 */
class GalaxyPerformanceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var monitor: GalaxyPerformanceMonitor? = null
    private val publishLock = Any()
    private var overlay: GalaxyPerformanceOverlay? = null
    private val pending = AtomicBoolean()
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.wrap(base)) }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!L7Agreement.canUse(this) || intent == null || intent.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (monitor == null) {
            if (intent.action != START) { stopSelf(); return START_NOT_STICKY }
            try {
                notification()
                val reader = GalaxyPerformanceReader()
                lateinit var owner: GalaxyPerformanceMonitor
                owner = GalaxyPerformanceMonitor(reader::read, { sample, history ->
                    val permission = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
                    val accepted = synchronized(publishLock) {
                        if (monitor !== owner) false else {
                            GalaxyPerformanceState.latest = sample
                            GalaxyPerformanceState.history = history
                            GalaxyPerformanceState.overlayPermission = permission
                            true
                        }
                    }
                    if (accepted) {
                        if (pending.compareAndSet(false, true)) main.post {
                            pending.set(false)
                            if (monitor === owner) refreshWindow()
                        }
                    }
                })
                monitor = owner
                GalaxyPerformanceState.latest = null; GalaxyPerformanceState.history = emptyList()
                GalaxyPerformanceState.displayFailed = false
                GalaxyPerformanceState.overlayPermission = false
                L7DebugLog.performanceMonitored = true
                com.shilapi.xcertplay.media.GalaxyPerformanceObservation.active = true
                GalaxyPerformanceState.running = owner.start()
                if (!GalaxyPerformanceState.running) stopSelf()
            } catch (_: Exception) { GalaxyPerformanceState.displayFailed = true; stopSelf() }
        }
        if (intent.action == DEEP) monitor?.deep()
        if (intent.action == OVERLAY) GalaxyPerformanceState.overlayWanted = intent.getBooleanExtra("show", true)
        refreshWindow()
        return START_NOT_STICKY
    }

    private fun refreshWindow() {
        val sample = GalaxyPerformanceState.latest
        val wanted = GalaxyPerformanceState.overlayWanted && GalaxyPerformanceState.overlayPermission
        if (sample == null || !wanted) {
            if (GalaxyPerformanceState.overlayVisible) L7DebugLog.record("PERFORMANCE WINDOW visible=false")
            overlay?.close(); overlay = null; GalaxyPerformanceState.overlayVisible = false
            GalaxyPerformanceState.displayFailed = false
            return
        }
        val view = overlay ?: GalaxyPerformanceOverlay(this,
            boundsChanged = { GalaxyPerformanceState.overlayBounds = it }) { monitor?.displayCost(it) }.also { overlay = it }
        val visible = view.update(sample)
        if (visible != GalaxyPerformanceState.overlayVisible)
            L7DebugLog.record("PERFORMANCE WINDOW visible=$visible")
        GalaxyPerformanceState.overlayVisible = visible
        GalaxyPerformanceState.displayFailed = !visible
    }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); refreshWindow() }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        val old = synchronized(publishLock) { monitor.also { monitor = null } }
        old?.close()
        L7DebugLog.performanceMonitored = false
        com.shilapi.xcertplay.media.GalaxyPerformanceObservation.active = false
        main.removeCallbacksAndMessages(null)
        overlay?.close(); overlay = null
        GalaxyPerformanceState.running = false; GalaxyPerformanceState.overlayVisible = false
        super.onDestroy()
    }

    private fun notification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.performance_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 130,
            Intent(this, GalaxySettingsActivity::class.java).putExtra("page", "settings-debug-performance"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 131, Intent(this, GalaxyPerformanceService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.performance_title)).setContentText(getString(R.string.performance_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.performance_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(130, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(130, notification)
    }
    companion object {
        private const val CHANNEL = "galaxy_performance"
        private const val START = "com.ecarx.carplay.PERFORMANCE_START"
        private const val STOP = "com.ecarx.carplay.PERFORMANCE_STOP"
        private const val DEEP = "com.ecarx.carplay.PERFORMANCE_DEEP"
        private const val OVERLAY = "com.ecarx.carplay.PERFORMANCE_OVERLAY"
        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, GalaxyPerformanceService::class.java).setAction(START)) }
        fun stop(context: Context) { context.stopService(Intent(context, GalaxyPerformanceService::class.java)) }
        fun deep(context: Context) { if (GalaxyPerformanceState.running) context.startService(Intent(context, GalaxyPerformanceService::class.java).setAction(DEEP)) }
        fun overlay(context: Context, show: Boolean) {
            GalaxyPerformanceState.overlayWanted = show
            if (GalaxyPerformanceState.running) context.startService(Intent(context, GalaxyPerformanceService::class.java).setAction(OVERLAY).putExtra("show", show))
        }
    }
}
