package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7WirelessPrerequisitesTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun bluetoothOffAndStalePairingCannotPassEvenWithSavedPhone() {
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        DiPlayPreferences.savePhone(activity, "00:11:22:33:44:55", "测试手机")
        assertEquals(L7WirelessPrerequisites.State.OFF, L7WirelessPrerequisites.read(activity))
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        assertEquals(L7WirelessPrerequisites.State.UNPAIRED, L7WirelessPrerequisites.read(activity))
        shadowOf(adapter).setBondedDevices(setOf(adapter.getRemoteDevice("00:11:22:33:44:55")))
        assertEquals(L7WirelessPrerequisites.State.READY, L7WirelessPrerequisites.read(activity))
    }

    @Test fun prerequisiteFailureNeverSilentlyContinuesOrRequestsPermission() {
        var choices = 0
        assertFalse(L7WirelessPrerequisites.ensure(activity, { choices++ }, L7WirelessPrerequisites.State.PERMISSION))
        assertEquals(0, choices)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, choices)
        assertTrue(L7WirelessPrerequisites.ensure(activity, {}, L7WirelessPrerequisites.State.READY))
    }
}
