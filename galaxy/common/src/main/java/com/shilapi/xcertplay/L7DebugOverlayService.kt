package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 用户主动开启的日志窗，独立于连接会话；关闭调试不影响 CarPlay。 */
class L7DebugOverlayService : Service() {
    // 与应用内语言一致，避免悬浮按钮沿用车机的其他系统语言。
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: WindowManager
    private var panel: LinearLayout? = null
    private var interfaceContext: Context = this
    private var interfaceDensity = 0
    private lateinit var layout: WindowManager.LayoutParams
    private lateinit var body: LinearLayout
    private lateinit var summary: TextView
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var fold: Button
    private var collapsed = false
    private var paused = false
    private var errorsOnly = false
    private var revision = -1L
    private var lastSummary = ""
    private val tick = object : Runnable {
        override fun run() {
            if (panel == null) return
            if (!Settings.canDrawOverlays(this@L7DebugOverlayService)) {
                L7DebugLog.record("悬浮窗权限已撤销，停止日志窗")
                stopSelf()
                return
            }
            refreshDensity()
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); manager = getSystemService(WindowManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !L7Agreement.canUse(this)) { stopSelf(); return START_NOT_STICKY }
        if (!resources.getBoolean(R.bool.config_l7_product_ui) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel != null) return START_NOT_STICKY
        try {
            showNotification()
            createWindow()
            isRunning = true
            L7DebugLog.record("悬浮日志已开启")
            handler.post(tick)
        } catch (error: Exception) {
            L7DebugLog.record("悬浮日志启动失败 ${error.javaClass.simpleName}")
            Toast.makeText(this, R.string.l7_debug_failed, Toast.LENGTH_LONG).show()
            stopSelf()
        }
        // 系统停止后需用户重新打开，不自动恢复或开机启动调试窗。
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.l7_debug_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30,
            Intent(this, GalaxySettingsActivity::class.java).putExtra("page", "diagnostics"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 31,
            Intent(this, L7DebugOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.app_name) + " · " + getString(R.string.l7_debug_title))
            .setContentText(getString(R.string.l7_debug_notification)).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.l7_debug_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createWindow() {
        interfaceContext = L7UiDensity.wrap(this)
        interfaceDensity = L7UiDensity.value(this)
        val root = LinearLayout(interfaceContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            L7Ui.bind(this) {
                background = L7Ui.rounded(interfaceContext, getColor(R.color.product_ui_surface), 16).apply {
                    setStroke(dp(1), getColor(R.color.product_ui_border))
                }
            }
        }
        val header = LinearLayout(interfaceContext).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = text(getString(R.string.l7_debug_drag), 16).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleFold() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        fold = button(R.string.l7_debug_collapse) { toggleFold() }
        header.addView(fold)
        header.addView(button(R.string.close) { stopSelf() })
        root.addView(header)
        summary = text("", 13).apply {
            L7Ui.text(this, R.color.product_ui_muted)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(summary)
        body = LinearLayout(interfaceContext).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(interfaceContext)
        val pause = button(if (paused) R.string.l7_debug_resume else R.string.l7_debug_pause) { }
        pause.setOnClickListener {
            paused = !paused
            pause.setText(if (paused) R.string.l7_debug_resume else R.string.l7_debug_pause)
            if (!paused) revision = -1
            refresh()
        }
        val filter = button(if (errorsOnly) R.string.l7_debug_all else R.string.l7_debug_errors) { }
        filter.setOnClickListener {
            errorsOnly = !errorsOnly
            filter.setText(if (errorsOnly) R.string.l7_debug_all else R.string.l7_debug_errors)
            refresh(force = true)
        }
        listOf(pause, filter, button(R.string.l7_debug_copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.l7_debug_title), logText.text))
            Toast.makeText(this, R.string.l7_debug_copied, Toast.LENGTH_SHORT).show()
        }, button(R.string.l7_debug_clear) {
            L7DebugLog.buffer.clear(); refresh(force = true)
        }).forEach { controls.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        body.addView(controls)
        logText = text("", 13).apply { typeface = Typeface.MONOSPACE; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        scroll = ScrollView(interfaceContext).apply { addView(logText) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, dp(240)))
        root.addView(body)
        panel = root
        layout = WindowManager.LayoutParams(windowWidth(), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val size = screenSize()
            x = (size.x - width - dp(12)).coerceAtLeast(0); y = (size.y * .55f).toInt()
        }
        attachDrag(title)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampWindow() }
        manager.addView(root, layout)
    }

    private fun refreshDensity() {
        if (interfaceDensity == L7UiDensity.value(this)) return
        val oldPanel = panel ?: return
        val x = layout.x
        val y = layout.y
        val previousLog = logText.text
        val previousSummary = summary.text
        // 缩放只更换日志窗口，保留暂停、过滤、折叠、位置与已展示的内容。
        manager.removeView(oldPanel)
        createWindow()
        layout.x = x
        layout.y = y
        logText.text = previousLog
        summary.text = previousSummary
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        fold.setText(if (collapsed) R.string.l7_debug_expand else R.string.l7_debug_collapse)
        clampWindow(update = true)
    }

    private fun attachDrag(title: View) {
        var startX = 0f; var startY = 0f; var windowX = 0; var windowY = 0; var moved = false
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; windowX = layout.x; windowY = layout.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        layout.x = windowX + dx.toInt(); layout.y = windowY + dy.toInt()
                        clampWindow(update = true)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) view.performClick()
            }
            true
        }
    }

    private fun toggleFold() {
        collapsed = !collapsed
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        fold.setText(if (collapsed) R.string.l7_debug_expand else R.string.l7_debug_collapse)
        if (!collapsed) refresh(force = true)
    }

    private fun refresh(force: Boolean = false) {
        val state = getString(when {
            CarPlayBackgroundSession.active -> R.string.l7_debug_connected
            CarPlayBackgroundSession.hasSession() -> R.string.l7_debug_connecting
            else -> R.string.l7_debug_disconnected
        })
        val snapshot = L7DebugLog.buffer.snapshot(errorsOnly, 180)
        val status = getString(R.string.l7_debug_summary, state,
            getString(if (paused) R.string.l7_debug_paused else R.string.l7_debug_live), snapshot.evicted)
        if (status != lastSummary) { summary.text = status; lastSummary = status }
        if (collapsed || (paused && !force) || (!force && snapshot.revision == revision)) return
        revision = snapshot.revision
        // 最多每半秒绘制一次、每次至多 180 行，避免日志刷屏影响投屏。
        logText.text = snapshot.lines.joinToString("\n").ifEmpty {
            getString(if (errorsOnly) R.string.l7_debug_no_error else R.string.l7_debug_empty)
        }
        scroll.post { if (panel != null) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clampWindow(update: Boolean = false) {
        val root = panel ?: return
        if (!root.isAttachedToWindow) return
        val size = screenSize()
        val position = DebugOverlayPosition.clamp(layout.x, layout.y, root.width, root.height, size.x, size.y)
        val changed = position.x != layout.x || position.y != layout.y
        layout.x = position.x; layout.y = position.y
        if (changed || update) runCatching { manager.updateViewLayout(root, layout) }.onFailure { stopSelf() }
    }

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= 30) return manager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        return Point().also { @Suppress("DEPRECATION") manager.defaultDisplay.getSize(it) }
    }
    private fun windowWidth() = dp(520).coerceAtMost((screenSize().x - dp(16)).coerceAtLeast(1))
    private fun dp(value: Int) = (value * interfaceContext.resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int) = TextView(interfaceContext).apply {
        text = value; textSize = size.toFloat(); L7Ui.text(this)
    }
    private fun button(title: Int, click: () -> Unit) = L7ActionButton(interfaceContext).apply {
        setText(title)
        L7Ui.button(this, compact = true)
        setOnClickListener { click() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.let { root ->
            L7Ui.refresh(root)
            layout.width = windowWidth()
            runCatching { manager.updateViewLayout(root, layout) }.onFailure { stopSelf() }
            root.post { clampWindow() }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        panel?.let { runCatching { manager.removeViewImmediate(it) } }
        panel = null; isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        L7DebugLog.record("悬浮日志已关闭，连接会话保持原状态")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "l7_debug_overlay"
        private const val NOTIFICATION_ID = 30
        private const val ACTION_STOP = "com.ecarx.carplay.STOP_DEBUG_OVERLAY"
        @Volatile var isRunning = false
            private set
        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, L7DebugOverlayService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, L7DebugOverlayService::class.java)) }
    }
}
