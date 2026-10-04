package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 无线先确认本机蓝牙权限和所选配对；不以 A2DP 媒体连接作为前置，不影响 USB。 */
internal object L7WirelessPrerequisites {
    enum class State(val message: Int) {
        READY(R.string.l7_wireless_bt_ready),
        UNAVAILABLE(R.string.l7_wireless_bt_unavailable),
        OFF(R.string.l7_wireless_bt_off),
        PERMISSION(R.string.l7_wireless_bt_permission),
        PHONE_MISSING(R.string.l7_wireless_phone_pending),
        UNPAIRED(R.string.l7_wireless_bt_unpaired),
        UNKNOWN(R.string.l7_wireless_bt_unknown),
    }

    fun read(context: Context): State = try {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            State.PERMISSION
        } else {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> State.UNAVAILABLE
                !adapter.isEnabled -> State.OFF
                DiPlayPreferences.phoneAddress(context) == null -> State.PHONE_MISSING
                adapter.bondedDevices.none { it.address.equals(DiPlayPreferences.phoneAddress(context), ignoreCase = true) } -> State.UNPAIRED
                else -> State.READY
            }
        }
    } catch (_: SecurityException) { State.PERMISSION }
      catch (_: Exception) { State.UNKNOWN }

    fun ensure(activity: Activity, choosePhone: () -> Unit, state: State = read(activity)): Boolean {
        if (state == State.READY) return true
        val choose = state in setOf(State.PERMISSION, State.PHONE_MISSING, State.UNPAIRED)
        val builder = L7Dialogs.builder(activity).setTitle(R.string.l7_wireless_prerequisite_title)
            .setMessage(state.message).setNegativeButton(R.string.close, null)
        if (state != State.UNAVAILABLE) builder.setPositiveButton(if (choose) R.string.choose_iphone else R.string.bluetooth_settings) { _, _ ->
                if (choose) choosePhone() else openSettings(activity)
            }
        builder.show()
        return false
    }

    fun openSettings(activity: Activity) {
        if (runCatching { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.isFailure) {
            L7Dialogs.builder(activity).setTitle(R.string.bluetooth_settings).setMessage(R.string.l7_wireless_bt_settings_missing)
                .setPositiveButton(R.string.close, null).show()
        }
    }
}

internal class L7WirelessPrerequisiteView(private val activity: Activity, parent: LinearLayout, choosePhone: () -> Unit) {
    private val phone = L7Components.valueRow(activity, activity.getString(R.string.choose_iphone), "", choosePhone)
    init {
        L7SettingsSection.add(parent, activity.getString(R.string.l7_wireless_prerequisite_title),
            activity.getString(R.string.l7_wireless_prerequisite_hint)) { card ->
            card.addView(phone)
            card.addView(L7Components.actionRow(activity, activity.getString(R.string.bluetooth_settings)) {
                L7WirelessPrerequisites.openSettings(activity)
            })
        }
        update()
    }
    fun update() {
        val state = L7WirelessPrerequisites.read(activity)
        phone.setValue(if (DiPlayPreferences.phoneAddress(activity) == null) activity.getString(R.string.l7_hotspot_not_saved)
            else DiPlayPreferences.phoneName(activity))
        phone.setFeedback(activity.getString(state.message), error = state in setOf(L7WirelessPrerequisites.State.UNAVAILABLE,
            L7WirelessPrerequisites.State.UNPAIRED, L7WirelessPrerequisites.State.UNKNOWN))
    }
}
