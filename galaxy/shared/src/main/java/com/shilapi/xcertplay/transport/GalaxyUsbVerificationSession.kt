package com.shilapi.xcertplay.transport

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.net.Inet6Address
import java.net.NetworkInterface

/** 本次连接独占角色事务及原始 iAP 管道，协议层仍由 GalaxyPlay 拥有。 */
class GalaxyUsbVerificationSession(
    context: Context,
    manager: UsbManager,
    device: UsbDevice,
    private val cancelled: () -> Boolean,
    report: (String) -> Unit,
) : Closeable {
    private val report: (String) -> Unit = { message -> try { report(message) } catch (_: Exception) { } }
    data class Network(val address: Inet6Address, val mac: ByteArray)
    private val attempt = GalaxyUsbVerificationAttempt(GalaxyUsbGadgetPlatform(context, cancelled, report),
        GalaxyUsbVerificationPhone(manager, device, report), cancelled, report)
    fun open(): BlockingDuplexByteStream = attempt.open()
    fun awaitNetwork(): Network {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            if (cancelled()) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.CANCELLED)
            val iface = NetworkInterface.getByName("usb0")
            val address = iface?.inetAddresses?.toList()?.filterIsInstance<Inet6Address>()
                ?.firstOrNull { it.isLinkLocalAddress }
            val mac = iface?.hardwareAddress
            if (iface != null && iface.isUp && address != null && mac?.size == 6) {
                report("USB_VERIFY stage=NETWORK_READY interface=usb0 ipv6LinkLocal=true macAvailable=true vpn=false")
                return Network(Inet6Address.getByAddress(null, address.address, iface), mac)
            }
            Thread.sleep(200)
        }
        throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.NETWORK_NOT_READY)
    }
    override fun close() = attempt.close()
    companion object {
        fun needsRecovery(context: Context): Boolean = runCatching {
            GalaxyUsbVerificationPreferences.journal(context).contains("original")
        }.getOrDefault(true)
        fun recover(context: Context, report: (String) -> Unit) = GalaxyUsbGadgetPlatform.recover(context, report)
    }
}
