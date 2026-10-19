package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** 检查真实宿主入口的授权、请求和释放边界；不访问真实 USB 手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class UsbModeDiagnosticsTest {
    private val device = mock(UsbDevice::class.java)
    private val connection = mock(UsbDeviceConnection::class.java)
    private val manager = mock(UsbManager::class.java)
    private val logs = mutableListOf<String>()

    private fun attempt(report: (String) -> Unit = { logs += it }): IphoneUsbHost.TransitionResult {
        val host = IphoneUsbHost(RuntimeEnvironment.getApplication(), manager, IphoneUsbMatcher.appleVendor(),
            onDiagnostic = report)
        var result: IphoneUsbHost.TransitionResult? = null
        host.requestCarPlayReenumerationAsync(device, Executor { it.run() }) { result = it }
        return checkNotNull(result)
    }

    private fun arrange(switchResult: Int = -1) {
        `when`(device.vendorId).thenReturn(0x05ac)
        `when`(manager.hasPermission(device)).thenReturn(true)
        `when`(manager.openDevice(device)).thenReturn(connection)
        `when`(connection.controlTransfer(eq(0xc0), eq(0x52), eq(0), eq(4), any(ByteArray::class.java), eq(1), eq(1000)))
            .thenReturn(switchResult)
    }

    private fun assertOriginalFailure(result: IphoneUsbHost.TransitionResult, transferred: Int = -1) {
        assertTrue(result is IphoneUsbHost.TransitionResult.Failed)
        assertTrue((result as IphoneUsbHost.TransitionResult.Failed).error is IphoneUsbException.ModeSwitchFailed)
        assertEquals("CarPlay configuration request transferred $transferred of 1 bytes",
            result.error.message)
        verify(connection).close()
    }

    @Test fun successNeverProbesOrReadsDescriptors() {
        arrange(1)
        assertEquals(IphoneUsbHost.TransitionResult.ReenumerationRequested, attempt())
        assertEquals(2, logs.size)
        assertTrue(logs.first().contains("event=BEGIN request=0x52"))
        assertTrue(logs.last().contains("event=RESULT returned=1 expected=1 elapsedMs="))
        verify(device, never()).getConfiguration(anyInt())
        verify(connection).controlTransfer(eq(0xc0), eq(0x52), eq(0), eq(4), any(ByteArray::class.java), eq(1), eq(1000))
        verify(connection).close()
        verifyNoMoreInteractions(connection)
    }

    @Test fun permissionDeniedNeverOpensOrProbes() {
        arrange()
        `when`(manager.hasPermission(device)).thenReturn(false)
        assertTrue((attempt() as IphoneUsbHost.TransitionResult.Failed).error is IphoneUsbException.PermissionDenied)
        verify(manager, never()).openDevice(device)
        verifyNoInteractions(connection)
        assertTrue(logs.isEmpty())
    }

    @Test fun nonAppleDeviceNeverOpensOrProbes() {
        arrange()
        `when`(device.vendorId).thenReturn(0x1234)
        assertTrue(attempt() is IphoneUsbHost.TransitionResult.Failed)
        verify(manager, never()).openDevice(device)
        verifyNoInteractions(connection)
    }

    @Test fun rejectedSwitchReadsOnlyOneBoundedModeQueryAndCloses() {
        arrange()
        doAnswer { invocation ->
            assertTrue(logs.last().contains("USB mode rejection transferred=-1 expected=1"))
            val bytes = invocation.getArgument<ByteArray>(4)
            byteArrayOf(3, 3, 3, 0).copyInto(bytes)
            3
        }.`when`(connection).controlTransfer(eq(0xc0), eq(0x45), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        assertOriginalFailure(attempt())
        assertTrue(logs.any { it.contains("result=short returned=3 mode=3:3:3 elapsedMs=") })
        verify(connection).controlTransfer(eq(0xc0), eq(0x52), eq(0), eq(4), any(ByteArray::class.java), eq(1), eq(1000))
        verify(connection).controlTransfer(eq(0xc0), eq(0x45), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        verify(connection).controlTransfer(eq(0xc0), eq(0x53), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        verifyNoMoreInteractions(connection)
    }

    @Test fun zeroLengthSwitchStillReportsItsOriginalResult() {
        arrange(0)
        assertOriginalFailure(attempt(), 0)
        assertTrue(logs.any { it.contains("transferred=0") })
        assertTrue(logs.last().contains("result=unreadable returned=0"))
    }

    @Test fun capabilityReplyDoesNotSwitchRolesOrTurnFailureIntoSuccess() {
        arrange()
        doAnswer { invocation ->
            byteArrayOf(1, 0, -1, 0).copyInto(invocation.getArgument<ByteArray>(4))
            4
        }.`when`(connection).controlTransfer(eq(0xc0), eq(0x53), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        assertOriginalFailure(attempt())
        assertTrue(logs.last().contains("USB capability query result=complete returned=4 capability=1:0:255:0"))
        verify(connection, times(3)).controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt())
        verify(connection, never()).setConfiguration(any())
        verify(connection, never()).claimInterface(any(), anyBoolean())
    }

    @Test fun capabilityExceptionKeepsOriginalFailureAndHidesExceptionMessage() {
        arrange()
        doThrow(IllegalStateException("private-capability-secret")).`when`(connection)
            .controlTransfer(eq(0xc0), eq(0x53), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        assertOriginalFailure(attempt())
        assertTrue(logs.last().contains("USB capability query result=unavailable reason=query-exception"))
        assertFalse(logs.joinToString().contains("private-capability-secret"))
    }

    @Test fun switchTimingAndExceptionTypeAreRecordedWithoutExceptionText() {
        val failure = IllegalStateException("private-usb-secret")
        try {
            UsbModeDiagnostics.traceSwitch({ logs += it }) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(17))
                throw failure
            }
            fail("Expected original exception")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertTrue(logs.last().contains("event=EXCEPTION type=IllegalStateException elapsedMs=17"))
        assertFalse(logs.joinToString().contains("private-usb-secret"))
    }

    @Test fun queryExceptionCannotReplaceOriginalErrorOrLeakMessage() {
        arrange()
        doThrow(IllegalStateException("private-device-path-secret")).`when`(connection)
            .controlTransfer(eq(0xc0), eq(0x45), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        assertOriginalFailure(attempt())
        assertTrue(logs.any { it.contains("reason=query-exception") })
        assertFalse(logs.joinToString().contains("private-device-path-secret"))
    }

    @Test fun revokedPermissionDuringQueryDoesNotTriggerAnotherGrant() {
        arrange()
        doThrow(SecurityException("secret")).`when`(connection)
            .controlTransfer(eq(0xc0), eq(0x45), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        assertOriginalFailure(attempt())
        assertTrue(logs.last().contains("reason=permission"))
        verify(manager).hasPermission(device)
        verify(manager).openDevice(device)
        verifyNoMoreInteractions(manager)
    }

    @Test fun brokenLogConsumerCannotReplaceOriginalErrorOrPreventClose() {
        arrange()
        assertOriginalFailure(attempt { throw IllegalStateException("sink failed") })
    }

    @Test fun brokenDescriptorStillAllowsModeProbe() {
        arrange()
        `when`(device.configurationCount).thenThrow(IllegalStateException("secret"))
        assertOriginalFailure(attempt())
        assertTrue(logs.any { it.endsWith("descriptors=unavailable") })
        assertTrue(logs.any { it.startsWith("USB mode query") })
    }

    @Test fun descriptorsAreBoundedAndNeverReadIdentityStrings() {
        arrange()
        val config = mock(UsbConfiguration::class.java)
        val iface = mock(UsbInterface::class.java)
        `when`(device.configurationCount).thenReturn(200)
        `when`(device.getConfiguration(anyInt())).thenReturn(config)
        `when`(config.id).thenReturn(4)
        `when`(config.interfaceCount).thenReturn(200)
        `when`(config.getInterface(anyInt())).thenReturn(iface)
        `when`(iface.interfaceClass).thenReturn(255)
        `when`(iface.interfaceSubclass).thenReturn(254)
        `when`(iface.interfaceProtocol).thenReturn(2)
        assertOriginalFailure(attempt())
        assertTrue(logs.any { it.contains("configurations=200 omitted=192") })
        assertTrue(logs.any { it.contains(":ff.fe.2x0") })
        assertTrue(logs.any { it.contains("interfaces=200 omitted=168") })
        assertTrue(logs.all { it.length < 4200 })
        verify(device, times(8)).getConfiguration(anyInt())
        verify(config, times(32)).getInterface(anyInt())
        verify(device, never()).getSerialNumber()
        verify(device, never()).getDeviceName()
        verify(device, never()).getManufacturerName()
        verify(device, never()).getProductName()
        verify(device, never()).getVersion()
        verify(config, never()).getName()
        verify(iface, never()).getName()
    }

    @Test fun modeFormattingHandlesShortUnsignedAndInvalidReplies() {
        val bytes = byteArrayOf(5, -1, 3, 0)
        assertEquals("result=complete returned=4 mode=5:255:3:0", UsbModeDiagnostics.formatMode(4, bytes))
        assertEquals("result=short returned=1 mode=5", UsbModeDiagnostics.formatMode(1, bytes))
        for (read in listOf(-1, 0, 5, Int.MAX_VALUE)) {
            assertEquals("result=unreadable returned=$read", UsbModeDiagnostics.formatMode(read, bytes))
        }
        assertEquals("result=unreadable returned=3", UsbModeDiagnostics.formatMode(3, byteArrayOf(1)))
    }
}
