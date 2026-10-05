package com.shilapi.xcertplay.orchestration

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.shadows.ShadowUsbDeviceConnection
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 实际控制器与系统事件边界；USB 授权请求只记录，不模拟手机协议成功。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33], manifest = Config.NONE, shadows = [PermissionUsbManager::class, TransitionUsbConnection::class])
class IphoneUsbRecoveryTest {
    @Test fun vendorCallbackStartsListFallbackAndClosesInitialConnection() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val old = device("old")
        usb().addOrUpdateUsbDevice(old, true)
        event(controller, "beginReenumeration", old)
        awaitWorker(controller)
        usb().removeUsbDevice(old)
        usb().addOrUpdateUsbDevice(device("new"), false)
        idle()
        assertEquals(listOf("new"), usb().requested)
        assertEquals(CarPlayStatus.RequestingIphonePermission, statuses.last())
        assertTrue(usb().connections.single().closed)
        assertEquals(1, ReflectionHelpers.getField<Int>(controller, "reenumerationAttempts").toInt())
    }

    @Test fun previouslyConnectedAppleDeviceCannotReplaceReenumeratingPhone() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val old = device("old")
        usb().addOrUpdateUsbDevice(old, true)
        usb().addOrUpdateUsbDevice(device("other"), false)
        event(controller, "beginReenumeration", old)
        awaitWorker(controller)
        usb().removeUsbDevice(old)
        idle()
        assertTrue(usb().requested.isEmpty())
        assertEquals(CarPlayStatus.WaitingForReenumeration, statuses.last())
        usb().addOrUpdateUsbDevice(device("new"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf("new"), usb().requested)
    }

    @Test fun failedVendorRequestDoesNotAuthorizePresentReplacement() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val old = device("old")
        usb().addOrUpdateUsbDevice(old, true)
        usb().failTransition = true
        event(controller, "beginReenumeration", old)
        awaitWorker(controller)
        usb().removeUsbDevice(old)
        usb().addOrUpdateUsbDevice(device("new"), false)
        idle()
        assertTrue(usb().requested.isEmpty())
        assertTrue(statuses.last() is CarPlayStatus.Failed)
        assertTrue(usb().connections.single().closed)
        assertFalse(reenumeration(controller).isActive())
    }

    @Test fun transitionCallbackQueuedBeforeCloseCannotStartPolling() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val old = device("old")
        usb().addOrUpdateUsbDevice(old, true)
        event(controller, "beginReenumeration", old)
        awaitWorker(controller)
        controller.close()
        usb().addOrUpdateUsbDevice(device("new"), false)
        idle()
        assertTrue(usb().requested.isEmpty())
        assertTrue(statuses.isEmpty())
        assertTrue(usb().connections.single().closed)
    }

    @Test fun deviceListRecoversMissingAttachBroadcast() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        val next = device("new")
        usb().addOrUpdateUsbDevice(next, false)
        invoke(controller, "checkReenumerationAvailability")
        idle()
        assertEquals(listOf("new"), usb().requested)
        assertEquals(CarPlayStatus.RequestingIphonePermission, statuses.last())
        assertFalse(reenumeration(controller).isActive())
    }

    @Test fun unchangedOldDeviceIsNotReauthorized() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        usb().addOrUpdateUsbDevice(device("old"), false)
        invoke(controller, "checkReenumerationAvailability")
        idle()
        assertTrue(usb().requested.isEmpty())
        assertEquals(listOf(CarPlayStatus.WaitingForReenumeration), statuses)
        assertTrue(reenumeration(controller).isActive())
    }

    @Test fun pollingRecoversDeviceAtReusedPathAfterAbsence() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        invoke(controller, "checkReenumerationAvailability")
        idle()
        usb().addOrUpdateUsbDevice(device("old"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf("old"), usb().requested)
        assertEquals(CarPlayStatus.RequestingIphonePermission, statuses.last())
    }

    @Test fun expectedDetachKeepsTransitionAndAttempt() = withController { controller, statuses ->
        waitingForReenumeration(controller, completed = false)
        val generation = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "iphoneGeneration").get()
        event(controller, "onIphoneDetached", device("old"))
        idle()
        assertEquals("REENUMERATION", phase(controller))
        assertTrue(statuses.isEmpty())
        assertEquals(generation, ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "iphoneGeneration").get())
        assertTrue(reenumeration(controller).isActive())
        reenumeration(controller).completeTransition()
        assertTrue(reenumeration(controller).accepts("old", false))
    }

    @Test fun earlyAttachIsRecoveredByListAfterTransitionCompletes() = withController { controller, _ ->
        waitingForReenumeration(controller, completed = false)
        val next = device("new")
        usb().addOrUpdateUsbDevice(next, false)
        event(controller, "onIphoneAttached", next)
        idle()
        assertTrue(usb().requested.isEmpty())
        reenumeration(controller).completeTransition()
        invoke(controller, "checkReenumerationAvailability")
        idle()
        assertEquals(listOf("new"), usb().requested)
    }

    @Test fun unplugDuringPermissionReturnsToDiscoveryAndDropsLateDenial() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val phone = device("old")
        usb().addOrUpdateUsbDevice(phone, false)
        event(controller, "doRequestIphonePermission", phone)
        idle()
        val requestId = ReflectionHelpers.getField<IphoneUsbPermissionGate.Request>(gate(controller), "pending").id
        usb().removeUsbDevice(phone)
        event(controller, "onIphoneDetached", phone)
        permission(controller, IphoneUsbHost.PermissionResult.Denied(phone, requestId))
        idle()
        assertEquals("IPHONE", phase(controller))
        assertEquals(CarPlayStatus.WaitingForIphone, statuses.last())
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
        assertNotNull(gate(controller).begin("old"))
    }

    @Test fun reenumeratedPhoneUnplugWhileAwaitingPermissionRestartsDiscovery() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        val next = device("new")
        usb().addOrUpdateUsbDevice(next, false)
        invoke(controller, "checkReenumerationAvailability")
        idle()
        usb().removeUsbDevice(next)
        event(controller, "onIphoneDetached", next)
        idle()
        assertEquals("IPHONE", phase(controller))
        assertEquals(CarPlayStatus.WaitingForIphone, statuses.last())
        assertFalse(reenumeration(controller).isActive())
    }

    @Test fun lateDetachAtPresentPathCannotInvalidateCurrentPermission() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val phone = device("old")
        usb().addOrUpdateUsbDevice(phone, false)
        event(controller, "doRequestIphonePermission", phone)
        idle()
        event(controller, "onIphoneDetached", phone)
        idle()
        assertEquals(listOf(CarPlayStatus.RequestingIphonePermission), statuses)
        assertNull(gate(controller).begin("old"))
        assertEquals(listOf("old"), usb().requested)
    }

    @Test fun duplicateQueuedAttachRequestsAreConsumedOnce() = withController { controller, _ ->
        waitingForReenumeration(controller)
        val next = device("new")
        usb().addOrUpdateUsbDevice(next, false)
        event(controller, "onIphoneAttached", next)
        event(controller, "onIphoneAttached", next)
        idle()
        assertEquals(listOf("new"), usb().requested)
    }

    @Test fun oldQueuedDiscoveryCannotReauthorizeSourceDuringTransition() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val old = device("old")
        usb().addOrUpdateUsbDevice(old, false)
        event(controller, "requestIphonePermission", old)
        waitingForReenumeration(controller)
        idle()
        assertTrue(usb().requested.isEmpty())
        assertTrue(statuses.isEmpty())
    }

    @Test fun cancelledPollCannotDiscoverOrAuthorizeAnotherDevice() = withController { controller, _ ->
        waitingForReenumeration(controller)
        invoke(controller, "checkReenumerationAvailability")
        idle()
        controller.close()
        usb().addOrUpdateUsbDevice(device("new"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(usb().requested.isEmpty())
    }

    @Test fun deviceRemovedBeforeQueuedAuthorizationReturnsToReenumerationWait() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        val next = device("new")
        usb().addOrUpdateUsbDevice(next, false)
        invoke(controller, "checkReenumerationAvailability")
        usb().removeUsbDevice(next)
        idle()
        assertTrue(usb().requested.isEmpty())
        assertEquals(CarPlayStatus.WaitingForReenumeration, statuses.last())
        assertTrue(reenumeration(controller).isActive())
    }

    @Test fun missingDetachBroadcastIsRecoveredByPermissionPoll() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val phone = device("old")
        usb().addOrUpdateUsbDevice(phone, false)
        event(controller, "doRequestIphonePermission", phone)
        idle()
        usb().removeUsbDevice(phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(CarPlayStatus.WaitingForIphone, statuses.last())
        assertNotNull(gate(controller).begin("old"))
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
    }

    @Test fun reappearanceBetweenDetachQueriesDoesNotStopPermissionPolling() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val phone = device("old")
        usb().addOrUpdateUsbDevice(phone, false)
        event(controller, "doRequestIphonePermission", phone)
        idle()
        usb().removeUsbDevice(phone)
        usb().restoreAfterEmptyRead = phone
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val reads = usb().deviceListReads
        assertNull(gate(controller).begin("old"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(usb().deviceListReads > reads)
        assertEquals(listOf(CarPlayStatus.RequestingIphonePermission), statuses)
        assertEquals(listOf("old"), usb().requested)
    }

    @Test fun deviceListFailureIsExplicitAndStopsPolling() = withController { controller, statuses ->
        waitingForReenumeration(controller)
        usb().listFailure = true
        invoke(controller, "checkReenumerationAvailability")
        idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertFalse(reenumeration(controller).isActive())
        usb().listFailure = false
        usb().addOrUpdateUsbDevice(device("new"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(usb().requested.isEmpty())
    }

    @Test fun initialDiscoveryFailureDoesNotEscapeSystemEventLoop() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        usb().listFailure = true
        invoke(controller, "checkIphoneAvailability")
        idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertTrue(usb().requested.isEmpty())
    }

    @Test fun unrelatedDetachDoesNotClearCurrentRequest() = withController { controller, statuses ->
        setPhase(controller, "IPHONE")
        val phone = device("old")
        usb().addOrUpdateUsbDevice(phone, false)
        event(controller, "doRequestIphonePermission", phone)
        idle()
        event(controller, "onIphoneDetached", device("other"))
        idle()
        assertEquals(listOf(CarPlayStatus.RequestingIphonePermission), statuses)
        assertNull(gate(controller).begin("old"))
    }

    @Test fun detachReceiverFiltersEventsAndUnregistersIdempotently() {
        val app = RuntimeEnvironment.getApplication()
        val host = IphoneUsbHost(app, app.getSystemService(UsbManager::class.java), IphoneUsbMatcher.appleVendor())
        val received = mutableListOf<UsbDevice>()
        val receiver = host.registerDetachReceiver { received += it }
        val phone = device("old")
        fun send(action: String, device: UsbDevice?) {
            app.sendBroadcast(Intent(action).apply { if (device != null) putExtra(UsbManager.EXTRA_DEVICE, device) })
            idle()
        }
        send(UsbManager.ACTION_USB_DEVICE_ATTACHED, phone)
        send(UsbManager.ACTION_USB_DEVICE_DETACHED, null)
        send(UsbManager.ACTION_USB_DEVICE_DETACHED, device("other", 0x1a86))
        send(UsbManager.ACTION_USB_DEVICE_DETACHED, phone)
        assertEquals(listOf(phone), received)
        receiver.close()
        receiver.close()
        send(UsbManager.ACTION_USB_DEVICE_DETACHED, phone)
        assertEquals(listOf(phone), received)
    }

    private fun usb(): PermissionUsbManager = Shadow.extract(RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitWorker(controller: CarPlayController) {
        ReflectionHelpers.getField<ExecutorService>(controller, "executor").submit {}.get(5, TimeUnit.SECONDS)
    }
    private fun reenumeration(controller: CarPlayController): IphoneUsbReenumeration = ReflectionHelpers.getField(controller, "iphoneReenumeration")
    private fun waitingForReenumeration(controller: CarPlayController, completed: Boolean = true) {
        setPhase(controller, "REENUMERATION")
        ReflectionHelpers.setField(controller, "currentIphoneDevice", "old")
        reenumeration(controller).begin("old")
        if (completed) reenumeration(controller).completeTransition()
    }
    private fun invoke(controller: CarPlayController, name: String) {
        CarPlayController::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)
    }
    private fun event(controller: CarPlayController, name: String, device: UsbDevice) {
        CarPlayController::class.java.getDeclaredMethod(name, UsbDevice::class.java).apply { isAccessible = true }.invoke(controller, device)
    }
    private fun permission(controller: CarPlayController, result: IphoneUsbHost.PermissionResult) {
        CarPlayController::class.java.getDeclaredMethod("onIphonePermission", IphoneUsbHost.PermissionResult::class.java)
            .apply { isAccessible = true }.invoke(controller, result)
    }
}

/** Robolectric 未模拟系统授权弹窗；这里仅记录真实调用并保留系统设备列表实现。 */
@Implements(UsbManager::class)
class PermissionUsbManager : ShadowUsbManager() {
    val requested = mutableListOf<String>()
    var listFailure = false
    var deviceListReads = 0
    var restoreAfterEmptyRead: UsbDevice? = null
    var failTransition = false
    val connections = mutableListOf<TransitionUsbConnection>()
    @Implementation public override fun openDevice(device: UsbDevice): UsbDeviceConnection {
        val connection = super.openDevice(device)
        val shadow = Shadow.extract<TransitionUsbConnection>(connection)
        shadow.failed = failTransition
        connections += shadow
        return connection
    }
    @Implementation fun requestPermission(device: UsbDevice, pending: PendingIntent) { requested += device.deviceName }
    @Implementation public override fun getDeviceList(): java.util.HashMap<String, UsbDevice> {
        deviceListReads += 1
        if (listFailure) throw SecurityException("USB device list unavailable")
        val devices = super.getDeviceList()
        val restore = restoreAfterEmptyRead
        if (devices.isEmpty() && restore != null) {
            restoreAfterEmptyRead = null
            addOrUpdateUsbDevice(restore, false)
        }
        return devices
    }
}

/** 只覆盖配置切换控制传输与关闭，不提供 USBMUX、NCM 或认证成功替身。 */
@Implements(UsbDeviceConnection::class)
class TransitionUsbConnection : ShadowUsbDeviceConnection() {
    var failed = false
    var closed = false
    @Implementation public override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray, length: Int, timeout: Int): Int {
        assertEquals(0xc0, requestType)
        assertEquals(0x52, request)
        assertEquals(0, value)
        assertEquals(4, index)
        assertEquals(1, length)
        assertEquals(1000, timeout)
        return if (failed) -1 else length
    }
    @Implementation fun close() { closed = true }
}
