package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** 显式一次采证，复用环境报告、工作线程和日志；不申请权限或切换角色。 */
internal class GalaxyUsbEnvironmentProbe(private val context: Context) {
    data class Query(val id: String, val source: String = "EXPLICIT_QUERY", val run: () -> Map<String, String?>)
    private val files = GalaxyUsbFileEvidence()
    private val platform = GalaxyUsbPlatformEvidence(context, files)

    fun queries(): List<Query> = buildList {
        fun add(id: String, source: String, read: () -> Map<String, String?>) {
            add(Query("USB-$id", source, read))
        }
        add("SYSTEM", "ANDROID_BUILD") { mapOf("api" to Build.VERSION.SDK_INT.toString(),
            "android" to Build.VERSION.RELEASE, "abi" to Build.SUPPORTED_ABIS.joinToString(",")) }
        add("IDENTITY", "ANDROID_APP") { L7ProbeEnvironment.identity(context) }
        add("BLUETOOTH", "USB_STACK_REQUIRES_ADAPTER") {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            mapOf("usbVisibility" to if (adapter == null) "NOT_VISIBLE_AT_PATH" else "OBSERVED",
                "usbObserved" to "adapter=${adapter != null},enabled=${adapter?.isEnabled}",
                "usbEffectiveCall" to "ADAPTER_STATE_ONLY_NO_ADDRESS_NO_DISCOVERY")
        }
        add("USB", "USB_MANAGER") { GalaxyUsbVerificationSnapshot.usb(context).facts }
        add("PORTS", "USB_PORT_STATUS") { platform.ports() }
        add("NETWORK", "NETWORK_INTERFACES") { GalaxyUsbVerificationSnapshot.network() }
        listOf("sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "sys.usb.configfs", "carplay.config.usb",
            "vendor.gwm.carplay.plugNCM", "init.svc.adbd", "init.svc.apvp", "init.svc.apvpd",
            "init.svc.transfer", "init.svc.usbd", "sys.usb.controller", "sys.usb.ffs.ready", "persist.vendor.usb.config", "vendor.usb.config").forEachIndexed { i, key ->
            add("PROPERTY-$i", key) { platform.property(key) }
        }
        listOf("/dev/ipodout0", "/dev/i2c-7", "/dev/usb-ffs/iap", "/dev/usb_accessory", "/dev/bus/usb").forEachIndexed { i, path ->
            add("NODE-$i", path) { files.stat(path) }
        }
        listOf("/sys/class/udc" to "UDC", "/sys/class/usb_role" to "ROLE", "/sys/class/typec" to "TYPEC",
            "/config/usb_gadget" to "GADGET", "/sys/kernel/config/usb_gadget" to "GADGET").forEachIndexed { i, (path, kind) ->
            add("SYSFS-$i", path) { platform.directory(path, kind) }
        }
        listOf("functions", "state", "enable").forEach { leaf ->
            add("LEGACY-$leaf", "/sys/class/android_usb/android0/$leaf") {
                files.tokenFile("/sys/class/android_usb/android0/$leaf", GalaxyUsbPlatformEvidence.functionsAllowed +
                    setOf("0", "1", "CONNECTED", "DISCONNECTED", "CONFIGURED"))
            }
        }
        listOf("/init.usb.rc", "/init.usb.configfs.rc", "/vendor/etc/init/hw/init.qcom.usb.rc", "/vendor/etc/init/hw/init.qcom.usb.configfs.rc",
            "/system/etc/ueventd.rc", "/vendor/ueventd.rc", "/vendor/etc/ueventd.rc").forEachIndexed { i, path ->
            add("RULE-$i", path) { platform.initRules(path) }
        }
        add("RULE-DISCOVERY", "INIT_CANDIDATES_MAX16") { platform.discoveredRules() }
        add("PROPERTY-CONTEXTS", "SELINUX_PROPERTY_CANDIDATES") {
            val sources = listOf("/system/etc/selinux/plat_property_contexts", "/vendor/etc/selinux/vendor_property_contexts")
            mapOf("usbObserved" to sources.mapIndexed { i, path ->
                val evidence = GalaxyUsbFileEvidence.markers(files.read(path, 131072), GalaxyUsbPlatformEvidence.markers)
                "$i:$evidence"
            }.joinToString(";"), "usbEffectiveCall" to "READ_MARKERS_ONLY_NOT_AUTHORIZATION_PROOF")
        }
        add("KERNEL", "/proc/config.gz") { platform.kernel() }
        add("MODULES", "/proc/modules") { GalaxyUsbFileEvidence.markers(files.read("/proc/modules", 65536),
            mapOf("IAP" to "iap", "IPOD" to "ipod", "NCM" to "ncm", "CONFIGFS" to "configfs", "DWC3" to "dwc3")) }
        add("SELINUX", "/sys/fs/selinux/enforce") { files.tokenFile("/sys/fs/selinux/enforce", setOf("0", "1")) }
        add("DOMAIN", "/proc/self/attr/current") {
            val read = files.read("/proc/self/attr/current", 512)
            mapOf("usbVisibility" to read.state, "usbObserved" to read.text.split(':').getOrNull(2)?.takeIf {
                it.matches(Regex("[a-zA-Z0-9_]{1,64}")) })
        }
        listOf("libcarplay_platform.so", "libapvp-api.so", "libtransfer-system.so", "libtransfer-client-system.so",
            "libgrpc++.so", "libprotobuf-cpp-full.so", "libc++.so").forEachIndexed { i, name ->
            add("LIBRARY-$i", "USB_ELF_DEPENDENCY") { platform.libraries(name) }
        }
        add("APVP", "USB_LOOPBACK_PORT_40005") { platform.listener() }
        add("ROUTES", "/proc/net/ipv6_route") {
            val read = files.read("/proc/net/ipv6_route", 32768)
            mapOf("usbVisibility" to read.state, "usbCount" to read.text.lineSequence().count { it.trim().endsWith(" usb0") }.toString(),
                "usbTruncated" to read.truncated.toString(), "usbEffectiveCall" to "ROUTE_COUNT_ONLY_NO_ADDRESSES")
        }
        listOf("android.permission.MANAGE_USB", "android.permission.NETWORK_SETTINGS", "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.CONTROL_VPN", "android.permission.BLUETOOTH_CONNECT").forEachIndexed { i, permission ->
            add("PERMISSION-$i", permission) {
                val item = L7PermissionProbe(context).inspect(permission)
                item.facts + ("usbVisibility" to if (item.result == L7ProbeOutcome.DENIED) "DENIED" else "OBSERVED")
            }
        }
        add("BOUNDARIES", "STATIC_USB_REFERENCE") { mapOf("usbVisibility" to "UNVERIFIED",
            "usbRoleSwitch" to "NOT_ATTEMPTED", "usbControlChannel" to "NOT_OPENED", "usbHandshake" to "NOT_ATTEMPTED",
            "usbServiceIdentity" to "UNVERIFIED", "usbFirstFrame" to "NOT_TESTED", "usbRecovery" to "NOT_TESTED",
            "usbEffectiveCall" to "NO_USB_TRANSFER_NO_PROPERTY_WRITE_NO_SIGNAL_NO_LIBRARY_LOAD",
            "usbObserved" to "CANDIDATE_NAMES_OR_MISSING_PATHS_DO_NOT_PROVE_SUPPORT;MFI_BACKEND_REMAINS_SEPARATE") }
    }

