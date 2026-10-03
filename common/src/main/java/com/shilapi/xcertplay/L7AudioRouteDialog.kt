package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.media.AudioDeviceInfo
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioOutputPolicy
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 将待选路由、试听状态和保存分开；试听成功只表示写入 PCM，听感由用户确认。 */
internal object L7AudioRouteDialog {
    fun show(context: Context, title: String, current: Int, role: AudioOutputRole,
             onApply: (Int) -> Unit): AlertDialog {
        val names = context.resources.getStringArray(R.array.l7_audio_stream_names)
        var pending = current.takeIf(AudioOutputPolicy::valid) ?: 0
        var selector: AlertDialog? = null
        var committed = false
        lateinit var dialog: AlertDialog
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun add(view: android.view.View) = body.addView(view,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = L7Components.dp(context, 12) })
        add(L7Components.text(context, context.getString(R.string.l7_audio_route_help), secondary = true))
        val status = L7Components.text(context, context.getString(R.string.l7_audio_test_ready), secondary = true)
        val stop = L7Components.actionButton(context, context.getString(R.string.l7_audio_test_stop)) {}
        val preview = AudioChannelPreview(
            onUnavailable = {
                status.text = context.getString(R.string.contrib_audio_home_channel_preview_unavailable, it)
                stop.isEnabled = false
            },
            context = context,
            onResult = { _, type ->
                status.text = context.getString(R.string.l7_audio_test_sent, deviceLabel(context, type))
                stop.isEnabled = false
            },
        )
        lateinit var route: L7SettingRow
        route = L7Components.valueRow(context, context.getString(R.string.l7_audio_route_select), L7AudioSettings.label(context, pending)) {
            preview.stop()
            stop.isEnabled = false
            status.setText(R.string.l7_audio_test_stopped)
            selector = L7Components.select(context, context.getString(R.string.l7_audio_route_select),
                names.toList(), AudioOutputPolicy.choices.indexOf(pending), context.getString(R.string.l7_audio_route_choose)) { selected ->
                pending = AudioOutputPolicy.choices[selected]
                route.setValue(names[selected])
                status.setText(R.string.l7_audio_test_ready)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = pending != current
            }
        }
        add(route)
        add(L7Components.actionButton(context, context.getString(R.string.l7_audio_test_play), primary = true) {
            status.text = context.getString(R.string.l7_audio_test_playing, L7AudioSettings.label(context, pending))
            stop.isEnabled = true
            preview.play(pending, role)
        })
        stop.isEnabled = false
        stop.setOnClickListener {
            preview.stop()
            stop.isEnabled = false
            status.setText(R.string.l7_audio_test_stopped)
        }
        add(stop)
        add(status)
        dialog = L7Dialogs.builder(context).setTitle(title).setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_save_next_connection) { _, _ ->
                if (pending != current && !committed) { committed = true; onApply(pending) }
            }.create()
        dialog.setOnDismissListener { selector?.dismiss(); preview.close() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
        dialog.show()
        return dialog
    }

    private fun deviceLabel(context: Context, type: Int): String = context.getString(when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.l7_audio_output_speaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.l7_audio_output_earpiece
        AudioDeviceInfo.TYPE_BUS -> R.string.l7_audio_output_bus
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.l7_audio_output_wired
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.l7_audio_output_bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.l7_audio_output_usb
        -1 -> R.string.l7_audio_output_unknown
        else -> R.string.l7_audio_output_system
    })

}
