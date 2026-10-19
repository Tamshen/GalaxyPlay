package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyUsbVerificationPhoneTest {
    @Test fun capabilityUsesExactUsbRuleAndRoleSwitchHasNoPayload() {
        val manager = mock(UsbManager::class.java); val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        `when`(manager.hasPermission(device)).thenReturn(true)
        `when`(manager.openDevice(device)).thenReturn(connection)
        doAnswer { call -> byteArrayOf(1, 0, 0, 0).copyInto(call.getArgument<ByteArray>(4)); 4 }.`when`(connection)
            .controlTransfer(eq(0xc0), eq(0x53), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        val phone = GalaxyUsbVerificationPhone(manager, device, {})
        assertTrue(phone.capability()); assertEquals(0, phone.switchRole()); phone.close(); phone.close()
        val order = inOrder(connection)
        order.verify(connection).controlTransfer(eq(0xc0), eq(0x53), eq(0), eq(0), any(ByteArray::class.java), eq(4), eq(1000))
        order.verify(connection).controlTransfer(0x40, 0x51, 1, 0, null, 0, 1000)
        order.verify(connection).close(); verifyNoMoreInteractions(connection)
        verify(manager, never()).requestPermission(any(UsbDevice::class.java), any(android.app.PendingIntent::class.java))
    }
    @Test fun shortOrWrongRepliesDoNotPass() {
        assertTrue(GalaxyUsbVerificationPhone.supported(4, byteArrayOf(1, 0, 7, 8)))
        assertFalse(GalaxyUsbVerificationPhone.supported(3, byteArrayOf(1, 0, 0, 0)))
        assertFalse(GalaxyUsbVerificationPhone.supported(4, byteArrayOf(0, 1, 0, 0)))
        assertFalse(GalaxyUsbVerificationPhone.supported(-1, byteArrayOf(1, 0, 0, 0)))
    }
    @Test fun missingPermissionDoesNotOpenOrRequestItAgain() {
        val manager = mock(UsbManager::class.java); val device = mock(UsbDevice::class.java)
        assertThrows(GalaxyUsbVerificationFailure::class.java) { GalaxyUsbVerificationPhone(manager, device, {}).capability() }
        verify(manager).hasPermission(device); verifyNoMoreInteractions(manager)
    }
}
