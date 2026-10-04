package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import com.shilapi.xcertplay.host.R

/** 业务动作使用具名图形，不用字符或 emoji 代替；文字仍是独立的可访问名称。 */
internal object L7Icons {
    private val actions = mapOf(
        R.string.l7_start_wireless to R.drawable.ic_dp_connection,
        R.string.l7_entry_connect to R.drawable.ic_l7_projection,
        R.string.l7_home_usb to R.drawable.ic_l7_usb,
        R.string.settings to R.drawable.ic_l7_settings,
        R.string.connect_phone to R.drawable.ic_dp_connection,
        R.string.connect to R.drawable.ic_dp_connection,
        R.string.connect_with_usb to R.drawable.ic_l7_usb,
        R.string.l7_resume_projection to R.drawable.ic_l7_projection,
        R.string.l7_view_connection to R.drawable.ic_l7_projection,
        R.string.open_car_hotspot_settings to R.drawable.ic_l7_hotspot,
        R.string.l7_hotspot_start to R.drawable.ic_l7_hotspot,
        R.string.l7_hotspot_use_saved to R.drawable.ic_dp_connection,
        R.string.l7_hotspot_create to R.drawable.ic_l7_edit,
        R.string.l7_hotspot_apply_start to R.drawable.ic_l7_hotspot,
        R.string.l7_hotspot_cancel to R.drawable.ic_l7_close,
        R.string.l7_hotspot_grant to R.drawable.ic_l7_permissions,
        R.string.l7_hotspot_copy_name to R.drawable.ic_l7_copy,
        R.string.l7_hotspot_copy_password to R.drawable.ic_l7_copy,
        R.string.open_car_settings to R.drawable.ic_l7_hotspot,
        R.string.choose_iphone to R.drawable.ic_l7_phone,
        R.string.l7_back_settings to R.drawable.ic_l7_back,
        R.string.back_to_diplay to R.drawable.ic_l7_back,
        R.string.reset_carplay_wi_fi to R.drawable.ic_l7_refresh,
        R.string.open_bluetooth to R.drawable.ic_l7_bluetooth,
        R.string.bluetooth_settings to R.drawable.ic_l7_bluetooth,
        R.string.l7_auth_quick_import to R.drawable.ic_l7_import,
        R.string.l7_auth_choose_source to R.drawable.ic_l7_lock,
        R.string.l7_debug_refresh to R.drawable.ic_l7_refresh,
        R.string.l7_debug_view to R.drawable.ic_dp_diagnostics,
        R.string.l7_log_view_short to R.drawable.ic_dp_diagnostics,
        R.string.l7_log_copy_matches to R.drawable.ic_l7_copy,
        R.string.l7_log_copy_all to R.drawable.ic_l7_copy,
        R.string.l7_log_clear_logs to R.drawable.ic_l7_delete,
        R.string.l7_log_clear_reports to R.drawable.ic_l7_delete,
        R.string.l7_log_upload to R.drawable.ic_l7_share,
        R.string.l7_task_collect to R.drawable.ic_l7_debug,
        R.string.l7_task_stop_collect to R.drawable.ic_l7_close,
        R.string.l7_task_stop_upload to R.drawable.ic_l7_close,
        R.string.l7_task_end_close to R.drawable.ic_l7_close,
        R.string.l7_probe_results to R.drawable.ic_l7_debug,
        R.string.l7_log_retry to R.drawable.ic_l7_refresh,
        R.string.l7_log_cancel to R.drawable.ic_l7_close,
        R.string.l7_debug_permission to R.drawable.ic_l7_permissions,
        R.string.l7_debug_start to R.drawable.ic_dp_diagnostics,
        R.string.save_diagnostic_report to R.drawable.ic_l7_import,
        R.string.choose_save_location to R.drawable.ic_l7_edit,
        R.string.share to R.drawable.ic_l7_share,
        R.string.app_permissions to R.drawable.ic_l7_permissions,
        R.string.wireless_connection_help to R.drawable.ic_dp_about,
        R.string.connection_setup to R.drawable.ic_l7_hotspot,
        R.string.disconnect to R.drawable.ic_l7_close,
        R.string.l7_cancel_connection to R.drawable.ic_l7_close,
        R.string.l7_debug_stop to R.drawable.ic_l7_close,
        R.string.l7_exit_app to R.drawable.ic_l7_exit,
        R.string.l7_agreement_entry to R.drawable.ic_l7_agreement,
        R.string.l7_agreement_accept to R.drawable.ic_l7_check,
        R.string.l7_agreement_decline to R.drawable.ic_l7_exit,
        R.string.l7_agreement_back to R.drawable.ic_l7_back,
        R.string.l7_agreement_revoke to R.drawable.ic_l7_revoke,
        R.string.l7_agreement_revoke_confirm to R.drawable.ic_l7_revoke,
    )

