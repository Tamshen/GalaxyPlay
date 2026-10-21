package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock

/**
 * 上游模式诊断与 USB 只读能力查询；不切换主从角色或解绑驱动。
 * 在既有 USB 工作线程、已授权连接上调用；连接仍由 IphoneUsbHost 的 finally 关闭。
 */
internal object UsbModeDiagnostics {
    private const val MAX_CONFIGURATIONS = 8
    private const val MAX_INTERFACES = 32
    private const val MAX_DESCRIPTOR_CHARS = 4096
    private const val MODE_BYTES = 4
    private const val QUERY_TIMEOUT_MILLIS = 1_000

    fun traceSwitch(report: (String) -> Unit, operation: () -> Int): Int {
        emit(report, "USB mode switch event=BEGIN request=0x52 index=4 expected=1 timeoutMs=1000 phoneLock=unknown carplayPairing=unknown")
        val started = SystemClock.elapsedRealtime()
        try {
            return operation().also {
                emit(report, "USB mode switch event=RESULT returned=$it expected=1 elapsedMs=${elapsed(started)}")
            }
        } catch (error: Exception) {
            emit(report, "USB mode switch event=EXCEPTION type=${error.javaClass.simpleName} elapsedMs=${elapsed(started)}")
            throw error
        }
    }

    fun recordRejection(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        transferred: Int,
        report: (String) -> Unit,
    ) {
        // 先保存缓存中的数字描述符，拔线或查询异常时仍保留失败现场。
        val descriptors = try {
            describe(device)
        } catch (_: Exception) {
            "descriptors=unavailable"
        }
        emit(report, "USB mode rejection transferred=$transferred expected=1 $descriptors")
        if (!query(connection, 0x45, "mode", report)) return
        // USB 能力检查请求；仅补诊断，不据此选择其 0x51 角色切换或判定 NCM 可用。
        query(connection, 0x53, "capability", report)
    }

    private fun query(connection: UsbDeviceConnection, request: Int, label: String, report: (String) -> Unit): Boolean {
        val started = SystemClock.elapsedRealtime()
        val mode = ByteArray(MODE_BYTES)
        var permitted = true
        val result = try {
            // 固定 IN 请求，最多读取四个模式状态字节；不获取序列号、字符串或配对数据。
            val read = connection.controlTransfer(0xc0, request, 0, 0, mode, mode.size, QUERY_TIMEOUT_MILLIS)
            formatReply(read, mode, label)
        } catch (_: SecurityException) {
            permitted = false
            "result=unavailable reason=permission"
        } catch (_: Exception) {
            // 异常正文可能携带设备路径；诊断失败不能替代原始切换错误。
            "result=unavailable reason=query-exception"
        }
        emit(report, "USB $label query $result elapsedMs=${elapsed(started)}")
        return permitted
    }

    private fun elapsed(started: Long) = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)

    internal fun formatMode(read: Int, mode: ByteArray): String = formatReply(read, mode, "mode")

    private fun formatReply(read: Int, mode: ByteArray, label: String): String {
        val available = minOf(mode.size, MODE_BYTES)
        if (read <= 0 || read > available) return "result=unreadable returned=$read"
        val values = mode.take(read).joinToString(":") { (it.toInt() and 0xff).toString() }
        // 旧 iOS 的三字节响应有效，不能误报为不可读；只输出实际返回的字节。
        return "result=${if (read == MODE_BYTES) "complete" else "short"} returned=$read $label=$values"
    }

    private fun describe(device: UsbDevice): String {
        var remainingInterfaces = MAX_INTERFACES
        val count = device.configurationCount.coerceAtLeast(0)
        val configurations = (0 until minOf(count, MAX_CONFIGURATIONS)).joinToString(";") { index ->
            val config = device.getConfiguration(index)
            val total = config.interfaceCount.coerceAtLeast(0)
            val included = minOf(total, remainingInterfaces)
            remainingInterfaces -= included
            val interfaces = (0 until included).joinToString(",") { slot ->
                val iface = config.getInterface(slot)
                "${iface.id}/${iface.alternateSetting}:" +
                    "${iface.interfaceClass.toString(16)}.${iface.interfaceSubclass.toString(16)}." +
                    "${iface.interfaceProtocol.toString(16)}x${iface.endpointCount}"
            }
            "${config.id}[$interfaces] interfaces=$total omitted=${total - included}"
        }
        val summary = "configurations=$count omitted=${(count - MAX_CONFIGURATIONS).coerceAtLeast(0)} detail=$configurations"
        return if (summary.length <= MAX_DESCRIPTOR_CHARS) summary
        else summary.take(MAX_DESCRIPTOR_CHARS) + " truncated=true"
    }

    private fun emit(report: (String) -> Unit, message: String) {
        try {
            report(message)
        } catch (_: Exception) {
            // 使用原诊断通道，不创建文件或上传；消费失败不改变连接结果。
        }
    }
}
