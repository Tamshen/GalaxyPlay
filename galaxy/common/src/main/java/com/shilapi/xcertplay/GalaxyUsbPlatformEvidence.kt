package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** 参考样本已确认的依赖与候选线索分开采集；所有动态文本先缩减为固定标记。 */
internal class GalaxyUsbPlatformEvidence(private val context: Context, private val files: GalaxyUsbFileEvidence) {
    fun property(name: String): Map<String, String?> {
        val raw = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
        val known = setOf("iap", "ncm", "adb", "diag", "mtp", "rndis", "none", "ptp", "accessory", "audio_source",
            "midi", "0", "1", "running", "stopped", "restarting", "up", "down")
        val words = raw.split(',')
        return mapOf("usbVisibility" to if (raw.isEmpty()) "EMPTY_OR_HIDDEN" else "READ",
            "usbObserved" to words.filter { it in known }.joinToString(","),
            "usbOtherCount" to words.count { it.isNotEmpty() && it !in known }.toString(), "usbEffectiveCall" to "PROPERTY_GET_ONLY")
    }

    fun ports(): Map<String, String?> {
        val manager = context.getSystemService(UsbManager::class.java) ?: error("unavailable")
        val ports = manager.javaClass.getMethod("getPorts").invoke(manager) as? List<*> ?: error("invalid")
        val values = ports.take(8).mapIndexed { i, port ->
            requireNotNull(port)
            val type = port.javaClass
            val status = type.getMethod("getStatus").invoke(port)
            fun value(name: String): String = if (status == null) "unknown" else
                runCatching { requireNotNull(status).javaClass.getMethod(name).invoke(status).toString().take(12) }.getOrDefault("unknown")
            "slot=$i,supportedModes=${type.getMethod("getSupportedModes").invoke(port)}," +
                "currentMode=${value("getCurrentMode")},dataRole=${value("getCurrentDataRole")},powerRole=${value("getCurrentPowerRole")}" +
                ",connected=${value("isConnected")}"
        }
        return mapOf("usbVisibility" to "OBSERVED", "usbObserved" to values.joinToString(";"),
            "usbCount" to ports.size.toString(), "usbEffectiveCall" to "PORT_STATUS_ONLY")
    }

    fun directory(path: String, kind: String): Map<String, String?> {
        val (state, names) = files.list(path)
        val facts = linkedMapOf<String, String?>("usbVisibility" to state, "usbCount" to names.size.toString())
        val evidence = names.mapIndexed { slot, name ->
            val attributes = when (kind) {
                "UDC" -> listOf("state", "current_speed", "maximum_speed")
                "ROLE" -> listOf("role")
                "TYPEC" -> listOf("data_role", "power_role", "port_type", "preferred_role")
                "GADGET" -> listOf("UDC")
                else -> emptyList()
            }
            val rows = attributes.map { attr ->
                val read = files.read("$path/$name/$attr", 128)
                val safe = read.text.trim().takeIf { raw -> raw in tokens ||
                    raw.split(Regex("[\\s\\[\\]]+")).filter { it.isNotBlank() }.all { it in tokens } }.orEmpty()
                "$attr=${read.state}:${if (kind == "GADGET" && read.text.isNotBlank()) "BOUND" else safe}"
            }.toMutableList()
            if (kind == "GADGET") {
                val (visible, functions) = files.list("$path/$name/functions", 32)
                rows += "functions=$visible:" + functions.map { it.substringBefore('.') }.filter { it in functionsAllowed }.distinct().joinToString(",")
                val (configsState, configs) = files.list("$path/$name/configs", 8)
                rows += "configs=$configsState:${configs.size}"
                configs.forEachIndexed { index, config ->
                    val (linksState, links) = files.list("$path/$name/configs/$config", 32)
                    rows += "config$index=$linksState:" + links.map { it.substringBefore('.') }.filter { it in functionsAllowed }.distinct().joinToString(",")
                }
            }
            "slot=$slot[${rows.joinToString(";")}]"
        }.joinToString("|")
        facts["usbObserved"] = evidence.take(1100)
        facts["usbTruncated"] = (state == "LIST_TRUNCATED" || evidence.length > 1100).toString()
        return facts
    }

