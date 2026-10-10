// SPDX-License-Identifier: AGPL-3.0-only
// 基于 DiPlay 宿主代码适配，保留其上游 DiAuto 来源声明；许可见 docs/第三方许可.md。
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 接入接收端会话与页面生命周期，L7 页面由本项目原生组件组合。 */
class GalaxySettingsActivity : ComponentActivity() {
    internal val debugGuides = L7DebugGuideStore()
    private val l7Ui = true
    private val BG get() = getColor(R.color.product_ui_background)
    private val SURFACE get() = getColor(R.color.product_ui_surface)
    private val BORDER get() = getColor(R.color.product_ui_border)
    private val ACCENT get() = getColor(R.color.product_ui_accent)
    private val TEXT get() = getColor(R.color.product_ui_text)
    private val MUTED get() = getColor(R.color.product_ui_muted)
    private val WARNING get() = getColor(R.color.product_ui_warning)
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var lastSettingsPage = "settings"
    private var pageNavigation: L7ProjectionNavigation? = null
    private var menuExpanded = false
    private var pageContent: FrameLayout? = null
    private var navigationDensity = 0
    private var contentScroll: ScrollView? = null
    private var renderedPage: String? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private var desktopPermissionHint: TextView? = null
    private var homePanel: L7HomePanel? = null
    private var usbButton: Button? = null
    private var connectionRequestPending = false
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var reconnectRow: L7SettingRow? = null
    private var lastRunning: Boolean? = null
    private var phoneDialog: AlertDialog? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private val audioModelConfirmation = L7AudioModelConfirmation(this)
    private var notificationTransport = true
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var adbStatus: TextView? = null
    private var adbCheckGeneration = 0
    private var diagnosticSettings: L7DiagnosticSettings? = null
    private var debugPage: L7DebugPage? = null
    private var steeringDebugPage: L7SteeringDebugPage? = null
    private var reportingTestPage: L7ReportingTestPage? = null
    private var codecProbePage: GalaxyCodecProbePage? = null
    private var fullDebugPage: GalaxyFullDebugPage? = null
    private var pendingFullDebugStart = false
    private var scenarioDebugPage: GalaxyScenarioDebugPage? = null
    private fun codecProbeOccupied() = CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.isStopping()
    private val codecProbeController by lazy { GalaxyCodecProbeController(applicationContext, ::codecProbeOccupied) }
    private var voiceDebugPage: L7VoiceInputDebugPage? = null
    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceDebugPage?.permissionResult(granted)
        fullDebugPage?.permissionResult(granted)
    }
    private val audioTemplateFiles = L7AudioTemplateFiles(this) {
        if (page == "settings-audio") render()
    }
    private val probeState = L7ProbeUiState()
    private val debugTasks by lazy { L7DebugTasks(this) {
        probeState.showCurrent(); page = "settings-debug-results"; render()
    } }
    private val probeExporter = L7ProbeExporter(this)
    private var pendingOverlayStart = false
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val start = pendingOverlayStart
        pendingOverlayStart = false
        if (start && Settings.canDrawOverlays(this)) startDebugOverlay()
        else if (start) toast(getString(R.string.l7_debug_permission_denied))
        render()
    }
    private val debugNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchDebugOverlay()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus(); hotspotSettings?.update(); wirelessHotspotGate.update()
            if (hotspotSettings != null && hotspotError(storedSsid(), storedPassword()) == null) pendingCarHotspotSetup = false
            handler.postDelayed(this, 1000)
        }
    }
    private val hotspotTask by lazy { L7HotspotTask(applicationContext) }
    private val hotspotActions by lazy { L7HotspotActions(this, hotspotTask) }
    private var wiredSettings: L7WiredSettings? = null
    private val wirelessHotspotGate by lazy { L7WirelessHotspotGate(this, hotspotTask) }
    private var hotspotSettings: L7HotspotSettings? = null
    private var wirelessPrerequisites: L7WirelessPrerequisiteView? = null
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L7UiDensity.wrap(AppLocale.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugGuides.restore(savedInstanceState)
        L7SteeringDiagnostics.initialize(applicationContext)
        L7VoiceDiagnostics.initialize(applicationContext)
        if (L7AppExit.exiting) { finish(); return }
        if (!L7Agreement.require(this)) return
        L7DesktopNavigation.attach(this)
        pendingOverlayStart = savedInstanceState?.getBoolean("pending_overlay_start") ?: false
        probeState.filter = savedInstanceState?.getInt("probe_filter") ?: 0
        probeState.query = savedInstanceState?.getString("probe_query").orEmpty()
        probeState.selectedReport = savedInstanceState?.getString("probe_selected_report")
        probeExporter.pendingId = savedInstanceState?.getString("probe_export_id")
        audioTemplateFiles.restore(savedInstanceState)
        if (l7Ui) L7DebugLog.record("GalaxyPlay 打开 version=${version()} Android=${Build.VERSION.RELEASE}")
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        savedInstanceState?.getBundle("scroll_positions")?.let { positions ->
            positions.keySet().forEach { scrollPositions[it] = positions.getInt(it) }
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if (l7Ui) page = L7Routes.normalize(page)
        lastSettingsPage = savedInstanceState?.getString("settings_page")?.let(L7Routes::normalize) ?: "settings"
        menuExpanded = savedInstanceState?.getBoolean("home_menu_expanded") ?: false
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = if (l7Ui) L7Routes.back(page) else "home"; render() }
                else if (pageNavigation != null) pageNavigation?.expand()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!L7Agreement.require(this)) return
        page = intent.getStringExtra("page") ?: page
        if (l7Ui) page = L7Routes.normalize(page)
        render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        debugGuides.save(outState)
        renderedPage?.let { scrollPositions[it] = contentScroll?.scrollY ?: 0 }
        outState.putBundle("scroll_positions", Bundle().apply { scrollPositions.forEach { (key, value) -> putInt(key, value) } })
        outState.putString("settings_page", lastSettingsPage)
        outState.putBoolean("home_menu_expanded", pageNavigation?.expanded ?: menuExpanded)
        outState.putString("page", page); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("pending_overlay_start", pendingOverlayStart)
        outState.putInt("probe_filter", probeState.filter)
        outState.putString("probe_query", probeState.query)
        outState.putString("probe_selected_report", probeState.selectedReport)
        outState.putString("probe_export_id", probeExporter.pendingId)
        audioTemplateFiles.save(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        L7Dialogs.refresh(this)
        if (pageNavigation != null && newConfig.densityDpi != navigationDensity) {
            menuExpanded = pageNavigation?.expanded ?: menuExpanded
            pageNavigation = null
            pageContent = null
        }
        if (l7Ui) theme.applyStyle(R.style.Theme_Xcertplay, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
            l7Ui && newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        render()
    }
    override fun onResume() {
        super.onResume()
        steeringDebugPage?.resume()
        reportingTestPage?.resume(); debugPage?.resume(); scenarioDebugPage?.resume()
        if (!L7Agreement.require(this)) return
        voiceDebugPage?.resume()
        codecProbePage?.resume()
        fullDebugPage?.resume()
        if (l7Ui && resources.configuration.densityDpi != L7UiDensity.value(this)) {
            recreate()
            return
        }
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        if (l7Ui && L7Routes.isDebug(page)) L7ProbeRunner.refreshEnvironment(this)
        L7DesktopNavigation.ensure(this)
        if (l7Ui) { if (page != "settings-debug-full") debugTasks.resume(); hotspotActions.resume(); hotspotSettings?.refresh(); wiredSettings?.check(); wirelessPrerequisites?.update() }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && page != "settings-debug-full" && (page == "home" || page == "connection" || page in L7Routes.settings)) {
            setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let { getString(R.string.setup_error_auth) }
            render()
        }
        if (!l7Ui || !audioModelConfirmation.ensure {
            if (page == "settings-audio" || page == "settings-vehicle") render()
            startAutomaticallyIfNeeded()
        }) startAutomaticallyIfNeeded()
        L7StartupGuard.showNotice(this, ::showDebugLogs)
        L7StartupGuard.healthyHome(this)
    }

    private fun startAutomaticallyIfNeeded() {
        if (isFinishing || isDestroyed || !L7Agreement.canUse(this)) return
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null &&
                L7StartupGuard.allowAutomatic(this)) {
                handler.post {
                    if (!isFinishing && !isDestroyed) connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
        }
    }
    private var channelDialog: android.app.AlertDialog? = null
    private var logView: L7LogView? = null

    override fun onPause() {
        steeringDebugPage?.background()
        debugPage?.background(); scenarioDebugPage?.background()
        reportingTestPage?.background()
        voiceDebugPage?.background()
        codecProbePage?.background()
        fullDebugPage?.background()
        channelDialog?.dismiss()
        channelDialog = null
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (l7Ui) VehicleSteeringInputLog.key(event, "vehicle-settings-key")
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        wirelessHotspotGate.cancel()
        hotspotActions.background(); wiredSettings?.background()
        if (!isChangingConfigurations) { L7ProbeRunner.stop(); debugTasks.background() }
        super.onStop()
    }

    override fun onDestroy() {
        configurationPage?.close(); configurationPage = null
        codecProbeController.close()
        steeringDebugPage?.close(); steeringDebugPage = null
        debugPage?.close(); scenarioDebugPage?.close(); scenarioDebugPage = null
        audioModelConfirmation.close()
        audioTemplateFiles.close()
        reportingTestPage?.close(); reportingTestPage = null
        voiceDebugPage?.close(); voiceDebugPage = null
        codecProbePage?.close(); codecProbePage = null
        fullDebugPage?.close(); fullDebugPage = null
        debugTasks.dispose(isChangingConfigurations)
        logView?.close(); logView = null
        hotspotSettings?.dispose()
        hotspotActions.close(); wiredSettings?.dispose()
        hotspotTask.close()
        super.onDestroy()
    }

    private fun profileChanged() {
        if (resources.configuration.densityDpi != L7UiDensity.value(this) ||
            AppLocale.preference(this) != languagePreferenceAtCreate) recreate()
        else render()
    }
    private var configurationPage: GalaxyConfigurationPage? = null
    private fun render() {
        if (renderedPage == "settings-vehicle" && page != "settings-vehicle" && configurationPage?.dirty == true) {
            val destination = page
            page = "settings-vehicle"
            configurationPage?.requestLeave { page = destination; render() }
            return
        }
        configurationPage?.close(); configurationPage = null
        if (!L7Agreement.require(this)) return
        steeringDebugPage?.close(); steeringDebugPage = null
        debugPage?.close(); scenarioDebugPage?.close(); scenarioDebugPage = null
        reportingTestPage?.close(); reportingTestPage = null
        voiceDebugPage?.close(); voiceDebugPage = null
        codecProbePage?.close(); codecProbePage = null
        fullDebugPage?.close(); fullDebugPage = null
        hotspotSettings?.dispose(); hotspotSettings = null; wirelessPrerequisites = null
        wiredSettings?.dispose(); wiredSettings = null
        if (page != "settings-connection-wireless") { hotspotActions.close(); hotspotTask.cancel() }
        if (renderedPage?.let(L7Routes::isDebug) == true && !L7Routes.isDebug(page)) L7ProbeRunner.stop()
        renderedPage?.let { scrollPositions[it] = contentScroll?.scrollY ?: 0 }
        contentScroll = null; desktopPermissionHint = null; homePanel = null; usbButton = null
        reconnectRow = null
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; diagnosticSettings = null; exportButton = null; debugPage = null; steeringDebugPage = null
        if (l7Ui) {
            renderL7()
            return
        }
    }

    private fun renderL7() {
        page = L7Routes.normalize(page)
        if (page in L7Routes.settings) lastSettingsPage = page
        val root = row().apply { setBackgroundColor(BG) }
        val inset = if (resources.configuration.screenWidthDp < 600) 16 else 24
        val settingsPage = page in L7Routes.settings
        val content = column().apply {
            setPaddingRelative(dp(inset), if (settingsPage) dp(16) else dp(24), dp(inset), dp(24))
        }
        if (page != "home" && page !in L7Routes.settings) {
            val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(label(getString(R.string.app_name), 24, TEXT, true).apply {
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            header.addView(label(L7AudioModelConfirmation.name(this, L7AudioTemplates.model(this)), 18, MUTED))
            content.addView(header)
            content.addView(space(24))
        }
        if (page != "home" && !settingsPage) {
            content.addView(label(pageTitle(), 28, TEXT, true).apply { setPadding(0, dp(12), 0, dp(20)) })
        }
        when (page) {
            "settings" -> settingsL7(content)
            "settings-vehicle" -> Unit
            "settings-connection" -> connectionChoicesL7(content)
            "settings-connection-wireless" -> connectionSettingsL7(content)
            "settings-connection-usb" -> wiredSettings = L7WiredSettings(this, content,
                { if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(false) }, ::stopFromHome)
            "settings-auth", "settings-display", "settings-audio", "settings-general", "settings-permissions" -> settings(content)
            "settings-debug-scenario" -> scenarioDebugPage = GalaxyScenarioDebugPage(this, content) { page = "settings-logs"; render() }
            "settings-debug-steering" -> steeringDebugPage = L7SteeringDebugPage(this, content) { page = "settings-logs"; render() }
            "settings-debug-codec" -> codecProbePage = GalaxyCodecProbePage(this, content, codecProbeController, ::codecProbeOccupied) { page = "settings-logs"; render() }
            "settings-debug-full" -> {
                val startNow = pendingFullDebugStart; pendingFullDebugStart = false
                fullDebugPage = GalaxyFullDebugPage(this, content, startNow, codecProbeController,
                    grant = { voicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onLogs = { page = "settings-logs"; render() })
            }
            "settings-debug-voice" -> voiceDebugPage = L7VoiceInputDebugPage(this, content,
                grant = { voicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                onLogs = { page = "settings-logs"; render() })
            "settings-debug-media", "settings-debug-navigation" -> reportingTestPage = L7ReportingTestPage(this, content,
                if (page == "settings-debug-media") L7ReportingKind.MEDIA else L7ReportingKind.NAVIGATION) { page = "settings-logs"; render() }
            "settings-logs" -> diagnostics(content)
            "settings-debug", "settings-debug-results", "settings-debug-history" -> {
                debugPage = L7DebugPage(this, content, page, probeState, probeExporter, debugTasks) { destination ->
                    pendingFullDebugStart = destination == "settings-debug-full"
                    page = destination; render()
                }
            }
            "settings-about" -> about(content)
            else -> homeL7(content)
        }
        val body = column().apply { if (settingsPage) L7SettingsStyle.content(this) }
        // 设置导航固定在正文滚动区之外，长页面仍可直接返回。
        if (settingsPage) body.addView(column().apply {
            setPaddingRelative(dp(inset), dp(24), dp(inset), dp(8))
            addView(L7Header(this@GalaxySettingsActivity, pageTitle(),
                backLabel = getString(when {
                    page.startsWith("settings-connection-") -> R.string.l7_connection_back
                    page == "settings-debug" -> R.string.l7_back_settings
                    L7Routes.isDebug(page) -> R.string.l7_probe_back_debug
                    else -> R.string.l7_back_settings
                }),
                onBack = if (page == "settings") null else ({ page = L7Routes.back(page); render() })
            ), LinearLayout.LayoutParams(-1, -2))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        contentScroll = scroll
        renderedPage = page
        if (page == "settings-vehicle") {
            configurationPage = GalaxyConfigurationPage(this, changed = ::profileChanged)
            body.addView(configurationPage!!.view, LinearLayout.LayoutParams(-1, 0, 1f))
            contentScroll = configurationPage!!.view.scroll
        } else body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(0, -1, 1f).apply {
            if (settingsPage) marginStart = L7ProjectionNavigation.settingsColumnWidth(this@GalaxySettingsActivity)
        })
        showL7Content(root)
        val expectedPage = page
        scroll.post { if (renderedPage == expectedPage) scroll.scrollTo(0, scrollPositions[expectedPage] ?: 0) }
        refreshStatus()
    }

    private fun selectL7Destination(destination: String) {
        if (configurationPage?.dirty == true) {
            configurationPage?.requestLeave { selectL7Destination(destination) }
            return
        }
        when {
            destination == "connection" -> GalaxyConnectionMenu.select(this) {
                page = "settings-connection"; render()
            }
            destination == "exit" -> L7AppExit.confirm(this)
            destination == "car-home" -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            destination == "home" && CarPlayBackgroundSession.hasSession() -> openProjection()
            else -> {
                val next = L7Routes.destination(page, destination, lastSettingsPage)
                if (page != next) { page = next; render() }
            }
        }
    }

    private fun showL7Content(content: View) {
        // 宿主只建立一次导航层；分类切换和主题刷新仅替换正文，菜单位置与实例保持不变。
        val container = pageContent ?: FrameLayout(this).also { holder ->
            pageContent = holder
            navigationDensity = resources.configuration.densityDpi
            val navigation = L7ProjectionNavigation(this, {}, ::selectL7Destination).also {
                if (menuExpanded) it.expand() else it.collapse()
                pageNavigation = it
            }
            setContentView(FrameLayout(this).apply {
                addView(holder, FrameLayout.LayoutParams(-1, -1))
                addView(navigation, FrameLayout.LayoutParams(-1, -1))
            })
        }
        container.removeAllViews()
        container.addView(content, FrameLayout.LayoutParams(-1, -1))
        pageNavigation?.apply { showPage(page); refreshAppearance() }
    }

    private fun pageTitle(): String = getString(when (page) {
        "connection" -> R.string.connection_setup
        "settings-vehicle" -> R.string.config_page_title
        "settings-auth" -> R.string.l7_auth_title
        "settings-connection" -> R.string.connection_setup
        "settings-connection-wireless" -> R.string.l7_start_wireless
        "settings-connection-usb" -> R.string.l7_home_usb
        "settings-display" -> R.string.display_and_performance
        "settings-audio" -> R.string.audio_routing
        "settings-general" -> R.string.application_settings_title
        "settings-permissions" -> R.string.permissions_and_connection_help
        "settings" -> R.string.settings
        "settings-debug" -> R.string.l7_probe_title
        "settings-debug-results" -> R.string.l7_probe_environment
        "settings-debug-history" -> R.string.l7_probe_history
        "settings-debug-scenario" -> R.string.debug_guide_scenario
        "settings-debug-full" -> R.string.full_debug_title
        "settings-debug-steering" -> R.string.l7_steering_title
        "settings-debug-codec" -> R.string.codec_probe_title
        "settings-debug-voice" -> R.string.l7_voice_title
        "settings-debug-media" -> R.string.l7_report_media_title
        "settings-debug-navigation" -> R.string.l7_report_navigation_title
        "settings-logs" -> R.string.l7_logs_title
        "settings-about" -> R.string.about
        else -> R.string.carplay
    })

    private fun settingsL7(content: LinearLayout) {
        val entries = listOf(
            Triple("settings-vehicle", R.string.config_page_title, R.drawable.ic_l7_preferences),
            Triple("settings-connection", R.string.connection_setup, R.drawable.ic_l7_connection),
            Triple("settings-general", R.string.application_settings_title, R.drawable.ic_l7_settings),
            Triple("settings-permissions", R.string.permissions_and_connection_help, R.drawable.ic_l7_permissions),
            Triple("settings-debug", R.string.l7_probe_title, R.drawable.ic_l7_debug),
            Triple("settings-logs", R.string.l7_logs_title, R.drawable.ic_l7_agreement),
            Triple("settings-about", R.string.about, R.drawable.ic_dp_about)
        )
        val hints = listOf(R.string.config_page_entry_hint, R.string.l7_connection_row_hint, R.string.application_settings_entry_hint,
            R.string.l7_permissions_row_hint, R.string.l7_probe_entry_hint, R.string.l7_logs_entry_hint, R.string.l7_about_row_hint)
        content.addView(label(getString(R.string.l7_settings_navigation_hint), 17, MUTED).apply {
            setPadding(0, 0, 0, dp(16))
        })
        entries.forEachIndexed { index, (destination, title, icon) ->
            content.addView(card().apply {
                addView(L7Components.categoryRow(this@GalaxySettingsActivity, getString(title), getString(hints[index]), icon) {
                    page = destination; render()
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
    }

    private fun connectionChoicesL7(content: LinearLayout) {
        L7SettingsSection.add(content, getString(R.string.l7_connection_choose), getString(R.string.l7_connection_choose_hint)) { card ->
            card.addView(L7Components.categoryRow(this, getString(R.string.l7_start_wireless),
                getString(R.string.l7_connection_wireless_hint), R.drawable.ic_l7_hotspot) {
                page = "settings-connection-wireless"; render()
            })
            card.addView(L7Components.categoryRow(this, getString(R.string.l7_home_usb),
                getString(R.string.l7_connection_usb_hint), R.drawable.ic_l7_usb) {
                page = "settings-connection-usb"; render()
            })
            card.addView(L7Components.categoryRow(this, getString(R.string.l7_auth_title),
                getString(R.string.config_auth_default_hint), R.drawable.ic_l7_settings) {
                page = "settings-auth"; render()
            })
            reconnectRow = L7Components.actionRow(this, getString(R.string.l7_reconnect_current),
                getString(R.string.l7_reconnect_current_hint), R.drawable.ic_l7_refresh, ::reconnectCurrent)
                .also { card.addView(it) }
        }
        L7SettingsSection.add(content, getString(R.string.automatic_connection)) { card ->
            GalaxyProfileFieldsView.add(this, card, R.string.automatic_connection)
        }
    }

    private fun connectionSettingsL7(content: LinearLayout) {
        content.addView(L7Components.sectionTitle(this, getString(R.string.l7_wireless_steps)))
        content.addView(L7Typography.text(this, getString(R.string.l7_connection_wireless_hint), L7Typography.Role.DESCRIPTION).apply {
            setPadding(0, 0, 0, dp(20))
        })
        wirelessPrerequisites = L7WirelessPrerequisiteView(this, content, ::choosePhone)
        hotspotSettings = L7HotspotSettings(this, content, hotspotTask, hotspotActions) {
            askHotspotCredentials { ssid, password ->
                saveHotspotCredentials(ssid, password)
                pendingCarHotspotSetup = false
                applyWirelessLink(WirelessHotspotMode.MANUAL)
            }
        }
        L7SettingsSection.add(content, getString(R.string.l7_wireless_step3)) { card ->
            card.addView(L7SettingRow(this, getString(R.string.l7_wireless_join_iphone), getString(R.string.l7_wireless_phone_hint)))
        }
        L7SettingsSection.actions(content) { actions ->
            connectButton = button(getString(R.string.l7_start_wireless), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(true)
            }
            disconnectButton = button(getString(R.string.disconnect), false) { stopFromHome() }.apply { visibility = View.GONE }
            actions.addView(connectButton, matchButton())
            actions.addView(disconnectButton, matchButton(12))
        }
    }

    private fun homeL7(content: LinearLayout) {
        content.gravity = Gravity.CENTER
        val inset = if (resources.configuration.screenWidthDp < 600) 16 else 24
        val width = dp((resources.configuration.screenWidthDp - inset * 2).coerceIn(1, 560))
        homePanel = L7HomePanel(this,
            onQuickConnect = {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(AirPlayPersistence.loadWirelessEnabled(this))
            },
            onWireless = { page = "settings-connection-wireless"; render() },
            onUsb = { page = "settings-connection-usb"; render() },
            onSettings = { page = "settings"; render() },
        ).also { content.addView(it, LinearLayout.LayoutParams(width, -2)) }
        // 悬浮权限提示留在首页内容中；连接状态统一由菜单「画面」显示。
        desktopPermissionHint = L7Components.text(this, getString(R.string.l7_desktop_home_permission), secondary = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(12))
            setOnClickListener { L7DesktopNavigation.permission(this@GalaxySettingsActivity) }
        }.also { content.addView(it, LinearLayout.LayoutParams(width, -2)) }
    }

    private fun settings(content: LinearLayout) {
        when (page) {
            "settings-display" -> L7DisplaySettings.add(this, content)
            "settings-audio" -> L7AudioSettings.page(this, content, audioTemplateFiles::importFile, audioTemplateFiles::exportFile) { title, current, role, save ->
                channelDialog?.dismiss()
                channelDialog = L7AudioRouteDialog.show(this, title, current, role, save)
            }
            "settings-auth" -> section(content, getString(R.string.l7_auth_title)) { card ->
                val source = when (L7Authentication.source(this)) {
                    L7Authentication.Source.BUILT_IN -> R.string.l7_auth_builtin
                    L7Authentication.Source.TEXT -> R.string.l7_auth_text
                    L7Authentication.Source.USB -> R.string.l7_auth_usb
                    L7Authentication.Source.REMOTE -> R.string.l7_auth_remote
                }
                card.addView(L7Components.valueRow(this, getString(R.string.l7_auth_choose_source), getString(source)) {
                    authenticationDialog().chooseSource()
                }.apply { if (setupError != null) setFeedback(getString(R.string.l7_auth_not_ready), error = true) })
                card.addView(L7Components.actionRow(this, getString(R.string.l7_auth_quick_import), getString(R.string.l7_auth_import_row_hint)) { authenticationDialog().quickImport() })
            }
            "settings-general" -> GalaxyApplicationSettingsPage.add(this, content) {
                page = "settings-auth"; render()
            }
            "settings-permissions" -> section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
                card.addView(L7Components.actionRow(this, getString(R.string.app_permissions)) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
                card.addView(L7Components.actionRow(this, getString(R.string.bluetooth_settings)) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
                card.addView(L7Components.actionRow(this, getString(R.string.wireless_connection_help)) { wirelessHelp() })
            }
        }
    }

    private fun diagnostics(content: LinearLayout) {
        diagnosticSettings = L7DiagnosticSettings(this, content, ::showDebugLogs,
            { exportDiagnostics() }, ::chooseReportDestination, ::requestOverlayPermission, debugTasks::upload).also {
            it.update(exportInProgress)
        }
    }

    private fun requestOverlayPermission(showAfterGrant: Boolean) {
        if (showAfterGrant && Settings.canDrawOverlays(this)) { startDebugOverlay(); return }
        fun launch() {
            pendingOverlayStart = showAfterGrant
            runCatching {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.recoverCatching {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.onFailure {
                pendingOverlayStart = false
                toast(getString(R.string.l7_debug_permission_denied))
            }
        }
        if (showAfterGrant) L7Dialogs.builder(this).setTitle(R.string.l7_debug_permission)
            .setMessage(R.string.l7_debug_permission_help)
            .setPositiveButton(R.string.l7_debug_permission) { _, _ -> launch() }
            .setNegativeButton(R.string.cancel, null).show()
        else launch()
    }

    private fun startDebugOverlay() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            debugNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchDebugOverlay()
    }

    private fun launchDebugOverlay() {
        if (!Settings.canDrawOverlays(this)) { toast(getString(R.string.l7_debug_permission_denied)); return }
        L7DebugOverlayService.start(this).onFailure { toast(getString(R.string.l7_debug_failed)) }
    }

    private fun showDebugLogs() {
        logView?.close()
        logView = L7LogView(this).also { it.show() }
    }

    private fun about(content: LinearLayout) = L7AboutSettings.add(this, content, version())

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        val dialog = L7Dialogs.builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
        if (!l7Ui) dialog.setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
        dialog.show()
    }

    private fun openCarWifiSettings() = L7HotspotSettings.openSettings(this)

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        L7HotspotEditor.show(this, storedSsid(), storedPassword(), apply = done)
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (!l7Ui && CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun authenticationDialog() = L7AuthenticationDialog(this) { reconnect ->
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let { getString(R.string.setup_error_auth) }
        render()
        toast(getString(if (l7Ui && reconnect) R.string.l7_auth_reconnect_hint else R.string.l7_auth_saved))
        if (!l7Ui && reconnect && setupError == null) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun connectionProblem(message: String) {
        L7Dialogs.builder(this).setTitle(R.string.setup_needs_attention).setMessage(message)
            .setPositiveButton(R.string.close, null).show()
    }

    private fun reconnectCurrent() {
        if (connectionRequestPending || CarPlayBackgroundSession.isStopping()) return
        val current = CarPlayBackgroundSession.snapshot()?.controller
        if (current == null || current.isClosed()) {
            refreshStatus()
            connectionProblem(getString(R.string.l7_reconnect_no_session))
            return
        }
        L7DebugLog.record("手动重连当前会话 transport=${current.transport}")
        // 沿用已有停止完成回调，旧控制器和媒体资源释放后才创建新会话。
        connect(current.transport == com.shilapi.xcertplay.orchestration.CarPlayTransport.WIRELESS)
    }

    private fun connect(wireless: Boolean, nativeHotspotPrepared: Boolean = false) {
        if (!L7Agreement.require(this)) return
        L7ReportingTests.stop("CONNECT_REQUEST")
        codecProbeController.stop("CONNECT_REQUEST")
        if (l7Ui && audioModelConfirmation.ensure { connect(wireless, nativeHotspotPrepared) }) return
        if (l7Ui && wireless && !CarPlayBackgroundSession.hasSession() &&
            !L7WirelessPrerequisites.ensure(this, ::choosePhone)) return
        if (wireless && hotspotTask.status.busy) { connectionProblem(getString(R.string.l7_hotspot_busy)); return }
        if (l7Ui && wireless && !nativeHotspotPrepared && setupError == null && !CarPlayBackgroundSession.hasSession()) {
            wirelessHotspotGate.start(onReady = {
                if (hotspotError(storedSsid(), storedPassword()) == null) pendingCarHotspotSetup = false
                connect(true, nativeHotspotPrepared = true)
            }, onSetup = { page = "settings-connection-wireless"; render() })
            return
        }
        if (l7Ui) L7DebugLog.record(if (wireless) "请求无线连接" else "请求 USB 有线连接")
        if (wireless && pendingCarHotspotSetup) { page = if (l7Ui) "settings-connection-wireless" else "connection"; render(); connectionProblem(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); return }
        if (setupError != null) { connectionProblem(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = if (l7Ui) "settings-connection-wireless" else "connection"
            render()
            connectionProblem(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (l7Ui && connectionRequestPending) return
        connectionRequestPending = true
        refreshStatus()
        val open = {
            connectionRequestPending = false
            if (!isFinishing && !isDestroyed && L7Agreement.canUse(this)) {
                AirPlayPersistence.saveWirelessEnabled(this, wireless)
                openProjection()
            }
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        if (!L7Agreement.require(this)) return
        L7StartupGuard.authorizeHost(this)
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (phoneDialog?.isShowing == true) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            pendingWireless = false
            phoneDialog = L7Dialogs.builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            pendingWireless = false
            phoneDialog = L7Dialogs.builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        phoneDialog = L7Dialogs.builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }
            .setOnCancelListener { pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        val dialog = L7Dialogs.builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
        if (!l7Ui) dialog.setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ -> confirmWirelessReset() }
        dialog.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        L7Dialogs.builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        diagnosticSettings?.update(exportInProgress)
        debugPage?.update()
        steeringDebugPage?.update()
        reportingTestPage?.update()
        voiceDebugPage?.update()
        codecProbePage?.update()
        wiredSettings?.update(connectionRequestPending)
        val running = CarPlayBackgroundSession.hasSession()
        val reconnectable = CarPlayBackgroundSession.snapshot()?.controller?.let { !it.isClosed() } == true
        val restarting = connectionRequestPending || CarPlayBackgroundSession.isStopping()
        reconnectRow?.apply {
            isEnabled = reconnectable && !restarting && setupError == null
            setFeedback(when {
                restarting -> getString(R.string.reconnecting_to_your_iphone)
                setupError != null -> setupError!!
                !reconnectable -> getString(R.string.l7_reconnect_no_session)
                else -> getString(R.string.l7_reconnect_ready)
            })
        }
        homePanel?.update(L7HomePanel.configured(this), running, CarPlayBackgroundSession.active,
            connectionRequestPending, setupError)
        status?.updateText(when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        })
        if (lastRunning != running) {
            connectButton?.updateText(if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone))
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        if (l7Ui) {
            connectButton?.updateText(getString(when {
                connectionRequestPending -> R.string.l7_preparing
                CarPlayBackgroundSession.active -> R.string.l7_resume_projection
                running -> R.string.l7_view_connection
                else -> if (page == "home") R.string.l7_entry_connect else R.string.l7_start_wireless
            }))
            pageNavigation?.setConnected(CarPlayBackgroundSession.active)
            desktopPermissionHint?.visibility = if (L7DesktopNavigation.enabled(this) && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
            disconnectButton?.updateText(getString(if (CarPlayBackgroundSession.active) R.string.disconnect else R.string.l7_cancel_connection))
            usbButton?.isEnabled = !running && !connectionRequestPending && setupError == null
        }
        connectButton?.isEnabled = (l7Ui || setupError == null) && !connectionRequestPending
    }
    // 相同状态不重复发送文本事件，避免持续播报与无谓的布局刷新。
    private fun TextView.updateText(value: String) {
        if (text.toString() != value) { text = value; if (this is Button && l7Ui) L7Icons.decorate(this) }
    }

    private fun stopFromHome() {
        fun stop() {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { if (!isDestroyed) refreshStatus() } }
        }
        if (CarPlayBackgroundSession.active) L7Dialogs.builder(this)
            .setTitle(R.string.disconnect).setMessage(R.string.l7_disconnect_confirm)
            .setPositiveButton(R.string.disconnect) { _, _ -> stop() }
            .setNegativeButton(R.string.cancel, null).show()
        else stop()
    }

    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        diagnosticSettings?.update(true)
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        // 在主线程读取当前窗口，避免导出线程访问 View，也不混入上次会话的屏幕值。
        val deviceSnapshot = L7DeviceDiagnostic.capture(this)
        Thread({
            val result = runCatching {
                // 在导出工作线程限时排空日志，不能阻塞 UI 或连接线程。
                AsyncDiagnosticLog.awaitIdle(500)
                val report = buildString {
                    appendLine("${getString(R.string.app_name)} ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication backend: ${AirPlayPersistence.loadMfiTarget(appContext)}")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${if (l7Ui) "L7 viewport physical geometry" else com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- 导出时设备信息（窗口属于当前导出页面）---")
                    deviceSnapshot.forEach { appendLine(it) }
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    if (l7Ui) {
                        appendLine("--- 当前进程日志（最多 1000 条，保留断联和窗口关闭后的记录）---")
                        L7DebugLog.buffer.snapshot().lines.forEach { appendLine(it) }
                        appendLine()
                    }
                    appendLine("--- Process exit history ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    appendLine("--- USB startup attempts ---")
                    L7WiredDiagnostics.report(appContext).forEach { appendLine(it) }
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES + L7ProbeLog.files) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report).uri
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                diagnosticSettings?.update(false)
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    L7Dialogs.builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) "Downloads/DiPlay/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show()
                } else {
                    diagnosticSettings?.showExportFailure()
                    L7Dialogs.builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        L7Dialogs.builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) {
        if (l7Ui) L7Notice.show(this, message) else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            val current = AppLocale.preference(this)
            if (l7Ui) {
                card.addView(L7Components.valueRow(this, getString(R.string.language_app_language), AppLocale.displayName(this, current)) { AppLocale.showPicker(this) })
                return@section
            }
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        if (l7Ui) {
            val footer = if (page == "settings-permissions") getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir) else ""
            L7SettingsSection.add(parent, title.takeUnless { it == pageTitle() }.orEmpty(), footer = footer, build = build)
            return
        }

        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(if (l7Ui) TEXT else ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(if (l7Ui) 40 else 28), dp(if (l7Ui) 40 else 28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!l7Ui || !page.startsWith("settings-") || title != pageTitle()) {
            card.addView(if (l7Ui) L7Components.sectionTitle(this, title) else heading)
        }
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (l7Ui) 16 else 18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        if (l7Ui) {
            parent.addView(L7Components.switchRow(this, title, description, value, save))
            return
        }
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply {
            contentDescription = title; isChecked = value; minHeight = dp(56)
            if (l7Ui) {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(ACCENT, MUTED))
                trackTintList = ColorStateList(states, intArrayOf(getColor(R.color.product_ui_selected), BORDER))
            } else buttonTintList = ColorStateList.valueOf(ACCENT)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        })
        parent.addView(line)
    }
    private fun card(): LinearLayout = if (l7Ui && page in L7Routes.settings) L7SettingsCard(this)
        else column().apply {
            if (l7Ui) L7Ui.surface(this) else background = rounded(SURFACE, BORDER)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = (if (l7Ui) size.coerceIn(16, 28) else size).toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = if (l7Ui) L7Components.actionButton(this, title, primary, click) else Button(this).apply {
        text = title; isAllCaps = false; textSize = if (l7Ui) 20f else 18f
        setTextColor(if (primary) getColor(R.color.product_ui_primary_text) else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        if (l7Ui) L7Ui.button(this, primary)
        setPadding(dp(16), dp(if (l7Ui) 12 else 0), dp(16), dp(if (l7Ui) 12 else 0))
        minHeight = dp(if (l7Ui) 64 else 56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(if (l7Ui) 12 else 20).toFloat(); setStroke(dp(1), stroke) }
    // L7 的按钮保持最低触控高度，大字体换行后允许继续增高。
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, if (l7Ui) -2 else dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