    fun inspect(query: Query): L7ProbeItem {
        val started = SystemClock.elapsedRealtime()
        val facts = try { query.run() } catch (e: Exception) {
            mapOf("usbVisibility" to GalaxyUsbFileEvidence.failure(e), "exceptionType" to e.javaClass.simpleName)
        } catch (e: LinkageError) { mapOf("usbVisibility" to "API_UNAVAILABLE", "exceptionType" to e.javaClass.simpleName) }
        val state = facts["usbVisibility"].orEmpty()
        return L7ProbeItem(query.id, query.id, "CARPLAY_ENV", when {
            state == "DENIED" -> L7ProbeOutcome.DENIED
            state.startsWith("UNAVAILABLE") || state in setOf("API_UNAVAILABLE", "UNVERIFIED", "EMPTY_OR_HIDDEN") -> L7ProbeOutcome.UNKNOWN
            state == "NOT_VISIBLE_AT_PATH" -> L7ProbeOutcome.NOT_OBSERVED
            else -> L7ProbeOutcome.OBSERVED
        }, "USB_EVIDENCE_ONLY", mapOf("usbSource" to query.source, "usbScope" to "APP_UID_READ_ONLY") + facts +
            mapOf("usbDurationMs" to (SystemClock.elapsedRealtime() - started).toString()))
    }

