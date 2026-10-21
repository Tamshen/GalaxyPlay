package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/** USB 能力查询与角色切换的两个请求；不会调用现有 0x52、USBMUX 或再次申请权限。 */
internal class GalaxyUsbVerificationPhone(
    private val manager: UsbManager,
    private val device: UsbDevice,
    report: (String) -> Unit,
) : GalaxyUsbVerificationAttempt.Phone {
    private val report: (String) -> Unit = { message -> try { report(message) } catch (_: Exception) { } }
    private var connection: UsbDeviceConnection? = null
    override fun capability(): Boolean = try {
        if (!manager.hasPermission(device)) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_UNAVAILABLE)
        val opened = manager.openDevice(device)
            ?: throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_UNAVAILABLE)
        connection = opened
        val bytes = ByteArray(4)
        val count = opened.controlTransfer(0xc0, 0x53, 0, 0, bytes, 4, 1000)
        val accepted = supported(count, bytes)
        report("USB_VERIFY stage=PHONE_CAPABILITY_REPLY request=0x53 returned=$count supported=$accepted")
        accepted
    } catch (_: Exception) { throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_UNAVAILABLE) }
    override fun switchRole(): Int = try {
        connection?.controlTransfer(0x40, 0x51, 1, 0, null, 0, 1000) ?: -1
    } catch (_: Exception) { throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.PHONE_ROLE_REJECTED) }
    override fun close() { val owned = connection; connection = null; owned?.close() }
    companion object {
        internal fun supported(count: Int, bytes: ByteArray): Boolean = count == 4 && bytes.size >= 4 &&
            (bytes[0].toInt() and 255) + ((bytes[1].toInt() and 255) shl 8) == 1
    }
}
