package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 最近一次真实检查保留到设置页；未检查不能显示为“无冲突”。 */
internal object L7BluetoothAudioSettings {
    @Volatile var status = BluetoothMediaStatus.IDLE

    fun add(context: Context, parent: LinearLayout) {
        var local = GalaxyMusicPlayback.localEnabled(context)
        lateinit var ownerRow: L7SettingRow
        ownerRow = L7Components.valueRow(context, context.getString(R.string.galaxy_music_owner),
            context.getString(if (local) R.string.galaxy_music_local else R.string.galaxy_music_bluetooth)) {
            val names = listOf(context.getString(R.string.galaxy_music_bluetooth), context.getString(R.string.galaxy_music_local))
            L7Components.select(context, context.getString(R.string.galaxy_music_owner), names,
                if (local) 1 else 0, context.getString(R.string.l7_save_next_connection)) { selected ->
                try {
                    GalaxyMusicPlayback.saveLocalEnabled(context, selected == 1)
                    local = selected == 1
                    ownerRow.setValue(names[selected])
                } catch (_: Exception) { android.widget.Toast.makeText(context, R.string.l7_template_save_failed, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        parent.addView(ownerRow)
        parent.addView(L7SettingRow(context, context.getString(R.string.galaxy_music_guide), context.getString(R.string.galaxy_music_guide_body)))
        parent.addView(L7Components.switchRow(context, context.getString(R.string.l7_bt_media_auto),
            context.getString(R.string.l7_bt_media_auto_desc), AirPlayPersistence.loadBluetoothMediaExclusive(context)) {
            AirPlayPersistence.saveBluetoothMediaExclusive(context, it)
            CarPlayBackgroundSession.updateBluetoothMediaAutomatic(it)
        })
        parent.addView(L7Components.actionRow(context, context.getString(R.string.l7_bt_media_help),
            context.getString(R.string.l7_bt_media_help_desc)) { show(context) })
    }

    fun show(context: Context) = L7Dialogs.builder(context)
        .setTitle(R.string.l7_bt_media_help)
        .setMessage(context.getString(R.string.l7_bt_media_help_body, context.getString(statusResource(status))))
        .setNegativeButton(R.string.close, null)
        .setPositiveButton(R.string.l7_bt_media_open_settings) { _, _ ->
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .onFailure {
                    if (context is Activity) L7Notice.show(context, context.getString(R.string.l7_bt_media_settings_unavailable))
                }
        }.show()

    private fun statusResource(status: BluetoothMediaStatus): Int = when (status) {
        BluetoothMediaStatus.IDLE -> R.string.l7_bt_media_idle
        BluetoothMediaStatus.CHECKING -> R.string.l7_bt_media_checking
        BluetoothMediaStatus.NO_TARGET -> R.string.l7_bt_media_no_target
        BluetoothMediaStatus.CLEAR -> R.string.l7_bt_media_clear
        BluetoothMediaStatus.CONFLICT -> R.string.l7_bt_media_conflict
        BluetoothMediaStatus.DISCONNECTING -> R.string.l7_bt_media_disconnecting
        BluetoothMediaStatus.BLOCKED -> R.string.l7_bt_media_blocked
        BluetoothMediaStatus.LIMIT -> R.string.l7_bt_media_limit
        BluetoothMediaStatus.CLOSED -> R.string.l7_bt_media_closed
    }
}