    /** 20 秒观察只枚举与接收系统广播；每秒发布一条，同次运行最多 21 条。 */
    fun observe(active: () -> Boolean, publish: (L7ProbeItem) -> Unit, durationMs: Long = 20_000) {
        val attached = AtomicInteger(); val detached = AtomicInteger(); val configured = AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> attached.incrementAndGet()
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> detached.incrementAndGet()
                    "android.hardware.usb.action.USB_STATE" -> configured.incrementAndGet()
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction("android.hardware.usb.action.USB_STATE")
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }.isSuccess
        val started = SystemClock.elapsedRealtime()
        var previous: Set<String>? = null
        try {
            for (sample in 0..20) {
                if (!active()) break
                val target = started + minOf(sample * 1000L, durationMs)
                while (active() && SystemClock.elapsedRealtime() < target) Thread.sleep(50)
                if (!active()) break
                publish(inspect(Query("USB-OBSERVE-$sample") {
                    val usb = runCatching { GalaxyUsbVerificationSnapshot.usb(context) }
                    val current = usb.getOrNull()
                    val changes = current?.let { now -> previous?.let { old ->
                        "added=${(now.devices - old).size},removed=${(old - now.devices).size}"
                    } ?: "baseline" } ?: "unknown"
                    previous = current?.devices
                    mapOf("usbSource" to "TIMED_ENUMERATION_AND_BROADCAST", "usbScope" to "APP_UID_READ_ONLY",
                        "usbSample" to sample.toString(), "usbElapsedMs" to (SystemClock.elapsedRealtime() - started).toString(),
                        "usbReceiver" to registered.toString(), "usbEvents" to "attach=${attached.get()},detach=${detached.get()},state=${configured.get()}",
                        "usbChanges" to changes, "usbState" to runCatching { platform.property("sys.usb.state")["usbObserved"] }.getOrNull(),
                        "usbUdcState" to runCatching { platform.directory("/sys/class/udc", "UDC").toString() }.getOrDefault("UNAVAILABLE"),
                        "usbLegacyState" to files.tokenFile("/sys/class/android_usb/android0/state",
                            setOf("CONNECTED", "DISCONNECTED", "CONFIGURED")).toString()) +
                        (current?.facts ?: mapOf("usbQuery" to "UNAVAILABLE")) +
                        runCatching { GalaxyUsbVerificationSnapshot.network() }.getOrElse { mapOf("usbNetwork" to "UNAVAILABLE") }
                }))
                if (SystemClock.elapsedRealtime() - started >= durationMs) break
            }
        } finally { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }

    companion object {
        const val GROUP = "CARPLAY_ENVIRONMENT"
        fun selected(id: String?) = id == GROUP || id?.startsWith("USB-") == true
        val factKeys = setOf("usbVisibility", "usbMode", "usbOwner", "usbNodeType", "usbAccessRead", "usbAccessWrite", "usbEffectiveCall",
            "usbSource", "usbScope", "usbObserved", "usbOtherCount", "usbTruncated", "usbCount", "usbLibrary", "usbDeviceCount",
            "usbAppleCount", "usbDescriptors", "usbNetwork", "usbInterfacePresent", "usbServiceIdentity", "usbRoleSwitch", "usbControlChannel",
            "usbHandshake", "usbFirstFrame", "usbRecovery", "usbDurationMs", "usbSample", "usbElapsedMs", "usbReceiver", "usbEvents",
            "usbChanges", "usbState", "usbQuery", "usbUdcState", "usbLegacyState")
    }
}