    fun libraries(name: String): Map<String, String?> {
        val paths = listOf("/system/lib64", "/system_ext/lib64", "/vendor/lib64", "/product/lib64",
            "/system/lib", "/vendor/lib", context.applicationInfo.nativeLibraryDir).distinct()
        return mapOf("usbLibrary" to name, "usbObserved" to paths.mapIndexed { index, path ->
            "$index:${files.stat("$path/$name")["usbVisibility"]}"
        }.joinToString(","), "usbEffectiveCall" to "PATH_STAT_ONLY_NOT_LOADED",
            "usbSource" to "system64,system_ext64,vendor64,product64,system32,vendor32,appNative")
    }

    fun initRules(path: String): Map<String, String?> = GalaxyUsbFileEvidence.markers(files.read(path, 65536), markers)

    fun kernel(): Map<String, String?> = GalaxyUsbFileEvidence.markers(files.read("/proc/config.gz", 262144, true),
        listOf("CONFIG_USB_CONFIGFS", "CONFIG_USB_CONFIGFS_NCM", "CONFIG_USB_F_NCM", "CONFIG_USB_CONFIGFS_F_IAP",
            "CONFIG_USB_F_IAP", "CONFIG_USB_GADGET", "CONFIG_USB_ROLE_SWITCH").flatMap { key ->
            listOf("$key=y" to "$key=y", "$key=m" to "$key=m")
        }.toMap())

    fun listener(): Map<String, String?> = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 40005), 400) }
        mapOf("usbVisibility" to "TCP_ACCEPTED", "usbEffectiveCall" to "CONNECT_CLOSE_NO_PAYLOAD",
            "usbServiceIdentity" to "UNVERIFIED", "usbRoleSwitch" to "NOT_ATTEMPTED")
    } catch (e: Exception) {
        mapOf("usbVisibility" to when (e) {
            is java.net.ConnectException -> "REFUSED_OR_BLOCKED"
            is java.net.SocketTimeoutException -> "TIMEOUT"
            else -> GalaxyUsbFileEvidence.failure(e)
        }, "usbServiceIdentity" to "UNVERIFIED", "usbEffectiveCall" to "CONNECT_CLOSE_NO_PAYLOAD")
    }

    fun discoveredRules(): Map<String, String?> {
        var scanned = 0
        var matched = 0
        val found = linkedSetOf<String>()
        val visibility = mutableListOf<String>()
        listOf("/vendor/etc/init", "/vendor/etc/init/hw", "/odm/etc/init", "/system/etc/init").forEachIndexed { i, path ->
            val stat = files.stat(path)
            val all = if (stat["usbVisibility"] == "PRESENT") File(path).list() else null
            visibility += "$i:${if (all == null) stat["usbVisibility"] + ":ENUMERATION_UNAVAILABLE" else "LISTED"}"
            val candidates = all.orEmpty().filter { it.endsWith(".rc") && Regex("usb|carplay|iap|apvp|transfer", RegexOption.IGNORE_CASE).containsMatchIn(it) }.sorted()
            matched += candidates.size
            candidates.take((16 - scanned).coerceAtLeast(0)).forEach { name ->
                val read = files.read("$path/$name", 32768)
                visibility += "file${scanned++}:${read.state}:truncated=${read.truncated}"
                found += markers.filterValues { read.text.contains(it, ignoreCase = true) }.keys
            }
        }
        return mapOf("usbVisibility" to visibility.joinToString(",").take(1100), "usbCount" to scanned.toString(),
            "usbObserved" to found.joinToString(","), "usbTruncated" to (matched > scanned).toString())
    }

    companion object {
        val functionsAllowed = setOf("iap", "ncm", "ffs", "rndis", "mtp", "ptp", "accessory", "audio_source", "midi", "diag")
        val tokens = setOf("host", "device", "none", "source", "sink", "dual", "dfp", "ufp", "unknown", "default",
            "not attached", "attached", "powered", "addressed", "configured", "suspended", "full-speed", "high-speed", "super-speed", "super-speed-plus")
        val markers = mapOf("IAP_NCM" to "iap,ncm", "IPODOUT" to "ipodout0", "USB0" to "usb0", "APVP" to "apvp",
            "CARPLAY_PROPERTY" to "carplay.config.usb", "USB_CONFIG" to "sys.usb.config", "NCM_FUNCTION" to "ncm.",
            "IAP_FUNCTION" to "iap.", "APVP_PORT" to "40005")
    }
}
