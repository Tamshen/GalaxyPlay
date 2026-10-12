package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** 无线方式独立保存；只由用户确认切换，不启动或关闭任何网络。 */
internal class GalaxyWirelessSettings(
    private val activity: Activity,
    parent: LinearLayout,
    private val canEdit: () -> Boolean,
    private val changed: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var closed = false
    private var pickerGeneration = 0
    private lateinit var modeRow: L7SettingRow
    private var channelRow: L7SettingRow? = null
    private var wifiRow: L7SettingRow? = null

    init {
        L7SettingsSection.add(parent, activity.getString(R.string.wireless_connection_method)) { card ->
            modeRow = L7Components.valueRow(activity, activity.getString(R.string.wireless_connection_method),
                modeLabel(AirPlayPersistence.loadWirelessHotspotMode(activity)), ::chooseMode)
                .apply { setDescription(activity.getString(R.string.wireless_connection_method_hint)) }
            card.addView(modeRow)
        }
        update()
    }

    fun addDirect(parent: LinearLayout) {
        L7SettingsSection.add(parent, activity.getString(R.string.wifi_direct),
            activity.getString(R.string.hotspot_hint_p2p)) { card ->
            wifiRow = L7Components.actionRow(activity, activity.getString(R.string.l7_hotspot_wifi_settings)) {
                openWifiSettings(activity)
            }.also(card::addView)
            channelRow = L7Components.valueRow(activity, activity.getString(R.string.wifi_direct_channel_title), "",
                ::chooseChannel).apply { setDescription(activity.getString(R.string.wifi_direct_channel_description)) }
                .also(card::addView)
        }
        update()
    }

    fun update() {
        modeRow.setValue(modeLabel(AirPlayPersistence.loadWirelessHotspotMode(activity)))
        modeRow.isEnabled = !closed && canEdit()
        channelRow?.setValue(channelLabel(AirPlayPersistence.loadWifiP2pPreferredChannel(activity)))
        channelRow?.isEnabled = !closed && canEdit()
        val available = supported(activity)
        val enabled = wifiEnabled(activity)
        modeRow.setFeedback(if (available) "" else activity.getString(R.string.this_head_unit_does_not_support_wi_fi_direct))
        wifiRow?.setFeedback(activity.getString(when {
            !available -> R.string.this_head_unit_does_not_support_wi_fi_direct
            enabled == null -> R.string.wifi_direct_wifi_unknown
            !enabled -> R.string.wifi_direct_wifi_off
            else -> R.string.hotspot_hint_p2p
        }), error = !available || enabled != true)
    }

    private fun chooseMode() {
        if (closed || !canEdit()) return
        val choices = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P)
        val current = AirPlayPersistence.loadWirelessHotspotMode(activity)
        var selection = current
        val available = supported(activity)
        dismiss()
        val generation = pickerGeneration
        dialog = L7Dialogs.builder(activity).setTitle(R.string.wireless_connection_method)
            .setSingleChoiceItems(choices.map(::modeLabel).toTypedArray(), choices.indexOf(selection),
                { it == 0 || available }) { _, index -> selection = choices[index] }
            .setPositiveButton(R.string.save) { _, _ ->
                if (canConfirm(generation) && selection != current &&
                    AirPlayPersistence.loadWirelessHotspotMode(activity) == current &&
                    (selection == WirelessHotspotMode.MANUAL || supported(activity))) {
                    AirPlayPersistence.saveWirelessHotspotMode(activity, selection)
                    changed()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseChannel() {
        if (closed || !canEdit()) return
        val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
        val current = AirPlayPersistence.loadWifiP2pPreferredChannel(activity)
        var selection = current
        dismiss()
        val generation = pickerGeneration
        dialog = L7Dialogs.builder(activity).setTitle(R.string.wifi_direct_channel_title).setFixedHeight()
            .setSingleChoiceItems(choices.map(::channelLabel).toTypedArray(), choices.indexOf(selection)) { _, index ->
                selection = choices[index]
            }.setPositiveButton(R.string.save) { _, _ ->
                if (canConfirm(generation) && selection != current &&
                    AirPlayPersistence.loadWifiP2pPreferredChannel(activity) == current &&
                    AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.WIFI_P2P) {
                    AirPlayPersistence.saveWifiP2pPreferredChannel(activity, selection)
                    update()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun modeLabel(mode: WirelessHotspotMode) = activity.getString(
        if (mode == WirelessHotspotMode.WIFI_P2P) R.string.wifi_direct else R.string.built_in_car_hotspot)
    private fun channelLabel(channel: Int) = if (channel == WifiP2pChannels.AUTO) activity.getString(R.string.auto)
        else activity.getString(R.string.wifi_direct_channel_choice, channel,
            activity.getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))

    private fun canConfirm(generation: Int) = !closed && generation == pickerGeneration &&
        dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed && canEdit()

    fun dismiss() { pickerGeneration++; dialog?.dismiss(); dialog = null }
    fun dispose() { closed = true; dismiss() }

    companion object {
        fun supported(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            context.getSystemService(WifiP2pManager::class.java) != null
        private fun wifiEnabled(context: Context) = runCatching {
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()

        fun ensure(activity: Activity): Boolean {
            val available = supported(activity)
            val enabled = wifiEnabled(activity)
            if (available && enabled == true) return true
            val builder = L7Dialogs.builder(activity).setTitle(R.string.wifi_direct)
                .setMessage(when {
                    !available -> R.string.this_head_unit_does_not_support_wi_fi_direct
                    enabled == null -> R.string.wifi_direct_wifi_unknown
                    else -> R.string.wifi_direct_wifi_off
                })
                .setNegativeButton(R.string.close, null)
            if (available) builder.setPositiveButton(R.string.l7_hotspot_wifi_settings) { _, _ -> openWifiSettings(activity) }
            builder.show()
            return false
        }

        private fun openWifiSettings(activity: Activity) {
            if (runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.isFailure) {
                L7Dialogs.builder(activity).setTitle(R.string.wifi_direct).setMessage(R.string.wifi_direct_settings_missing)
                    .setPositiveButton(R.string.close, null).show()
            }
        }
    }
}