    fun action(context: Context, title: String) = actions.entries.firstOrNull { context.getString(it.key) == title }?.value

    private val settings = mapOf(
        R.string.carplay_size to R.drawable.ic_dp_display,
        R.string.resolution to R.drawable.ic_dp_display,
        R.string.frame_rate to R.drawable.ic_dp_display,
        R.string.efficient_video to R.drawable.ic_l7_projection,
        R.string.full_screen to R.drawable.ic_dp_display,
        R.string.l7_desktop_title to R.drawable.ic_l7_projection,
        R.string.l7_floating_transparency to R.drawable.ic_dp_display,
        R.string.music_buffer to R.drawable.ic_l7_audio,
        R.string.contrib_audio_home_toggle_audio_focus to R.drawable.ic_l7_audio,
        R.string.advanced_audio_channel_mapping to R.drawable.ic_l7_audio,
        R.string.l7_bt_media_auto to R.drawable.ic_l7_bluetooth,
        R.string.connect_when_diplay_opens to R.drawable.ic_dp_automation,
        R.string.open_after_the_car_starts to R.drawable.ic_dp_automation,
        R.string.report_location_to_iphone to R.drawable.ic_dp_navigation,
        R.string.language_app_language to R.drawable.ic_l7_settings,
        R.string.l7_debug_state_title to R.drawable.ic_dp_diagnostics,
        R.string.l7_app_version to R.drawable.ic_dp_about,
        R.string.made_possible_by_open_source to R.drawable.ic_dp_about,
    )

    fun setting(context: Context, title: String): Int =
        settings.entries.firstOrNull { context.getString(it.key) == title }?.value
            ?: action(context, title) ?: R.drawable.ic_l7_settings

    private val dialogs = mapOf(
        R.string.l7_licenses_title to R.drawable.ic_l7_agreement,
        R.string.l7_exit_title to R.drawable.ic_l7_exit,
        R.string.l7_agreement_revoke to R.drawable.ic_l7_revoke,
        R.string.l7_floating_transparency to R.drawable.ic_dp_display,
        R.string.l7_auth_title to R.drawable.ic_l7_lock,
        R.string.l7_auth_quick_import to R.drawable.ic_l7_import,
        R.string.l7_auth_remote to R.drawable.ic_l7_lock,
        R.string.frame_rate to R.drawable.ic_dp_display,
        R.string.resolution to R.drawable.ic_dp_display,
        R.string.carplay_size to R.drawable.ic_dp_display,
        R.string.car_hotspot_details to R.drawable.ic_l7_hotspot,
        R.string.language_app_language to R.drawable.ic_l7_settings,
        R.string.car_hotspot_is_off to R.drawable.ic_l7_warning,
        R.string.turn_on_bluetooth to R.drawable.ic_l7_bluetooth,
        R.string.pair_your_iphone to R.drawable.ic_l7_phone,
        R.string.choose_your_iphone to R.drawable.ic_l7_phone,
        R.string.diagnostic_report_saved to R.drawable.ic_l7_check,
        R.string.could_not_save_the_report to R.drawable.ic_l7_warning,
    )
    fun dialog(context: Context, title: String) = dialogs.entries.firstOrNull { context.getString(it.key) == title }?.value ?: action(context, title)

    fun decorate(button: Button, icon: Int? = action(button.context, button.text.toString())) {
        button.compoundDrawablePadding = L7Components.dp(button.context, 10)
        val leading = icon?.let { resource ->
            button.context.getDrawable(resource)?.apply {
                val size = L7Components.dp(button.context, 32)
                setBounds(0, 0, size, size)
                setTintList(button.textColors)
            }
        }
        // 左图标、右等宽占位，文字始终位于整个按钮的水平中心。
        val trailing = leading?.let { ColorDrawable(Color.TRANSPARENT).apply { bounds = it.bounds } }
        button.setCompoundDrawablesRelative(leading, null, trailing, null)
    }
}
