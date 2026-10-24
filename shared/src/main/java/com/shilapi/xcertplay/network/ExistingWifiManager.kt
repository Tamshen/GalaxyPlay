package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("ConnectivityManager is unavailable")
    private val wifi = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var selected: Network? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var hosts: List<InetAddress> = emptyList()
    private var interfaceIndex = 0
    @Volatile private var interfaceName: String? = null
    private var callbackRegistered = false
    private var lastNudgeNs = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network == selected && !sameLink(properties)) invalidate("interface or address changed")
        }
    }

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(selected == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        // 同一局域网模式完全依赖标准 Wi-Fi 射频；若关闭则请求打开。
        // 普通 App 需要 NETWORK_SETTINGS 权限（manifest 已声明）才能真开起来，否则 setWifiEnabled 静默返回 false。
        if (!wifi.isWifiEnabled) {
            @Suppress("DEPRECATION")
            val accepted = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
            onDiagnostic("Wi-Fi radio was off; enable request accepted=$accepted")
            // 无权限时 setWifiEnabled 会静默失败，射频仍关着。此时拉起系统 Wi-Fi 设置页，
            // 让用户手动点开开关；射频一开，下面的 nudgeAssociation 会自动连回已保存网络。
            if (!wifi.isWifiEnabled) {
                openSystemWifiSettings()
            }
        }
        while (!closed) {
            // INTERNET/VALIDATED and the default route do not identify the CarPlay LAN.
            val networks = connectivity.allNetworks.filter { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
            if (networks.size > 1) throw IOException("Multiple Wi-Fi networks are active; keep one existing Wi-Fi connection")
            val network = networks.singleOrNull()
            val properties = network?.let(connectivity::getLinkProperties)
            val name = properties?.interfaceName
            val iface = name?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            val address = if (iface == null) null else properties?.let {
                wirelessHostAddress(it.linkAddresses.map { link -> link.address }, iface.index)
            }
            if (network != null && name != null && address != null) {
                val addresses = existingWifiHostAddresses(properties.linkAddresses.map { it.address }, iface!!.index)
                val capabilities = connectivity.getNetworkCapabilities(network)
                val networkInfo = if (Build.VERSION.SDK_INT >= 31) capabilities?.transportInfo as? WifiInfo else null
                // On-demand capabilities can redact SSID/BSSID even when the legacy station
                // snapshot can supply them. A non-null redacted object must not hide that input.
                @Suppress("DEPRECATION")
                val stationInfo = try { wifi.connectionInfo } catch (_: SecurityException) { null }
                val info = listOfNotNull(networkInfo, stationInfo).firstOrNull { readableSsid(it) != null }
                    ?: networkInfo ?: stationInfo
                val liveSsid = readableSsid(info)
                if (liveSsid != null && liveSsid != ssid) {
                    throw IOException("Configured Wi-Fi does not match the connected network; check Wi-Fi settings and saved details")
                }
                val frequency = info?.frequency?.takeIf { it > 0 }
                val channel = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0
                val apBssid = accessPointAddress(info?.bssid)
                val observedSecurity = if (Build.VERSION.SDK_INT >= 31 && info != null) info.currentSecurityType else -1
                if (Build.VERSION.SDK_INT >= 31) {
                    when (observedSecurity) {
                        WifiInfo.SECURITY_TYPE_OPEN -> if (passphrase.isNotEmpty()) {
                            throw IOException("Connected Wi-Fi is open; clear the saved Wi-Fi password")
                        }
                        WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_SAE -> if (passphrase.isEmpty()) {
                            throw IOException("Connected Wi-Fi is secured; enter its WPA2 password")
                        }
                        WifiInfo.SECURITY_TYPE_UNKNOWN -> Unit
                        else -> throw IOException("Existing Wi-Fi requires an open or WPA2-Personal network")
                    }
                }
                synchronized(lock) {
                    check(!closed) { "ExistingWifiManager is closed" }
                    selected = network
                    host = address
                    hosts = addresses
                    interfaceIndex = iface.index
                    interfaceName = name
                    connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                    callbackRegistered = true
                }
                // Close the gap between reading the link and registering the callback.
                val current = connectivity.getLinkProperties(network)
                if (current == null || !sameLink(current) || invalidated.get()) {
                    throw IOException("Existing Wi-Fi changed during startup; connect again")
                }
                onDiagnostic("Existing Wi-Fi attached iface=$name host=${address.hostAddress} " +
                    "networkNameReadable=${liveSsid != null} channel=$channel security=${security()} " +
                    "receiverIdentity=saved")
                return WirelessHotspotInfo(ssid, passphrase, security(), channel, frequency,
                    // The router's BSSID is not this receiver's AirPlay device identity.
                    null, name, address, when {
                        frequency == null -> "Auto"
                        frequency < 2500 -> "2.4 GHz"
                        frequency < 5955 -> "5 GHz"
                        else -> "6 GHz"
                    }, WirelessHotspotBackend.EXISTING_WIFI, hostAddresses = addresses, accessPointBssid = apBssid)
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                throw IOException("Existing Wi-Fi is not connected or has no usable address. Connect both devices to the same Wi-Fi in system settings")
            }
            // 车机 Wi-Fi 掉线时系统不会自己重连，“同一局域网”模式就一直空等到超时。
            // 这里只是温和地让系统连回已保存的网络（不改任何配置、不新建网络）。
            nudgeAssociation(started)
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi attachment was cancelled")
    }

    /**
     * 车机 Wi-Fi 断开后系统不会主动连回（也不该由用户先手动点一次）。
     * 只要还没关联上，就每隔几秒请系统连回**已保存**的目标网络；找不到保存项时退回 reconnect()。
     */
    private fun nudgeAssociation(started: Long) {
        val now = System.nanoTime()
        if ((now - started) / 1_000_000 < NUDGE_GRACE_MILLIS) return
        if (lastNudgeNs != 0L && now - lastNudgeNs < NUDGE_INTERVAL_NS) return
        lastNudgeNs = now
        val requested = runCatching {
            val saved = wifi.configuredNetworks?.firstOrNull { it.SSID?.trim('"') == ssid }
            when {
                saved != null -> {
                    wifi.enableNetwork(saved.networkId, true)
                    wifi.reconnect()
                    "saved"
                }
                else -> {
                    wifi.reconnect()
                    "reconnect"
                }
            }
        }.getOrElse { error ->
            onDiagnostic("Existing Wi-Fi reconnect request failed: ${error.javaClass.simpleName}")
            return
        }
        onDiagnostic("Existing Wi-Fi not associated yet; asked the system to reconnect ($requested)")
    }

    /**
     * 引导用户到系统 Wi-Fi 设置页手动打开射频（本 App 无 NETWORK_SETTINGS 权限时的兜底）。
     * 注意：这台车机的 ACTION_WIFI_SETTINGS action 被 Flyme 的 connectivitysettings 抢注，
     * 会打开 TCAM 私有 Wi-Fi 页（不是标准 wlan0）。因此用显式 component 直连
     * com.android.car.settings 的标准 WLAN 页；失败时再退回 ACTION_WIFI_SETTINGS。
     */
    private fun openSystemWifiSettings() {
        val standard = Intent().apply {
            component = ComponentName(
                "com.android.car.settings",
                "com.android.car.settings.common.CarSettingActivities\$WifiSettingsActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(standard)
            onDiagnostic("Opened standard Wi-Fi settings (CarSettings) for manual enable")
        } catch (standardError: Exception) {
            onDiagnostic("Standard Wi-Fi settings unavailable (${standardError.javaClass.simpleName}); falling back")
            try {
                appContext.startActivity(
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onDiagnostic("Opened fallback Wi-Fi settings")
            } catch (fallbackError: Exception) {
                onDiagnostic("Failed to open Wi-Fi settings: ${fallbackError.javaClass.simpleName}")
            }
        }
    }

    private fun sameLink(properties: LinkProperties): Boolean =
        properties.interfaceName == interfaceName && properties.linkAddresses.any { it.address == host } &&
            existingWifiHostAddresses(properties.linkAddresses.map { it.address }, interfaceIndex).toSet() == hosts.toSet()

    private fun security(): Iap2WirelessSecurity =
        if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid?.removeSurrounding("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }

    private fun accessPointAddress(text: String?): ByteArray? {
        if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
            text.equals("02:00:00:00:00:00", ignoreCase = true)) return null
        return bytes
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            if (callbackRegistered) {
                connectivity.unregisterNetworkCallback(callback)
                callbackRegistered = false
            }
        }
    }
}

/** 同一局域网模式：先等 1.5 秒看系统是否自己连上，之后每 3 秒温和地请求一次重连。 */
private const val NUDGE_GRACE_MILLIS = 1_500L
private const val NUDGE_INTERVAL_NS = 3_000_000_000L
