package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import java.net.Inet6Address
import java.net.NetworkInterface

/** 仅保存数字描述符和地址类别；设备路径只在单次观察内用于比较，不进入报告。 */
internal object GalaxyUsbVerificationSnapshot {
    data class Snapshot(val facts: Map<String, String?>, val devices: Set<String>)
    fun usb(context: Context): Snapshot {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return Snapshot(mapOf("usbVisibility" to "SERVICE_UNAVAILABLE"), emptySet())
        val devices = manager.deviceList.values.toList()
        var budget = 32
        val details = devices.take(8).mapIndexed { index, device ->
            val configs = (0 until minOf(device.configurationCount, 8)).joinToString(";") { c ->
                val config = device.getConfiguration(c)
                val ifaces = (0 until minOf(config.interfaceCount, budget)).joinToString(",") { i ->
                    val iface = config.getInterface(i)
                    "${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol}:${iface.alternateSetting}"
                }
                budget -= minOf(config.interfaceCount, budget)
                "${config.id}[$ifaces]"
            }
            "$index:${device.vendorId}/${device.productId}:granted=${manager.hasPermission(device)}:configs=$configs"
        }.joinToString("|")
        return Snapshot(mapOf("usbVisibility" to "OBSERVED", "usbDeviceCount" to devices.size.toString(),
            "usbAppleCount" to devices.count { it.vendorId == 0x05ac }.toString(),
            "usbDescriptors" to details.take(1100), "usbTruncated" to (devices.size > 8 || budget == 0 || details.length > 1100).toString(),
            "usbEffectiveCall" to "ENUMERATE_ONLY_NO_PERMISSION_OR_TRANSFER"), devices.map { it.deviceName }.toSet())
    }

    fun network(): Map<String, String?> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val candidates = interfaces.filter { it.name.matches(Regex("(?:usb|ncm|rndis|eth|tun)\\d{1,2}")) }.take(12)
        val summary = candidates.joinToString(";") { iface ->
            val addresses = iface.inetAddresses.toList()
            "${iface.name}:up=${iface.isUp},mtu=${iface.mtu},v6ll=${addresses.count { it is Inet6Address && it.isLinkLocalAddress }}," +
                "v6other=${addresses.count { it is Inet6Address && !it.isLinkLocalAddress }},v4=${addresses.count { it !is Inet6Address }}"
        }
        return mapOf("usbVisibility" to "OBSERVED", "usbNetwork" to summary,
            "usbInterfacePresent" to candidates.any { it.name == "usb0" }.toString(),
            "usbEffectiveCall" to "INTERFACES_ONLY_NO_BIND_OR_ROUTE_CHANGE")
    }
}
