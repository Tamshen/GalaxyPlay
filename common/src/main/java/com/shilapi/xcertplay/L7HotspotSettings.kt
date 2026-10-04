package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 原生热点引导与系统能力反馈；页面只自动读取，写入和开启必须点击。 */
internal class L7HotspotSettings(
    private val activity: Activity,
    parent: LinearLayout,
    private val task: L7HotspotTask,
    private val actions: L7HotspotActions,
    private val onEdit: () -> Unit,
) {
    private lateinit var details: L7SettingRow
    private lateinit var read: L7SettingRow
    private lateinit var start: android.widget.Button
    private lateinit var create: android.widget.Button
    private lateinit var stateRow: L7SettingRow
    private var dialog: AlertDialog? = null
    private var displayedStatus: L7HotspotTask.Status? = null

    init {
        L7SettingsSection.add(parent, text(R.string.l7_wireless_step1),
            description = text(R.string.hotspot_mode_manual_desc)) { card ->
            stateRow = L7SettingRow(activity, text(R.string.l7_hotspot_state)).also(card::addView)
            card.addView(L7Components.actionRow(activity, text(R.string.open_car_hotspot_settings),
                text(R.string.l7_hotspot_settings_hint)) { openSettings(activity) })
        }
        L7SettingsSection.actions(parent) { group ->
            start = L7Components.actionButton(activity, text(R.string.l7_hotspot_start), primary = true) {
                actions.start(); update()
            }.also { group.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        L7SettingsSection.add(parent, text(R.string.l7_wireless_step2),
            description = text(R.string.l7_wireless_details_hint)) { card ->
            details = L7Components.valueRow(activity, text(R.string.car_hotspot_details), "", onEdit).also(card::addView)
            read = L7Components.actionRow(activity, text(R.string.l7_hotspot_read)) { actions.read(); update() }.also(card::addView)
        }
        L7SettingsSection.actions(parent, text(R.string.l7_hotspot_actions_hint)) { group ->
            create = L7Components.actionButton(activity, text(R.string.l7_hotspot_create)) { generatedDialog() }
                .also { group.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        refresh()
    }

    fun refresh() {
        if (!actions.showing && !task.status.busy) task.read()
        update()
    }

    fun update() {
        val state = task.status
        if (state != displayedStatus) {
            displayedStatus = state
            val reading = state.message in setOf(R.string.l7_hotspot_reading, R.string.l7_hotspot_read_ok,
                R.string.l7_hotspot_read_permission, R.string.l7_hotspot_read_invalid, R.string.l7_hotspot_read_unavailable)
            read.setFeedback(if (reading) text(state.message) else "", error = reading && !state.busy && state.message != R.string.l7_hotspot_read_ok)
            stateRow.setValue(text(when {
                state.busy -> R.string.l7_hotspot_checking
                state.hotspotEnabled == true -> R.string.l7_hotspot_observed_on
                state.hotspotEnabled == false -> R.string.l7_hotspot_observed_off
                else -> R.string.l7_hotspot_observed_unknown
            }))
            stateRow.setFeedback(if (reading) text(R.string.l7_hotspot_observation_hint) else text(state.message),
                error = !reading && !state.busy && state.message !in setOf(R.string.l7_hotspot_ready,
                    R.string.l7_hotspot_ready_no_details, R.string.l7_hotspot_cancelled))
        }
        val valid = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(
            AirPlayPersistence.loadManualHotspotSsid(activity), AirPlayPersistence.loadManualHotspotPassphrase(activity)) == null
        details.setValue(AirPlayPersistence.loadManualHotspotSsid(activity).ifBlank { text(R.string.l7_hotspot_not_saved) })
        details.setFeedback(text(if (valid) R.string.l7_wireless_details_saved else R.string.l7_wireless_details_missing), error = !valid)
        val enabled = !state.busy && !CarPlayBackgroundSession.hasSession()
        listOf(start, create, details, read).forEach { it.isEnabled = enabled }
    }

    private fun generatedDialog() {
        val value = task.proposal(RemoteLogDevice.id(activity))
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = L7Components.dp(activity, 24)
            setPadding(inset, inset, inset, inset)
        }
        fields.addView(L7Typography.text(activity, text(R.string.l7_hotspot_create_hint), L7Typography.Role.DESCRIPTION))
        L7SettingsSection.add(fields) { card ->
            card.addView(L7SettingRow(activity, text(R.string.hotspot_name)).apply { setValue(value.ssid) })
            val password = L7SettingRow(activity, text(R.string.hotspot_password)).apply { setValue(value.password) }
            password.valueView.transformationMethod = PasswordTransformationMethod.getInstance()
            card.addView(password)
            fields.addView(CheckBox(activity).apply {
                text = text(R.string.show_password)
                setOnCheckedChangeListener { _, checked ->
                    password.valueView.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
                }
            })
        }
        L7SettingsSection.actions(fields) { group ->
            listOf(R.string.l7_hotspot_copy_name to value.ssid, R.string.l7_hotspot_copy_password to value.password).forEach { (title, content) ->
                group.addView(L7Components.actionButton(activity, text(title)) {
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text(title), content))
                    Toast.makeText(activity, R.string.l7_hotspot_copied, Toast.LENGTH_SHORT).show()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(activity, 8) })
            }
        }
        dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_hotspot_create)
            .setView(ScrollView(activity).apply { addView(fields) })
            .setPositiveButton(R.string.l7_hotspot_apply_start) { _, _ ->
                actions.start(value); update()
            }
            .setNeutralButton(R.string.open_car_hotspot_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun dispose() { dialog?.dismiss(); dialog = null }
    private fun text(id: Int) = activity.getString(id)

    companion object {
        /** 明确限定 Android 设置包，避免相同 action 被车机自带热点页面接管。 */
        fun openSettings(activity: Activity) {
            val candidates = listOf(
                Intent("com.android.settings.WIFI_TETHER_SETTINGS").setPackage("com.android.settings"),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WifiTetherSettingsActivity"),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS).setPackage("com.android.settings"),
            )
            if (candidates.none { runCatching { activity.startActivity(it) }.isSuccess }) {
                L7Dialogs.builder(activity).setTitle(R.string.open_car_hotspot_settings)
                    .setMessage(R.string.l7_hotspot_settings_missing).setPositiveButton(R.string.close, null).show()
            }
        }
    }
}
