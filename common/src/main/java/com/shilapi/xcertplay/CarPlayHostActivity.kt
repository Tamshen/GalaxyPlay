package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.VideoViewport
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private var wiredAttempt: String? = null
    private var wiredStartupFeedback: String? = null
    private var wiredStartupWait: String? = null
    private var wiredFailureDialog: android.app.AlertDialog? = null
    private val vpnGate: L7VpnConsent by lazy { L7VpnConsent(
        prepare = { CarPlayVpnService.prepare(this) },
        launch = { awaitingVpnConsent = true; vpnConsent.launch(it) },
        trace = { phase, result, error ->
            L7WiredDiagnostics.event(this, wiredAttempt, phase, result, error,
                if (error != null) "FAILED" else null)
            appendLog("USB VPN phase=$phase result=$result" + (error?.let { " exception=${it.javaClass.simpleName}" } ?: ""))
            if (result == "WAITING") {
                wiredStartupFeedback = getString(R.string.l7_usb_vpn_waiting)
                setConnectionStage(wiredStartupFeedback!!)
            }
        },
        ready = {
            wiredStartupFeedback = null
            awaitingVpnConsent = false
            vpnReady = true
            if (locationReportingEnabled && !locationPermissionAvailable) requestLocationPermission()
            else maybeStartCarPlay()
        },
        failed = ::wiredStartupFailed,
    ) }
    private val l7DebugLogs by lazy { resources.getBoolean(R.bool.config_l7_product_ui) }
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var projectionNavigation: L7ProjectionNavigation? = null
    private var l7ChromeDensity = 0
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = getString(R.string.app_name),
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleStatusEnabled = com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphone(this),
            chargingConnectors = com.shilapi.xcertplay.hud.BydOutputSettings.chargingConnectors(this),
            vehicleSpeedEnabled = locationReportingEnabled && com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphone(this),
        ),
        label = getString(R.string.app_name),
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        // USB 有线连接不依赖未填写的无线热点参数。
        wirelessHotspotMode = if (wirelessEnabled) wirelessHotspotMode else WirelessHotspotMode.WIFI_P2P,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            vpnGate.returned(result.resultCode)
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            L7WiredDiagnostics.event(this, wiredAttempt, "MICROPHONE", if (granted) "GRANTED" else "DENIED")
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            L7WiredDiagnostics.event(this, wiredAttempt, "LOCATION", if (locationPermissionAvailable) "GRANTED" else "DENIED")
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    private var videoView: View? = null
    private var softwareVideo: GalaxySoftwareVideoOutput? = null
    private var videoWindowProbe: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private val touchTracker = CarPlayTouchMapper.Tracker()
    private var videoCanvasSize: DisplaySize? = null
    private var videoViewport: VideoViewport? = null
    // 同一个 UI Surface 可能先后绑定新旧会话，释放时必须等待全部持有者。
    private val surfaceOwners = mutableMapOf<Surface, MutableSet<AndroidMediaSink>>()
    private val retiringTextures = mutableSetOf<SurfaceTexture>()
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var videoFailureDialog: android.app.AlertDialog? = null
    private var pendingVideoFailure: Pair<Int, com.shilapi.xcertplay.airplay.VideoCodec>? = null
    private var videoRecoveryPanel: L7VideoRecoveryPanel? = null
    private var hevcEnabled = true
    private var sessionAvcFallback = false
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.let { old -> retireVideoSurface(old, currentSurfaceTexture?.takeIf { it !== texture }) }
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoViewport(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            releaseVideoTouches()
            updateVideoViewport(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (retiringTextures.remove(texture)) return false
            if (currentSurfaceTexture !== texture) return true
            releaseVideoTouches()
            val surface = currentSurface
            if (surface != null) retireVideoSurface(surface, texture)
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            if (surface == null) return true
            // SurfaceTexture 由宿主在 worker 解除后释放，框架不能提前释放。
            retiringTextures.remove(texture)
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (L7AppExit.exiting) { finish(); return }
        if (!L7Agreement.require(this)) return
        if (!L7StartupGuard.enterHost(this)) return
        L7DesktopNavigation.attach(this)
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        val restoredVpnAttempt = L7VpnConsent.savedAttempt(savedInstanceState, AirPlayPersistence.loadWirelessEnabled(this))
        if (!AirPlayPersistence.loadWirelessEnabled(this))
            wiredAttempt = L7WiredDiagnostics.beginOrResume(this, restoredVpnAttempt)
        L7WiredDiagnostics.event(this, wiredAttempt, "AUTH_BOOTSTRAP", "BEGIN")
        val bootstrap = runCatching { DiPlayBootstrap.ensure(this) }
        if (bootstrap.isFailure) {
            L7WiredDiagnostics.event(this, wiredAttempt, "AUTH_BOOTSTRAP", "FAILED", bootstrap.exceptionOrNull(), "FAILED")
            startActivity(Intent(this, GalaxySettingsActivity::class.java))
            finish(); return
        }
        L7WiredDiagnostics.event(this, wiredAttempt, "HOST_UI", "BEGIN")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        L7SteeringDiagnostics.initialize(applicationContext)
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (projectionNavigation != null) {
                        projectionNavigation?.expand()
                        return
                    }
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        L7WiredDiagnostics.event(this, wiredAttempt, "HOST_UI", "READY")
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            L7WiredDiagnostics.event(this, wiredAttempt, "SESSION", "REUSED", outcome = "CONNECTED")
            updateDebugOverlays()
        } else if (restoredVpnAttempt != null) {
            // ActivityResultRegistry 会交付原授权页结果，不能重建时另开一轮申请。
            // 到达 VPN 阶段前已处理过麦克风许可，先前拒绝也允许继续无麦克风连接。
            microphonePermissionResolved = true
            awaitingVpnConsent = true
            vpnGate.restoreWaiting(restoredVpnAttempt)
        } else if (microphonePermissionResolved) {
            L7WiredDiagnostics.event(this, wiredAttempt, "MICROPHONE", "ALREADY_GRANTED")
            requestStartupPrerequisites()
        } else {
            L7WiredDiagnostics.event(this, wiredAttempt, "MICROPHONE", "BEGIN")
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val audioModelConfirmation = L7AudioModelConfirmation(this)

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        L7WiredDiagnostics.event(this, wiredAttempt, "PREREQUISITES", "BEGIN")
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        L7WiredDiagnostics.event(this, wiredAttempt, "LOCATION", "BEGIN")
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        vpnGate.request()
    }

    private fun wiredStartupFailed(failure: L7VpnConsent.Failure) {
        awaitingVpnConsent = false
        vpnReady = false
        L7WiredDiagnostics.event(this, wiredAttempt, "VPN", failure.name, outcome = "FAILED")
        val message = getString(when (failure) {
            L7VpnConsent.Failure.PAGE_MISSING -> R.string.l7_usb_vpn_missing
            L7VpnConsent.Failure.SYSTEM_DENIED -> R.string.l7_usb_vpn_system_denied
            L7VpnConsent.Failure.DECLINED -> R.string.l7_usb_vpn_declined
            else -> R.string.l7_usb_vpn_failed
        })
        wiredStartupFeedback = message
        setConnectionStage(message)
        if (isFinishing || isDestroyed) return
        wiredFailureDialog?.dismiss()
        val guidance = if (failure in setOf(L7VpnConsent.Failure.PAGE_MISSING, L7VpnConsent.Failure.SYSTEM_DENIED))
            message + "\n\n" + getString(R.string.l7_usb_vpn_adb_hint) else message
        wiredFailureDialog = L7Dialogs.builder(this).setTitle(R.string.l7_usb_start_failed).setMessage(guidance)
            .setPositiveButton(R.string.l7_usb_retry) { _, _ ->
                wiredStartupFeedback = null
                wiredAttempt = L7WiredDiagnostics.begin(this)
                requestStartupPrerequisites()
            }
            .setNegativeButton(R.string.l7_usb_back_settings) { _, _ -> showDiPlayHome("settings-connection-usb"); finish() }
            .setNeutralButton(R.string.l7_log_view_short) { _, _ -> showDiPlayHome("settings-debug-logs"); finish() }
            .show()
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!L7Agreement.require(this)) return
        if (!L7StartupGuard.enterHost(this)) return
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED" && wirelessEnabled) {
            shutdown(false, "switching to USB") {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                startActivity(Intent(this, CarPlayHostActivity::class.java))
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (L7AppExit.exiting || isFinishing) return
        if (!L7Agreement.require(this)) return
        refreshL7ChromeDensity()
        L7DesktopNavigation.ensure(this)
        projectionNavigation?.refreshAppearance()
        projectionNavigation?.setConnected(controller?.hasActiveSession() == true)
        // 设置页复用原宿主，握手前须读取新连接方式、凭据及显式认证来源。
        if (!menuOpen) loadPersistedSettings()
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (DiLink51ClusterLayout.automatic(this) && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!DiLink51ClusterLayout.automatic(this)) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        ensureClusterPresentation()
        maybeStartCarPlay()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    // Experimental: the CarPlay instrument-cluster stream on the BYD cluster projection display.
    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        if (state != detectedCluster) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible}")
        detectedCluster = state
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) { dismissClusterPresentation(); return }
        ensureClusterPresentation()
    }

    private fun ensureClusterPresentation() {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        if (clusterPresentation != null) return
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        val presentation = ClusterMapPresentation(this, display, theme) { surface -> runOnUiThread { onClusterSurface(surface) } }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(true)
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            appendLog("Cluster map: presentation shown display=${display.displayId}")
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
        }
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        var target = clusterLayers[fullMap]
        if (target == null) {
            val display = ClusterMapPresentation.findDisplay(this, theme) ?: return
            lateinit var presentation: ClusterMapPresentation
            presentation = ClusterMapPresentation(this, display, theme) { surface ->
                runOnUiThread {
                    if (clusterPresentation === presentation) onClusterSurface(surface)
                }
            }
            presentation.setOnDismissListener {
                if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
                if (clusterPresentation === presentation) clusterPresentation = null
            }
            try {
                presentation.setMapVisible(false)
                clusterPresentation = presentation
                clusterLayers[fullMap] = presentation
                presentation.show()
                appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
                target = presentation
            } catch (error: RuntimeException) {
                clusterLayers.remove(fullMap)
                clusterPresentation = null
                appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
                return
            }
        }
        clusterPresentation = target
        target.outputSurface?.let(::onClusterSurface)
        target.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
        target.setMapVisible(visible)
    }

    private fun dismissClusterPresentation() {
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        presentations.forEach { runCatching { it.dismiss() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if (!DiLink51ClusterLayout.supported() || surface == null) {
            clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: return null
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x <= 0 || size.y <= 0) return null
        if (DiLink51ClusterLayout.supported()) {
            val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
            return DiLink51ClusterLayout.streamConfig().also {
                appendLog("Cluster map: fixed 1920x720 stream; layout=$theme viewport=$plan")
            }
        }
        return CarPlayClusterDisplay.config(
            size.x,
            size.y,
            AirPlayPersistence.loadClusterMapScalePercent(this),
            AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
            AirPlayPersistence.loadClusterMarkerVerticalStep(this),
            AirPlayPersistence.loadClusterContent(this),
        ).also {
            appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} safeArea=${it.safeArea} url=${it.initialUrl}")
        }
    }

    // L7 标准媒体键与系统媒体控制共用转发路径；语音广播另由会话所属接收器处理。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (l7DebugLogs && GalaxyMediaKeys.onHardwareKey(controller, event)) return true
        val voiceKey = if (l7DebugLogs) event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
            else CarPlayMediaButton.opensSiri(event.keyCode)
        if (!voiceKey) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = if (l7DebugLogs) GalaxyMediaKeys.onVoiceKey(controller) else controller?.requestSiri() == true
            appendLog("Siri: voice key ${event.keyCode} sent=$sent")
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { applyFullscreenMode(); showPendingVideoFailure() }
    }

    override fun onStop() {
        releaseVideoTouches()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        vpnGate.saveWaiting(outState, if (wirelessEnabled) null else wiredAttempt)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        L7Dialogs.refresh(this)
        if (l7DebugLogs) {
            theme.applyStyle(R.style.Theme_Xcertplay, true)
            L7Ui.refresh(window.decorView)
        }
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        audioModelConfirmation.close()
        vpnGate.dispose()
        wiredFailureDialog?.dismiss()
        wiredFailureDialog = null
        if (isFinishing && controller == null) {
            L7WiredDiagnostics.event(this, wiredAttempt, "HOST", "CLOSED", outcome = "CANCELLED")
        }
        videoFailureDialog?.dismiss()
        videoFailureDialog = null
        releaseVideoTouches()
        if (CarPlayBackgroundSession.isOwner(this)) {
            sink?.setVideoSizeChangedListener(null)
            sink?.setScreenStreamActiveChangedListener(null)
        }
        clusterMonitor?.stop()
        dismissClusterPresentation()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        removeVideoWindowProbe()
        softwareVideo?.close()
        softwareVideo = null
        currentSurface?.let { surface ->
            retireVideoSurface(surface, currentSurfaceTexture)
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            // 主画面没有 alpha，避免全屏透明合成；黑边由根容器承接。
            isOpaque = true
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        if (l7DebugLogs) {
            val waiting = createL7Waiting()
            root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
            stageStatusView = waiting.stage
            wifiRecoveryButton = waiting.recoveryButton
            connectionPanel = waiting
        } else {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
                setBackgroundColor(getColor(R.color.product_ui_background))
                isClickable = true
            }
            panel.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = getString(R.string.carplay)
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            panel.addView(TextView(this).apply {
                text = getString(R.string.diplay); textSize = 28f; setTextColor(getColor(R.color.product_ui_text))
                gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(14))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            val stage = TextView(this).apply {
                text = getString(R.string.getting_carplay_ready); textSize = 22f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.product_ui_text))
            }
            panel.addView(stage)
            panel.addView(TextView(this).apply {
                text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                    else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
                textSize = 18f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.product_ui_muted))
                setPadding(0, dp(14), 0, dp(24))
            })
            panel.addView(Button(this).apply {
                text = getString(R.string.reset_carplay_wi_fi); isAllCaps = false; textSize = 18f
                visibility = View.GONE
                setOnClickListener { showDiPlayHome("wireless-recovery") }
                wifiRecoveryButton = this
            }, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
            panel.addView(Button(this).apply {
                text = getString(R.string.back_to_diplay); isAllCaps = false; textSize = 18f
                setTextColor(getColor(R.color.product_ui_primary_text))
                background = GradientDrawable().apply { setColor(getColor(R.color.product_ui_accent)); cornerRadius = dp(12).toFloat() }
                minHeight = dp(64)
                setOnClickListener { showDiPlayHome() }
            }, LinearLayout.LayoutParams(dp(300), dp(64)))
            panel.addView(TextView(this).apply {
                text = getString(R.string.in_carplay_swipe_down_with_three_fingers_to_open_diplay_se)
                textSize = 14f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.product_ui_muted)); setPadding(0, dp(20), 0, 0)
            })
            root.addView(panel, FrameLayout.LayoutParams(-1, -1))
            stageStatusView = stage
            connectionPanel = panel
        }
        videoView = video
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        updateDebugOverlays()
        if (l7DebugLogs) {
            // 浮层始终覆盖同一全尺寸视频容器，展开或收起不触发 Surface 尺寸变化。
            val navigation = createL7Navigation()
            projectionNavigation = navigation
            l7ChromeDensity = L7UiDensity.value(this)
            root.addView(navigation, FrameLayout.LayoutParams(-1, -1))
        }
        videoRecoveryPanel = createVideoRecoveryPanel().also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        return root
    }

    private val galaxyChrome by lazy { GalaxyHostChrome(this) }

    private fun createVideoRecoveryPanel() = galaxyChrome.videoRecovery(
        retry = ::retryCurrentVideo, settings = { showDiPlayHome("settings-connection") })

    private fun retryCurrentVideo(): Boolean {
        val failure = pendingVideoFailure ?: return false
        if (failure.first != restartGeneration || shuttingDown.get() || !CarPlayBackgroundSession.isOwner(this)) return false
        val queued = sink?.retryMainVideo() == true
        appendLog("Video: userRetry generation=$restartGeneration queued=$queued audioRestart=false")
        return queued
    }

    private fun createL7Waiting() = galaxyChrome.waiting(wirelessEnabled,
        home = { showDiPlayHome() },
        cancel = { CarPlayBackgroundSession.stop { runOnUiThread { showDiPlayHome(); finish() } } },
        recovery = { showDiPlayHome("wireless-recovery") }).also { panel ->
        panel.retryButton.visibility = if (startupRecovery.stopped) View.VISIBLE else View.GONE
        panel.retryButton.setOnClickListener {
            if (shuttingDown.get() || !CarPlayBackgroundSession.isOwner(this)) return@setOnClickListener
            startupRecovery.manualRetry()
            panel.retryButton.visibility = View.GONE
            restartCarPlay("Manual wireless retry")
        }
    }

    private fun createL7Navigation() = galaxyChrome.navigation(::releaseVideoTouches, ::showDiPlayHome)

    private fun refreshL7ChromeDensity() {
        if (!l7DebugLogs || l7ChromeDensity == L7UiDensity.value(this)) return
        val root = videoView?.parent as? FrameLayout ?: return
        val stage = stageStatusView?.text
        val recovery = wifiRecoveryButton?.visibility ?: View.GONE
        val expanded = projectionNavigation?.expanded ?: false
        // 只替换覆盖控件，不移除视频、触控层或控制器；保留等待阶段和菜单状态。
        connectionPanel?.let(root::removeView)
        projectionNavigation?.let(root::removeView)
        val waiting = createL7Waiting()
        stage?.let { waiting.stage.text = it }
        waiting.recoveryButton.visibility = recovery
        stageStatusView = waiting.stage
        wifiRecoveryButton = waiting.recoveryButton
        connectionPanel = waiting
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        projectionNavigation = createL7Navigation().also {
            it.setConnected(controller?.hasActiveSession() == true)
            if (expanded) it.expand() else it.collapse()
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        videoRecoveryPanel?.let(root::removeView)
        videoRecoveryPanel = createVideoRecoveryPanel().also {
            if (pendingVideoFailure?.first == restartGeneration) it.failed()
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        l7ChromeDensity = L7UiDensity.value(this)
        updateDebugOverlays()
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val panel = FrameLayout(this).apply {
            setBackgroundColor(MENU_BACKGROUND)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        content.addView(
            menuText(getString(R.string.carplay_settings), 32f, Color.WHITE, bold = true).apply {
                setPadding(dp(56), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.connection)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText(getString(R.string.wireless_carplay_2), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = Switch(this).apply {
            isChecked = wirelessEnabled
            contentDescription = getString(R.string.wireless_carplay_transport)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText(getString(R.string.hotspot_status), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.location)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.startup)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = getString(R.string.auto_start_on_boot),
                checked = autoStartOnBoot,
                description = getString(R.string.start_carplay_automatically_after_device_boot),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsCategoryHeader(getString(R.string.audio)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(36) },
            )
            content.addView(
                settingsSwitchRow(
                    label = getString(R.string.advanced_audio_channel_mapping),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.identity_appearance)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.display_video)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText(getString(R.string.resolution), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = SeekBar(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText(getString(R.string.s_0_3x), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText(getString(R.string.s_1_0x), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.physical_size_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.widest_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.longest_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText("HEVC (H.265)", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = Switch(this).apply {
            isChecked = hevcEnabled
            contentDescription = getString(R.string.hevc_h_265_video_transport)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                sessionAvcFallback = false
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText(getString(R.string.hevc_software_decoder), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = Switch(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = getString(R.string.use_software_hevc_decoder)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.window)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.diagnostics)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                settingsCategoryHeader(getString(R.string.android_9_compatibility)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    getString(R.string.settings_android9_compat),
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val save = Button(this).apply {
            text = getString(R.string.save_and_reconnect)
            isAllCaps = false
            textSize = 17f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            minHeight = dp(52)
            setOnClickListener { saveSettingsAndReconnect() }
        }
        content.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(46) },
        )

        val exitApplicationButton = Button(this).apply {
            text = getString(R.string.exit_application)
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(MENU_DANGER)
            minHeight = dp(52)
            setOnClickListener { exitApplication() }
        }
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(Button(this).apply {
            text = getString(R.string.language_app_language)
            isAllCaps = false
            setOnClickListener { AppLocale.showPicker(this@CarPlayHostActivity) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            Button(this).apply {
                text = "X"
                isAllCaps = false
                textSize = 22f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
                contentDescription = getString(R.string.discard_changes_and_exit_settings)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { cancelSettingsEdits() }
            },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(16)
                topMargin = dp(16)
            },
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, MAX_SETTINGS_MENU_WIDTH_PX),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = minOf(view.width, MAX_SETTINGS_MENU_WIDTH_PX)
            val params = panel.layoutParams
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                panel.layoutParams = params
            }
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = getString(R.string.mfi_certificate_signing_target),
            options = listOf(
                MfiTarget.LOCAL to getString(R.string.l7_auth_local),
                MfiTarget.USB_CH341 to getString(R.string.usb_ch341),
                MfiTarget.I2C to getString(R.string.i2c),
                MfiTarget.REMOTE to getString(R.string.remote),
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.linux_device_path_for_example_dev_i2c_1), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.settings_mfi_address_hint),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location_to_iphone), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.show_on_screen_debug_logs),
        ) { checked ->
            debugLogsEnabled = checked
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.choose_image)
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.default_icon)
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.left_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.right_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_top_bar),
                checked = hideTopBar,
                description = getString(R.string.hide_the_status_bar),
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_bottom_bar),
                checked = hideBottomBar,
                description = getString(R.string.hide_the_navigation_bar),
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.set)
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.wi_fi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.wi_fi_p2p_5_ghz))
            }
            add(WirelessHotspotMode.MANUAL to getString(R.string.built_in_car_hotspot))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to getString(R.string.s_2_4_ghz),
                    ManualHotspotBand.GHZ_5 to getString(R.string.s_5_ghz),
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.channel_0_auto),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.open),
                    ManualHotspotSecurity.WPA2 to getString(R.string.wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to getString(R.string.wpa3),
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.i2c_device_path_is_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in mfiI2cPath -> getString(R.string.i2c_device_path_contains_u_0000)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val messages = mutableListOf<String>()
        val candidates = com.shilapi.xcertplay.media.VideoDecoderCapabilities.query(mime,
            display.widthPixels, display.heightPixels, display.fps.toDouble(), messages::add)
        val hardware = candidates.any { it.hardware }
        val supported = if (hevcEnabled && hevcSoftwareDecoderEnabled) candidates.any { it.software } else hardware
        CanvasSupport(supported, if (supported) "supported" else "no_hardware_canvas_support",
            messages.joinToString("\n").ifEmpty { "Decoder capability result=no_decoder" })
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, displayScaleTenths)
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, requestedPercent)
        var effectivePercent = requestedPercent
        val candidate = scaledDisplay
        val support = when {
            uiScalePercent >= CarPlayUiScale.DEFAULT -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
            scaledDisplay === resolutionDisplay -> CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            else -> largerCanvasSupport(scaledDisplay)
        }
        if (!support.supported) {
            scaledDisplay = resolutionDisplay
            effectivePercent = CarPlayUiScale.DEFAULT
            appendLog("Larger CarPlay canvas unavailable reason=${support.reason}; using Default icon and text size")
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(effectivePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        var effectiveHevc = hevcEnabled && !sessionAvcFallback
        val capabilityLog = mutableListOf<String>()
        fun support(mime: String, rate: Int, software: Boolean): Boolean? {
            capabilityLog.clear()
            val candidates = runCatching { com.shilapi.xcertplay.media.VideoDecoderCapabilities.query(
                mime, scaledDisplay.widthPixels, scaledDisplay.heightPixels, rate.toDouble(), capabilityLog::add) }.getOrNull()
                ?: return null
            if (candidates.any { if (software) it.software else it.hardware }) return true
            return if (capabilityLog.any { "query failed" in it }) null else false
        }
        val effectiveFormat = com.shilapi.xcertplay.media.VideoSessionFormat.select(
            effectiveHevc, fps, hevcSoftwareDecoderEnabled, ::support)
        if (effectiveFormat.hevc != effectiveHevc || effectiveFormat.fps != fps) {
            effectiveHevc = effectiveFormat.hevc
            scaledDisplay = scaledDisplay.copy(fps = effectiveFormat.fps)
            appendLog("Video session fallback codec=H264 fps=30 preferencePreserved=true")
        }
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${displayScaleTenths * 10}% " +
            "base=${resolutionDisplay.widthPixels}x${resolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$effectivePercent " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} effectiveHevc=$effectiveHevc effectiveFps=${display.fps} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = getString(R.string.app_name),
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = display,
            cluster = clusterDisplayConfig(),
            rightHandDrive = rightHandDrive,
            hevc = effectiveHevc,
            supportsOpusOutput = wirelessEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParked(this),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        if (l7DebugLogs) L7DisplayGeometry.forViewport(this, size.width, size.height)
        else AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            "${getString(R.string.safe_area_prefix)}${mapping.width} x ${mapping.height} at " +
                "(${mapping.left}, ${mapping.top}) in ${size.width} x ${size.height}"
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
        localMusic: Boolean,
        videoFps: Int,
    ): AndroidMediaSink {
        // 固定当前会话文件，解码器的迟到回调不能写入新会话。
        val diagnosticLog = sessionLog
        // 当前会话固定一份模板，设置修改从下次连接起生效。
        val audioTemplate = if (l7DebugLogs) L7AudioTemplates.load(this) else null
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = if (audioTemplate != null) true else advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            localMediaAudioEnabled = localMusic,
            videoFps = videoFps,
            mediaChannel = audioTemplate?.choice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA)
                ?: AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = audioTemplate?.choice(com.shilapi.xcertplay.media.AudioOutputRole.NAVIGATION)
                ?: AirPlayPersistence.loadNavigationAudioChannel(this),
            assistantChannel = audioTemplate?.choice(com.shilapi.xcertplay.media.AudioOutputRole.ASSISTANT)
                ?: AirPlayPersistence.loadAssistantAudioChannel(this),
            context = this,
            platformAdaptation = com.shilapi.xcertplay.media.GalaxyMediaPolicy(
                l7DebugLogs, audioTemplate?.preferBus ?: AirPlayPersistence.loadL7AudioBusEnabled(this), audioTemplate, GalaxyNavigationOutput.load(this)),
            wirelessAudio = wirelessEnabled,
            callProcessingEnabled = AirPlayPersistence.loadCallProcessingEnabled(this),
            onVideoFailure = { codec, reason -> onVideoFailure(controllerGeneration, codec, reason) },
            onVideoRecovered = { onVideoRecovered(controllerGeneration) },
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = GalaxySessionDiagnostics.audio(applicationContext, diagnosticLog, controllerGeneration, l7DebugLogs),
            onVideoSizeChanged = { width, height -> updateVideoCanvas(controllerGeneration, width, height) },
        )
    }

    private fun onVideoFailure(generation: Int, codec: com.shilapi.xcertplay.airplay.VideoCodec, reason: String) {
        runOnUiThread {
            if (generation != restartGeneration || shuttingDown.get() || !CarPlayBackgroundSession.isOwner(this)) return@runOnUiThread
            if (sink?.currentVideoFailure() != (codec to reason)) return@runOnUiThread
            pendingVideoFailure = generation to codec
            appendLog("Video: failure generation=$generation codec=$codec reason=$reason")
            videoRecoveryPanel?.failed()
            showPendingVideoFailure()
        }
    }

    private fun onVideoRecovered(generation: Int) {
        runOnUiThread {
            if (generation != restartGeneration || shuttingDown.get() || !CarPlayBackgroundSession.isOwner(this)) return@runOnUiThread
            if (sink?.currentVideoFailure() != null) return@runOnUiThread
            pendingVideoFailure = null
            videoRecoveryPanel?.recovered()
            videoFailureDialog?.dismiss()
        }
    }

    private fun showPendingVideoFailure() {
        val failure = pendingVideoFailure ?: return
        if (failure.first != restartGeneration || !CarPlayBackgroundSession.isOwner(this)) {
            pendingVideoFailure = null
            videoRecoveryPanel?.recovered()
            return
        }
        if (failure.second != com.shilapi.xcertplay.airplay.VideoCodec.H265 || !hevcEnabled) return
        if (!hasWindowFocus() || menuOpen || isFinishing || videoFailureDialog != null) return
        setConnectionStage(getString(R.string.l7_hevc_failed_title))
        videoFailureDialog = L7Dialogs.builder(this).setTitle(R.string.l7_hevc_failed_title)
            .setMessage(R.string.l7_hevc_failed_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_hevc_use_avc) { _, _ ->
                // 旧会话弹窗不能改变新会话；编码格式必须通过重新协商切换。
                if (failure.first == restartGeneration && !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this)) {
                    sessionAvcFallback = true
                    restartCarPlay("HEVC failed; user selected H.264")
                }
            }.create().also { dialog ->
                dialog.setOnDismissListener { if (videoFailureDialog === dialog) videoFailureDialog = null }
                dialog.show()
            }
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener {
        val diagnosticLog = sessionLog
        return object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    L7WiredDiagnostics.event(this@CarPlayHostActivity, wiredAttempt, "SESSION", "ACTIVE", outcome = "CONNECTED")
                    CarPlayBackgroundSession.active = true
                    L7StartupGuard.connected(this@CarPlayHostActivity) {
                        activeAirPlaySession === session && controllerGeneration == restartGeneration &&
                            CarPlayBackgroundSession.active
                    }
                    projectionNavigation?.setConnected(true)
                    reconnectAttempts = 0
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (!startupRecovery.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRecovery.stable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("Wireless startup budget reset after stable video")
                        }
                    }, com.shilapi.xcertplay.network.WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRecovery.stopped) return@runOnUiThread
                    if (activeAirPlaySession != null && activeAirPlaySession !== session) return@runOnUiThread
                    startupRecovery.disconnected()
                    appendLog("AirPlay session ended")
                    if (activeAirPlaySession === session) {
                        activeAirPlaySession = null
                    }
                    CarPlayBackgroundSession.active = false
                    projectionNavigation?.setConnected(false)
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration == restartGeneration) appendLog("CarPlay transport error: $message")
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    startupRecovery.disconnected()
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (controllerGeneration != restartGeneration &&
                    !message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX)) return
                val safe = DiagnosticRedactor.redact(message) ?: return
                // 文件目标随监听器固定，旧控制器的关闭诊断仍落在所属会话；写盘不占用主线程。
                AsyncDiagnosticLog.append(diagnosticLog, safe)
                if (l7DebugLogs) L7DebugLog.record(safe)
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    if (message == "Video: first frame rendered") {
                        L7WiredDiagnostics.event(this@CarPlayHostActivity, wiredAttempt, "VIDEO", "FIRST_FRAME", outcome = "CONNECTED")
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)

                }
            }
        }
    }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (controllerGeneration == restartGeneration) {
            L7WiredDiagnostics.event(this, wiredAttempt, "TRANSPORT", status.javaClass.simpleName,
                outcome = if (status is CarPlayStatus.Failed) "FAILED" else null)
        }
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                    wifiRecoveryButton?.visibility = View.VISIBLE
                } else {
                    wifiRecoveryButton?.visibility = View.GONE
                    reconnectAfterLoss(description, status.startupFailure)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height, this, snapshot.display) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        snapshot.sink.setVideoSizeChangedListener { width, height -> updateVideoCanvas(generation, width, height) }
        snapshot.sink.setVideoRecoveredListener { onVideoRecovered(generation) }
        snapshot.sink.setVideoFailureListener { codec, reason -> onVideoFailure(generation, codec, reason) }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        projectionNavigation?.setConnected(snapshot.controller.hasActiveSession())
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (L7AppExit.exiting || !L7Agreement.canUse(this)) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        L7WiredDiagnostics.event(this, wiredAttempt, "CONTROLLER", "BEGIN")
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                config.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(this),
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(applicationContext),
                )
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val localMusic = !l7DebugLogs || GalaxyMusicPlayback.localEnabled(this)
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
            localMusic = localMusic,
            videoFps = airPlayConfig.main.fps,
        )
        sink = renderer
        updateVideoCanvas(controllerGeneration, airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels)
        currentSurface?.let(::attachSurface)
        clusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            diagnosticSink = GalaxyDiagnosticSink.create(applicationContext),
            localMediaAudioEnabled = localMusic,
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphone(this)) {
                com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext)
            } else {
                null
            },
        )
        controller = next
        GalaxyMediaKeys.attach(this, next, renderer::resumeMediaAudioFocus, renderer::isAssistantAudioActive)
        renderer.setMediaAudioChangedListener { active -> GalaxyMediaKeys.onMediaAudioChanged(next, active) }
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, size.width, size.height)
        sessionDisplay = display
        videoCanvasSize = null
        videoView?.let { updateVideoViewport(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        val audioContext = applicationContext
        next.audioConnectionListener = { active -> CarPlayBackgroundSession.setBluetoothMediaActive(next, audioContext, active) }
        try {
            L7WiredDiagnostics.event(this, wiredAttempt, "SERVICE", "BEGIN")
            startForegroundService(Intent(this, DiPlaySessionService::class.java))
            next.start()
            L7WiredDiagnostics.event(this, wiredAttempt, "CONTROLLER", "STARTED")
        } catch (error: RuntimeException) {
            L7WiredDiagnostics.event(this, wiredAttempt, "CONTROLLER", "FAILED", error, "FAILED")
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // 窗口回到原尺寸可能取消待处理变化，资源拆除完成后仍需恢复正常启动。
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // 等待期间旧会话可能已经拆除，即使尺寸未变也要重新检查启动条件。
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        L7WiredDiagnostics.event(this, wiredAttempt, "DISPLAY", "READY")
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // 原窗口范围内的缩小与恢复只调整显示；超过启动窗口时才重新协商画布。
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoViewport(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        // 环视可能把窗口变窄，不能用窗口宽高比例判断屏幕发生了旋转。
        if (display.rotation != displayRotation() || display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0 && AirPlayPersistence.loadAdaptPipResolution(this)) {
            val aspectDiff = kotlin.math.abs((newSize.width.toDouble() / newSize.height) / (display.width.toDouble() / display.height) - 1.0)
            if (aspectDiff > 0.08) return true
        }
        return false
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || L7AppExit.exiting || !L7Agreement.canUse(this)) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: run { recordWiredWait("DISPLAY"); return }
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            recordWiredWait(when {
                !transportReady -> "VPN"
                !locationReady -> "LOCATION"
                !microphonePermissionResolved -> "MICROPHONE"
                pendingDisplaySize != null -> "DISPLAY_SETTLE"
                controller != null -> "EXISTING_CONTROLLER"
                else -> "LIFECYCLE"
            })
            return
        }
        wiredStartupWait = null
        if (l7DebugLogs && audioModelConfirmation.ensure { maybeStartCarPlay() }) return
        startCarPlay(size)
    }

    private fun recordWiredWait(reason: String) {
        if (wiredAttempt == null || reason == wiredStartupWait) return
        wiredStartupWait = reason
        L7WiredDiagnostics.event(this, wiredAttempt, "STARTUP_WAIT", reason)
    }

    private val startupRecovery = GalaxyWirelessRecovery()

    private fun reconnectAfterLoss(reason: String, startupFailure: com.shilapi.xcertplay.network.WirelessStartupFailure? = null) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRecovery.stopped) return
        if (reconnectScheduled) return
        val startupDelay = startupFailure?.let { failure ->
            when (val decision = startupRecovery.failed(restartGeneration, failure)) {
                is GalaxyWirelessRecovery.Decision.Retry -> decision.delayMillis
                GalaxyWirelessRecovery.Decision.Ignore -> return
                GalaxyWirelessRecovery.Decision.Stop -> {
                    (connectionPanel as? L7ConnectionPanel)?.retryButton?.visibility = View.VISIBLE
                    setConnectionStage("$reason\n${getString(R.string.wireless_startup_retries_exhausted)}")
                    appendLog("Wireless recovery stopped retries=${startupRecovery.retries}")
                    return
                }
            }
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) startupDelay else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration || startupRecovery.stopped
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        startupRecovery.disconnected()
        appendLog(reason)
        activeScreenStreamTypes.clear()
        pendingVideoFailure = null
        videoRecoveryPanel?.recovered()
        videoFailureDialog?.dismiss()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        releaseVideoTouches()
        oldSink?.let(::detachVideoOwner)
        GalaxyMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        sessionDisplay = null
        videoCanvasSize = null
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    if (!clean) {
                        appendLog("Connection replacement blocked reason=previous_resources_not_released")
                        setConnectionStage(getString(R.string.galaxy_connection_release_pending))
                        return@runOnUiThread
                    }
                    handshakeResetInProgress = false
                    // 关闭旧会话期间窗口或权限可能变化，使用稳定后的当前尺寸重新检查。
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        releaseVideoTouches()
        startActivity(Intent(this, GalaxySettingsActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSettingsMenu() = showDiPlayHome("settings")

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        L7WiredDiagnostics.event(this, wiredAttempt, "STOP", "BEGIN", outcome = "CANCELLED")
        if (terminateProcess) L7StartupGuard.stopped()
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        restartGeneration += 1
        pendingVideoFailure = null
        videoRecoveryPanel?.recovered()
        videoFailureDialog?.dismiss()
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        releaseVideoTouches()
        oldSink?.let(::detachVideoOwner)
        GalaxyMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        sessionDisplay = null
        videoCanvasSize = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun releaseVideoTouches() {
        touchTracker.reset()
        controller?.sendTouch(emptyList())
    }

    private fun updateVideoCanvas(generation: Int, width: Int, height: Int) {
        runOnUiThread {
            if (isDestroyed || shuttingDown.get() || generation != restartGeneration || width <= 0 || height <= 0) return@runOnUiThread
            val size = DisplaySize(width, height)
            if (videoCanvasSize == size) return@runOnUiThread
            releaseVideoTouches()
            videoCanvasSize = size
            videoView?.let { updateVideoViewport(it.width, it.height) }
        }
    }

    private fun updateVideoViewport(width: Int, height: Int) {
        val video = videoView ?: return
        if (width <= 0 || height <= 0) return
        val canvas = videoCanvasSize ?: sessionDisplay?.let { DisplaySize(it.width, it.height) }
            ?: DisplaySize(width, height)
        val viewport = VideoViewport.fit(width, height, canvas.width, canvas.height)
        videoViewport = viewport
        softwareVideo?.layout(viewport)
        (video as? TextureView)?.isOpaque = viewport.left == 0.0 && viewport.top == 0.0
        (video as? TextureView)?.setTransform(Matrix().apply {
            setScale((viewport.width / width).toFloat(), (viewport.height / height).toFloat())
            postTranslate(viewport.left.toFloat(), viewport.top.toFloat())
        })
    }

    private fun retireVideoSurface(surface: Surface, texture: SurfaceTexture?, releaseSurface: Boolean = true) {
        if (texture != null) retiringTextures.add(texture)
        val owners = surfaceOwners.remove(surface).orEmpty().toList()
        val release = {
            mainHandler.post { if (releaseSurface) surface.release(); texture?.release() }
            Unit
        }
        if (owners.isEmpty()) { release(); return }
        val remaining = java.util.concurrent.atomic.AtomicInteger(owners.size)
        owners.forEach { owner ->
            owner.detachSurface(surface) { if (remaining.decrementAndGet() == 0) release() }
        }
    }

    /** 会话退出也先解除目标，完成后清除持有者记录，避免多次重连积累引用。 */
    private fun detachVideoOwner(owner: AndroidMediaSink) {
        surfaceOwners.filterValues { owner in it }.keys.toList().forEach { surface ->
            owner.detachSurface(surface) {
                mainHandler.post {
                    surfaceOwners[surface]?.let { owners ->
                        owners.remove(owner)
                        if (owners.isEmpty()) surfaceOwners.remove(surface)
                    }
                }
            }
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!texture.isAttachedToWindow) true else {
                removeVideoWindowProbe()
                if (!isDestroyed && videoView === texture && !texture.isHardwareAccelerated) {
                    useSoftwareVideoOutput(texture)
                    false
                } else true
            }
        }
        videoWindowProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoWindowProbe() {
        videoWindowProbe?.let { probe ->
            videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        }
        videoWindowProbe = null
    }

    private fun useSoftwareVideoOutput(texture: TextureView) {
        if (videoView !== texture || softwareVideo != null || isDestroyed) return
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        currentSurface?.let { retireVideoSurface(it, currentSurfaceTexture) }
        currentSurface = null
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        lateinit var output: GalaxySoftwareVideoOutput
        output = GalaxySoftwareVideoOutput(this, created = { surface ->
            if (!isDestroyed && softwareVideo === output) {
                currentSurface = surface
                attachSurface(surface)
            }
        }, destroyed = { surface ->
            if (currentSurface === surface) {
                currentSurface = null
                retireVideoSurface(surface, null, releaseSurface = false)
            }
        }, resized = { width, height ->
            if (softwareVideo === output) {
                updateVideoViewport(width, height)
                scheduleDisplaySize(width, height)
            }
        })
        softwareVideo = output
        videoView = output.viewport
        root.removeView(texture)
        root.addView(output.viewport, index, texture.layoutParams)
        appendLog("Video output mode=SURFACE windowHardwareAccelerated=false pictureAdjustments=false")
        android.widget.Toast.makeText(this, R.string.galaxy_software_video_note, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun attachSurface(surface: Surface) {
        sink?.let { owner -> surfaceOwners.getOrPut(surface) { mutableSetOf() }.add(owner) }
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    releaseVideoTouches()
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val viewport = videoViewport ?: VideoViewport.fit(view.width, view.height, view.width, view.height)
        val contacts = touchTracker.contacts(event, viewport)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = if (message == wiredStartupFeedback) message else friendlyStage(message)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun friendlyStage(message: String): String = when {
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("unsupported", true) || message.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        val line = formattedLogLine(safe, System.currentTimeMillis())
        if (l7DebugLogs) L7DebugLog.buffer.append(line)
        AsyncDiagnosticLog.append(sessionLog, message)
    }

    private fun appendFileLog(message: String) {
        val line = formattedLogLine(message, System.currentTimeMillis())
        if (l7DebugLogs) L7DebugLog.buffer.append(line)
        AsyncDiagnosticLog.append(sessionLog, message)
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.WirelessActiveFallback -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> getString(R.string.status_failed, message)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
