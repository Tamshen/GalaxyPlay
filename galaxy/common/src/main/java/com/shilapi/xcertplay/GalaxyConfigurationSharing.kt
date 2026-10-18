package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputFilter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 分享草稿不写车型；粘贴先预览，确认后才替换当前配置，活动会话仍用旧快照。 */
internal class GalaxyConfigurationSharing(private val activity: ComponentActivity,
    private val original: () -> GalaxyProfile?, private val draft: () -> GalaxyProfile?,
    private val applied: (GalaxyProfile) -> Unit) : Closeable {
    private var closed = false
    private var operation = 0
    private var expected: GalaxyProfile? = null
    private var dialog: AlertDialog? = null
    private val files = GalaxyConfigurationFiles(activity, { result ->
        result.onSuccess { review(it) }.onFailure { notice(R.string.config_share_invalid) }
    }) { success -> notice(if (success) R.string.config_share_saved else R.string.profile_save_failed) }

    fun share() {
        if (closed || files.busy) return
        val profile = draft() ?: return
        val remark = input("", GalaxyConfigurationShare.MAX_REMARK).apply {
            hint = activity.getString(R.string.config_share_remark)
            minLines = 2; maxLines = 3
        }
        dialog?.dismiss()
        dialog = L7Dialogs.builder(activity).setTitle(R.string.config_share_title)
            .setMessage(R.string.config_share_name_hint).setView(remark)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.config_share_generate, null).create()
        dialog!!.setOnShowListener {
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = ++operation
                progress()
                files.prepare(profile, remark.text.toString()) { result ->
                    if (closed || operation != token) return@prepare
                    dialog?.dismiss()
                    result.onSuccess { showShared(it) }.onFailure { notice(R.string.config_share_invalid) }
                }
            }
        }
        dialog!!.show()
    }

    fun paste() {
        if (closed || files.busy) return
        expected = original() ?: return
        ++operation
        val text = input("", GalaxyConfigurationShare.MAX_TEXT).apply { minLines = 3; maxLines = 6 }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text, LinearLayout.LayoutParams(-1, -2))
            addView(L7Components.actionButton(activity, activity.getString(R.string.config_share_paste), R.drawable.ic_l7_copy) {
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                text.setText(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
            })
        }
        dialog?.dismiss()
        dialog = L7Dialogs.builder(activity).setTitle(R.string.config_share_import)
            .setMessage(R.string.config_share_paste_hint).setView(body)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.config_share_preview) { _, _ ->
                if (!closed) files.parse(text.text.toString())
            }.show()
    }

    private fun review(value: GalaxyConfigurationShare) {
        if (closed) return
        val before = expected ?: return
        dialog?.dismiss()
        dialog = L7Dialogs.builder(activity).setTitle(R.string.config_share_preview)
            .setMessage(summary(value) + remark(value) + "\n\n" + activity.getString(R.string.config_share_replace_hint))
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.config_share_apply, null).create()
        dialog!!.setOnShowListener {
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (closed) return@setOnClickListener
                val result = runCatching { GalaxyProfiles(activity).importShared(before, value) }
                result.onSuccess { dialog?.dismiss(); applied(it) }.onFailure { notice(R.string.profile_save_failed) }
            }
        }
        dialog!!.show()
    }

    private fun showShared(prepared: GalaxyConfigurationFiles.Prepared) {
        val value = prepared.value
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (prepared.qr != null) {
            body.addView(ImageView(activity).apply {
                setImageBitmap(prepared.qr); adjustViewBounds = true
                contentDescription = activity.getString(R.string.config_share_qr)
            }, LinearLayout.LayoutParams(-1, L7Components.dp(activity, 300)))
        } else body.addView(L7Components.note(activity, activity.getString(R.string.config_share_qr_large)))
        body.addView(L7Components.actionButton(activity, activity.getString(R.string.config_share_copy), R.drawable.ic_l7_copy) {
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(value.name, prepared.code))
            notice(R.string.config_share_copied)
        })
        body.addView(L7Components.actionButton(activity, activity.getString(R.string.config_share_file), R.drawable.ic_l7_save) {
            files.exportFile(value)
        })
        if (prepared.qr != null) body.addView(L7Components.actionButton(activity,
            activity.getString(R.string.config_share_save_qr), R.drawable.ic_l7_save) { files.exportImage(value.name, prepared.qr) })
        dialog = L7Dialogs.builder(activity).setTitle(GalaxyConfigurationNames.display(activity, value.name))
            .setMessage(activity.getString(R.string.config_share_qr_hint) + remark(value))
            .setView(body).setNegativeButton(R.string.close, null).show()
    }

    private fun summary(value: GalaxyConfigurationShare): String {
        val main = value.configuration.preferences["xcertplay_airplay"].orEmpty()
        val model = value.configuration.preferences["l7_audio_templates"]?.get("model") as? String ?: "custom"
        val mode = com.shilapi.xcertplay.network.P2pAddressMode.fromStored(main[GalaxyP2pAddressSettings.KEY] as? String)
        val modeLabel = activity.getString(when (mode) {
            com.shilapi.xcertplay.network.P2pAddressMode.IPV4_ONLY -> R.string.galaxy_p2p_ipv4_only
            com.shilapi.xcertplay.network.P2pAddressMode.IPV6_ONLY -> R.string.galaxy_p2p_ipv6_only
            else -> R.string.galaxy_p2p_dual_stack
        })
        return activity.getString(R.string.config_share_summary, GalaxyConfigurationNames.display(activity, value.name), model.uppercase(),
            main["display_fps"] as? Int ?: 30, main["display_scale_percent"] as? Int ?: 100, modeLabel,
            activity.getString(if (main["audio_focus_enabled"] == false) R.string.config_share_off else R.string.config_share_on))
    }

    private fun input(value: String, maximum: Int) = EditText(activity).apply {
        L7Ui.input(this); filters = arrayOf(InputFilter.LengthFilter(maximum)); setText(value)
    }
    private fun remark(value: GalaxyConfigurationShare) = if (value.remark.isEmpty()) "" else
        "\n\n" + activity.getString(R.string.config_share_remark_value, value.remark)
    private fun progress() {
        dialog?.dismiss()
        dialog = L7Dialogs.builder(activity).setTitle(R.string.config_share_title).setMessage(R.string.config_share_preparing)
            .setOnCancelListener { operation++ }.show()
    }
    private fun notice(message: Int) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    override fun close() { closed = true; operation++; dialog?.dismiss(); files.close() }
}
