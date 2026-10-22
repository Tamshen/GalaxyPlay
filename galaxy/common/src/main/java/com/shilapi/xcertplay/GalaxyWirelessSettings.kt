package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.network.GalaxyP2pRegion
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
    private var regionRow: L7SettingRow? = null
    private var wifiRow: L7SettingRow? = null
    private var existingSsidRow: L7SettingRow? = null
    private var existingPassRow: L7SettingRow? = null
    private val performanceRows = mutableListOf<Pair<String, L7SettingRow>>()

    init {
        L7SettingsSection.add(parent, activity.getString(R.string.wireless_connection_method)) { card ->
            modeRow = L7Components.valueRow(activity, activity.getString(R.string.wireless_connection_method),
                modeLabel(AirPlayPersistence.loadWirelessHotspotMode(activity)), ::chooseMode)
                .apply { setDescription(activity.getString(R.string.wireless_connection_method_hint)) }
            card.addView(modeRow)
            GalaxyWirelessPerformanceSettings.add(activity, card, false) { !closed && canEdit() }
                .also { performanceRows += GalaxyWirelessPerformanceSettings.LOCK to it }
            existingSsidRow = L7Components.valueRow(activity, activity.getString(R.string.existing_wifi_ssid),
                AirPlayPersistence.loadExistingWifiSsid(activity)) { editExistingWifi(true) }
                .also { card.addView(it) }
            existingPassRow = L7Components.valueRow(activity, activity.getString(R.string.existing_wifi_password),
                mask(AirPlayPersistence.loadExistingWifiPassphrase(activity))) { editExistingWifi(false) }
                .also { card.addView(it) }
        }
        update()
    }

    fun addDirect(parent: LinearLayout) {
        L7SettingsSection.add(parent, activity.getString(R.string.wifi_direct),
            activity.getString(R.string.hotspot_hint_p2p)) { card ->
            wifiRow = L7Components.actionRow(activity, activity.getString(R.string.open_car_hotspot_settings)) {
                L7HotspotSettings.openSettings(activity)
            }.also(card::addView)
            regionRow = L7Components.valueRow(activity, activity.getString(R.string.galaxy_p2p_region_title), "",
                ::chooseRegion).apply { setDescription(activity.getString(R.string.galaxy_p2p_region_hint)) }
                .also(card::addView)
            channelRow = L7Components.valueRow(activity, activity.getString(R.string.wifi_direct_channel_title), "",
                ::chooseChannel).apply { setDescription(activity.getString(R.string.wifi_direct_channel_description)) }
                .also(card::addView)
            GalaxyWirelessPerformanceSettings.add(activity, card, true) { !closed && canEdit() }
                .also { performanceRows += GalaxyWirelessPerformanceSettings.SCAN to it }
        }
        update()
    }

    fun update() {
        performanceRows.forEach { (key, row) ->
            row.isEnabled = !closed && canEdit()
            row.setSwitchChecked(GalaxyWirelessPerformanceSettings.enabled(activity, key))
        }
        modeRow.setValue(modeLabel(AirPlayPersistence.loadWirelessHotspotMode(activity)))
        modeRow.isEnabled = !closed && canEdit()
        val showExisting = AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.EXISTING_WIFI
        existingSsidRow?.visibility = if (showExisting) View.VISIBLE else View.GONE
        existingPassRow?.visibility = if (showExisting) View.VISIBLE else View.GONE
        val fixedChannel = GalaxyP2pSettings.fixedChannel(activity)
        channelRow?.setValue(channelLabel(GalaxyP2pSettings.effectiveChannel(activity)))
        channelRow?.isEnabled = fixedChannel == null && !closed && canEdit()
        channelRow?.setDescription(activity.getString(if (fixedChannel != null)
            R.string.galaxy_l7_direct_channel_hint else R.string.wifi_direct_channel_description))
        val region = GalaxyP2pSettings.load(activity)
        regionRow?.setValue(regionLabel(region))
        regionRow?.isEnabled = !closed && canEdit()
        val channel = GalaxyP2pSettings.effectiveChannel(activity)
        val invalid = channel != WifiP2pChannels.AUTO && !region.policy.allows(channel)
        channelRow?.setFeedback(if (invalid) activity.getString(R.string.galaxy_p2p_region_channel_invalid) else "", error = invalid)
        val available = supported(activity)
        val enabled = wifiEnabled(activity)
        val mode = AirPlayPersistence.loadWirelessHotspotMode(activity)
        modeRow.setFeedback(if (available || mode == WirelessHotspotMode.EXISTING_WIFI) "" else activity.getString(R.string.this_head_unit_does_not_support_wi_fi_direct))
        wifiRow?.setFeedback(activity.getString(when {
            !available -> R.string.this_head_unit_does_not_support_wi_fi_direct
            enabled == null -> R.string.wifi_direct_wifi_unknown
            !enabled -> R.string.wifi_direct_wifi_off
            else -> R.string.hotspot_hint_p2p
        }), error = !available || enabled != true)
    }

    private fun chooseMode() {
        if (closed || !canEdit()) return
        val choices = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val current = AirPlayPersistence.loadWirelessHotspotMode(activity)
        var selection = current
        val available = supported(activity)
        dismiss()
        val generation = pickerGeneration
        dialog = L7Dialogs.builder(activity).setTitle(R.string.wireless_connection_method)
            .setSingleChoiceItems(choices.map(::modeLabel).toTypedArray(), choices.indexOf(selection),
                { index -> choices[index] != WirelessHotspotMode.WIFI_P2P || available }) { _, index -> selection = choices[index] }
            .setPositiveButton(R.string.save) { _, _ ->
                if (canConfirm(generation) && selection != current &&
                    AirPlayPersistence.loadWirelessHotspotMode(activity) == current &&
                    (selection == WirelessHotspotMode.MANUAL || selection == WirelessHotspotMode.EXISTING_WIFI || supported(activity))) {
                    AirPlayPersistence.saveWirelessHotspotMode(activity, selection)
                    changed()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseChannel() {
        if (closed || !canEdit() || GalaxyP2pSettings.fixedChannel(activity) != null) return
        val region = GalaxyP2pSettings.load(activity)
        val choices = listOf(WifiP2pChannels.AUTO) + region.policy.channels
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
                    GalaxyP2pSettings.load(activity) == region &&
                    GalaxyP2pSettings.fixedChannel(activity) == null &&
                    AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.WIFI_P2P) {
                    AirPlayPersistence.saveWifiP2pPreferredChannel(activity, selection)
                    update()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseRegion() {
        if (closed || !canEdit()) return
        val current = GalaxyP2pSettings.load(activity)
        var selection = current
        dismiss()
        val generation = pickerGeneration
        val choices = GalaxyP2pRegion.entries
        dialog = L7Dialogs.builder(activity).setTitle(R.string.galaxy_p2p_region_title).setFixedHeight()
            .setSingleChoiceItems(choices.map(::regionLabel).toTypedArray(), choices.indexOf(current)) { _, index ->
                selection = choices[index]
            }.setPositiveButton(R.string.save) { _, _ ->
                if (canConfirm(generation) && selection != current && GalaxyP2pSettings.load(activity) == current &&
                    AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.WIFI_P2P) {
                    GalaxyP2pSettings.save(activity, selection)
                    update()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun regionLabel(region: GalaxyP2pRegion) = activity.getString(when (region) {
        GalaxyP2pRegion.MAINLAND_CHINA -> R.string.galaxy_p2p_region_mainland
        GalaxyP2pRegion.OTHER_CONSERVATIVE -> R.string.galaxy_p2p_region_other
    })


    private fun modeLabel(mode: WirelessHotspotMode) = activity.getString(when (mode) {
        WirelessHotspotMode.WIFI_P2P -> R.string.wifi_direct
        WirelessHotspotMode.EXISTING_WIFI -> R.string.existing_wifi_title
        else -> R.string.built_in_car_hotspot
    })
    private fun channelLabel(channel: Int) = if (channel == WifiP2pChannels.AUTO) activity.getString(R.string.auto)
        else activity.getString(R.string.wifi_direct_channel_choice, channel,
            activity.getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))

    private fun canConfirm(generation: Int) = !closed && generation == pickerGeneration &&
        dialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed && canEdit()

    fun dismiss() { pickerGeneration++; dialog?.dismiss(); dialog = null }
    fun dispose() { closed = true; performanceRows.forEach { it.second.isEnabled = false }; dismiss() }

    private fun mask(value: String): String = if (value.isEmpty()) "" else "••••••"

    private fun editExistingWifi(isSsid: Boolean) {
        if (closed || !canEdit()) return
        val ctx = activity
        val ssid = AirPlayPersistence.loadExistingWifiSsid(ctx)
        val pass = AirPlayPersistence.loadExistingWifiPassphrase(ctx)
        val edit = EditText(ctx).apply {
            setText(if (isSsid) ssid else pass)
            inputType = if (isSsid) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (isSsid) R.string.existing_wifi_ssid else R.string.existing_wifi_password)
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = edit.text.toString()
                if (isSsid) AirPlayPersistence.saveExistingWifiCredentials(ctx, text, pass)
                else AirPlayPersistence.saveExistingWifiCredentials(ctx, ssid, text)
                existingSsidRow?.setValue(AirPlayPersistence.loadExistingWifiSsid(ctx))
                existingPassRow?.setValue(mask(AirPlayPersistence.loadExistingWifiPassphrase(ctx)))
                changed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
            if (available) builder.setPositiveButton(R.string.open_car_hotspot_settings) { _, _ ->
                L7HotspotSettings.openSettings(activity)
            }
            builder.show()
            return false
        }
    }
}
