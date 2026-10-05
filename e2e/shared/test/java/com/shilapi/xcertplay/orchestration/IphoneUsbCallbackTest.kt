package com.shilapi.xcertplay.orchestration

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/** 执行真实控制器回调与 PendingIntent 边界；不构造手机协议连接。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33], manifest = Config.NONE)
class IphoneUsbCallbackTest {
    @Test fun staleDeviceDenialDoesNotFailCurrentAttempt() = withController { controller, statuses ->
        val current = device("device-b")
        setPhase(controller, "IPHONE")
        val request = gate(controller).begin(current.deviceName)!!
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device("device-a"), request.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
        assertTrue(gate(controller).isPending(request))
    }

    @Test fun previousRunAtSamePathCannotFailNewRequest() = withController { controller, statuses ->
        val device = device("device-a")
        setPhase(controller, "IPHONE")
        val old = gate(controller).begin(device.deviceName)!!
        gate(controller).invalidate()
        val current = gate(controller).begin(device.deviceName)!!
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device, old.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
        assertTrue(gate(controller).isPending(current))
    }

    @Test fun deniedThenLateGrantedDoesNotRestartBringUp() = withController { controller, statuses ->
        val device = device("device-a")
        setPhase(controller, "IPHONE")
        val request = gate(controller).begin(device.deviceName)!!
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device, request.id))
        callback(controller, IphoneUsbHost.PermissionResult.Granted(device, request.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, statuses.size)
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertEquals("IPHONE", phase(controller))
    }

    @Test fun callbacksAfterCloseCannotChangeStatus() = withController { controller, statuses ->
        val device = device("device-a")
        setPhase(controller, "IPHONE")
        val request = gate(controller).begin(device.deviceName)!!
        controller.close()
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device, request.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun grantIsRecheckedBeforeOpeningUsbInterfaces() = withController { controller, statuses ->
        val device = device("device-a")
        setPhase(controller, "IPHONE")
        val request = gate(controller).begin(device.deviceName)!!
        callback(controller, IphoneUsbHost.PermissionResult.Granted(device, request.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertEquals("IPHONE", phase(controller))
    }

    @Test fun laterProtocolPhaseIgnoresPermissionDenial() = withController { controller, statuses ->
        val device = device("device-a")
        val request = gate(controller).begin(device.deviceName)!!
        setPhase(controller, "CONTROL")
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device, request.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun queuedDiscoveryFromPreviousRunCannotRequestPermission() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        CarPlayController::class.java.getDeclaredMethod("requestIphonePermission", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(controller, device("device-a"))
        CarPlayController::class.java.getDeclaredMethod("invalidateIphonePermission")
            .apply { isAccessible = true }.invoke(controller)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
        // 若旧任务已调用 begin，则当前同设备申请会被判为重复。
        assertNotNull(gate(controller).begin("device-a"))
    }

    @Test fun recreatedControllerUsesDifferentPermissionAction() = withController { first, _ ->
        withController { second, _ ->
            val firstHost = ReflectionHelpers.getField<IphoneUsbHost>(first, "iphoneHost")
            val secondHost = ReflectionHelpers.getField<IphoneUsbHost>(second, "iphoneHost")
            assertNotEquals(ReflectionHelpers.getField<String>(firstHost, "permissionAction"),
                ReflectionHelpers.getField<String>(secondHost, "permissionAction"))
        }
    }

    @Test fun failureQueuedBeforeReconnectCannotBeDeliveredToNewRun() = withController { controller, statuses ->
        val device = device("device-a")
        setPhase(controller, "IPHONE")
        val request = gate(controller).begin(device.deviceName)!!
        callback(controller, IphoneUsbHost.PermissionResult.Denied(device, request.id))
        CarPlayController::class.java.getDeclaredMethod("invalidateIphonePermission")
            .apply { isAccessible = true }.invoke(controller)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun permissionPendingIntentsKeepDistinctRequestNumbers() {
        val host = host("test.usb.permission")
        fun pending(id: Long): android.app.PendingIntent = IphoneUsbHost::class.java
            .getDeclaredMethod("permissionPendingIntent", java.lang.Long.TYPE).apply { isAccessible = true }
            .invoke(host, id) as android.app.PendingIntent
        val first = pending(1)
        val second = pending(2)
        assertNotEquals(first, second)
        assertEquals(1L, shadowOf(first).savedIntent.getLongExtra(IphoneUsbHost.PERMISSION_REQUEST_ID, 0))
        assertEquals(2L, shadowOf(second).savedIntent.getLongExtra(IphoneUsbHost.PERMISSION_REQUEST_ID, 0))
    }

    @Test fun parsedPermissionResultRetainsRequestIdAndRejectsOtherControllers() {
        val host = host("test.usb.permission.a")
        val device = device("device-a")
        val intent = Intent("test.usb.permission.a").putExtra(UsbManager.EXTRA_DEVICE, device)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
            .putExtra(IphoneUsbHost.PERMISSION_REQUEST_ID, 7L)
        assertEquals(IphoneUsbHost.PermissionResult.Granted(device, 7), host.parsePermissionResult(intent))
        assertNull(host("test.usb.permission.b").parsePermissionResult(intent))
    }

    @Test fun registeredReceiverAcceptsRequestUriAndDeliversOriginalNumber() {
        val app = RuntimeEnvironment.getApplication()
        val host = host("test.usb.permission")
        val pending = IphoneUsbHost::class.java.getDeclaredMethod("permissionPendingIntent", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(host, 9L) as android.app.PendingIntent
        val device = device("device-a")
        val received = mutableListOf<IphoneUsbHost.PermissionResult>()
        host.registerPermissionReceiver { received += it }.use {
            val intent = Intent().putExtra(UsbManager.EXTRA_DEVICE, device)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
            // 与 Android USB 服务相同，通过 PendingIntent 填入设备和结果。
            pending.send(app, 0, intent)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(IphoneUsbHost.PermissionResult.Granted(device, 9)), received)
        }
    }

    @Test fun ch341PendingIntentAlsoDeliversSystemGrantAndDenial() {
        val app = RuntimeEnvironment.getApplication()
        val host = Ch341UsbHost(app, app.getSystemService(UsbManager::class.java),
            Ch341DeviceMatcher(listOf(UsbDeviceId(0x1a86, 0x1234))))
        val pending = Ch341UsbHost::class.java.getDeclaredMethod("permissionPendingIntent")
            .apply { isAccessible = true }.invoke(host) as android.app.PendingIntent
        val device = device("device-ch341", 0x1a86)
        val received = mutableListOf<Ch341UsbHost.PermissionResult>()
        host.registerPermissionReceiver { received += it }.use {
            for (granted in listOf(true, false)) {
                pending.send(app, 0, Intent().putExtra(UsbManager.EXTRA_DEVICE, device)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted))
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(listOf(Ch341UsbHost.PermissionResult.Granted(device),
                Ch341UsbHost.PermissionResult.Denied(device)), received)
        }
    }

    private fun withController(check: (CarPlayController, MutableList<CarPlayStatus>) -> Unit) {
        val statuses = mutableListOf<CarPlayStatus>()
        val config = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3))
        val controller = CarPlayController(RuntimeEnvironment.getApplication(), config,
            AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02", "1", AirPlayDisplayConfig(800, 480), port = 0),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, { statuses += it })
        try { check(controller, statuses) } finally { controller.close(); assertTrue(controller.awaitClosed(4000)) }
    }

    private fun host(action: String): IphoneUsbHost {
        val app = RuntimeEnvironment.getApplication()
        return IphoneUsbHost(app, app.getSystemService(UsbManager::class.java), IphoneUsbMatcher.appleVendor(), action)
    }

    private fun gate(controller: CarPlayController): IphoneUsbPermissionGate = ReflectionHelpers.getField(controller, "iphonePermission")
    private fun phase(controller: CarPlayController): String = ReflectionHelpers.getField<Any>(controller, "phase").toString()
    private fun setPhase(controller: CarPlayController, phase: String) {
        val type = CarPlayController::class.java.declaredClasses.single { it.simpleName == "Phase" }
        ReflectionHelpers.setField(controller, "phase", type.enumConstants!!.single { it.toString() == phase })
    }
    private fun callback(controller: CarPlayController, result: IphoneUsbHost.PermissionResult) {
        CarPlayController::class.java.getDeclaredMethod("onIphonePermission", IphoneUsbHost.PermissionResult::class.java)
            .apply { isAccessible = true }.invoke(controller, result)
    }

    private fun device(name: String, vendor: Int = 0x05ac): UsbDevice {
        // API 差异与 Android 编译器生成的访问构造只在测试边界适配。
        val candidates = UsbDevice::class.java.declaredConstructors.filter {
            !it.isSynthetic && it.parameterTypes.firstOrNull() == String::class.java &&
                it.parameterTypes.any { parameter -> parameter.simpleName == "IUsbSerialReader" }
        }
        val constructor = (candidates.singleOrNull() ?: error(candidates.joinToString(" | ") { it.toGenericString() }))
            .apply { isAccessible = true }
        var strings = 0
        var ints = 0
        val values = constructor.parameterTypes.map { type ->
            when {
                type == String::class.java -> if (strings++ == 0) name else "test"
                type == Integer.TYPE -> when (ints++) { 0 -> vendor; 1 -> 0x1234; else -> 0 }
                type == java.lang.Boolean.TYPE -> false
                type.isArray -> java.lang.reflect.Array.newInstance(type.componentType!!, 0)
                type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }
                else -> error("Unexpected USB constructor parameter")
            }
        }.toTypedArray()
        return constructor.newInstance(*values) as UsbDevice
    }
}
