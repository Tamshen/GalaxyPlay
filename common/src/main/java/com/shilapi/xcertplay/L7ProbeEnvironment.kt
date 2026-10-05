package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** 基础检查只读取自身与公开状态；不初始化厂商服务、不请求授权、不访问认证后端。 */
internal object L7ProbeEnvironment {
    fun window(activity: Activity): Map<String, String?> {
        val decor = activity.window.decorView
        val metrics = activity.applicationContext.resources.displayMetrics
        val session = CarPlayBackgroundSession.snapshot()
        return mapOf("resourcePixels" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "systemDensityDpi" to metrics.densityDpi.toString(),
            "uiDensityDpi" to L7UiDensity.value(activity).toString(),
            "windowPixels" to "${decor.width}x${decor.height}",
            "displayId" to decor.display?.displayId?.toString(),
            "projectionBuffer" to session?.let { "${it.width}x${it.height}" },
            "sessionActive" to CarPlayBackgroundSession.active.toString())
    }

    fun identity(context: Context): Map<String, String?> {
        val app = context.applicationInfo
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return mapOf("packageName" to context.packageName, "appVersion" to info.versionName,
            "versionCode" to info.longVersionCode.toString(), "uid" to Process.myUid().toString(),
            "pid" to Process.myPid().toString(), "userHandle" to Process.myUserHandle().toString(),
            "minSdk" to app.minSdkVersion.toString(),
            "targetSdk" to app.targetSdkVersion.toString(),
            "systemApp" to (app.flags and ApplicationInfo.FLAG_SYSTEM != 0).toString(),
            "privilegedApp" to null, "privilegedAppReason" to "NO_PUBLIC_QUERY",
            "debuggable" to (app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0).toString(),
            "signerSha256" to info.signingInfo?.apkContentsSigners?.joinToString(",") { hash(it.toByteArray()) })
    }

    fun generation(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val special = L7SpecialAccessProbe(context)
        val access = listOf("WRITE_SETTINGS", "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS",
            "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE").map { suffix ->
            val name = "android.permission.$suffix"
            val granted = runCatching { context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }.getOrNull()
            special.inspect(name, granted).toString()
        }
        // 只用于识别历史环境变化；原始构建串不写入报告。
        val input = listOf(Build.FINGERPRINT, context.packageName, info.longVersionCode.toString(),
            context.applicationInfo.uid.toString(), info.requestedPermissionsFlags?.joinToString(),
            identity(context)["signerSha256"], File(context.applicationInfo.sourceDir).lastModified().toString(),
            access.joinToString(), "permission-vendor-probe-v5")
        return hash(input.joinToString("\u0000").toByteArray())
    }

    fun queries(context: Context, window: Map<String, String?>): List<Pair<String, () -> Map<String, String?>>> = listOf(
        "ENV-SYSTEM" to { mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "android" to Build.VERSION.RELEASE, "api" to Build.VERSION.SDK_INT.toString(),
            "abi" to Build.SUPPORTED_ABIS.joinToString(","), "securityPatch" to Build.VERSION.SECURITY_PATCH) },
        "ENV-APP" to { identity(context) },
        "ENV-APK" to {
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            mapOf("baseApkSha256" to hex(digest.digest()))
        },
        "ENV-WINDOW" to { window },
        "ENV-LIBRARIES" to { mapOf("visibleSharedLibraries" to
            context.packageManager.systemSharedLibraryNames.orEmpty().take(100).joinToString(",")) },
        "ENV-PACKAGES" to {
            val catalog = JSONObject(context.assets.open("l7-permission-catalog.json").bufferedReader().use { it.readText() })
            val sources = catalog.getJSONArray("sources")
            val names = (0 until sources.length()).mapNotNull { index ->
                sources.getJSONObject(index).let { if (it.isNull("package")) null else it.getString("package") }
            }.distinct()
            names.associateWith { name ->
                runCatching {
                    val info = context.packageManager.getPackageInfo(name, 0)
                    "versionCode=${info.longVersionCode},identity=${if (name == context.packageName) "CURRENT_APP" else "REFERENCE_UNKNOWN"}"
                }.getOrElse { "NOT_VISIBLE_OR_UNINSTALLED" }
            }
        },
        "ENV-EXECUTOR" to {
            mapOf("executorContext" to "L7_APP", "uid" to Process.myUid().toString(),
                "selinuxContext" to runCatching { File("/proc/self/attr/current").readText().trim().trimEnd('\u0000') }.getOrNull(),
                "selinuxEnforcing" to runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull(),
                "shellSession" to "NOT_CONNECTED", "rootSession" to "NOT_CHECKED", "hookSession" to "NOT_CONNECTED")
        },
        "ENV-SDK-CONTRACT" to { L7SdkContractProbe.inspect(context) },
        "ENV-MEDIA-SESSIONS" to {
            val sessions = context.getSystemService(android.media.session.MediaSessionManager::class.java).getActiveSessions(null)
            val own = sessions.filter { it.packageName == context.packageName }
            mapOf("activeSessionCount" to sessions.size.toString(), "ownSessionCount" to own.size.toString(),
                "ownPlaybackStates" to own.joinToString(",") { it.playbackState?.state?.toString() ?: "UNKNOWN" },
                "effectiveCall" to "QUERY_ONLY")
        },
        "ENV-AUDIO" to {
            val devices = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_ALL)
            mapOf("visibleDeviceCount" to devices.size.toString(), "deviceTypes" to devices.take(32)
                .joinToString(";") { "type=${it.type},input=${it.isSource},output=${it.isSink}" })
        },
        "ENV-RUNTIME" to {
            val audio = context.getSystemService(AudioManager::class.java)
            mapOf("videoPreference" to if (AirPlayPersistence.loadHevcEnabled(context)) "HEVC" else "H264",
                "fpsPreference" to AirPlayPersistence.loadFps(context).toString(),
                "displayScalePreference" to AirPlayPersistence.loadDisplayScaleTenths(context).toString(),
                "connectionPreference" to if (AirPlayPersistence.loadWirelessEnabled(context)) "WIRELESS" else "USB",
                "authenticationBackend" to AirPlayPersistence.loadMfiTarget(context).toString(),
                "bluetoothExclusivePreference" to AirPlayPersistence.loadBluetoothMediaExclusive(context).toString(),
                "bluetoothGuardState" to L7BluetoothAudioSettings.status.name,
                "audioMode" to audio.mode.toString(), "musicActive" to audio.isMusicActive.toString(),
                "sessionPresent" to CarPlayBackgroundSession.hasSession().toString())
        },
        "ENV-NETWORK" to {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            mapOf("activeNetworkVisible" to (caps != null).toString(),
                "wifi" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?.toString(),
                "vpn" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?.toString())
        },
        "ENV-USB" to {
            val manager = context.getSystemService(UsbManager::class.java)
            val devices = manager.deviceList.values.take(32)
            mapOf("usbHostFeature" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST).toString(),
                "visibleDevices" to devices.joinToString(";") {
                    "vid=${it.vendorId},pid=${it.productId},authorized=${manager.hasPermission(it)}"
                })
        },
        "ENV-OVERLAY" to { mapOf("allowed" to Settings.canDrawOverlays(context).toString()) },
        "ENV-WRITE-SETTINGS" to { mapOf("allowed" to Settings.System.canWrite(context).toString()) },
    )

    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
