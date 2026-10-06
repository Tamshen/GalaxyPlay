package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** 首页只承担连接入口；配置和认证仍进入统一设置页。 */
internal class L7HomePanel(
    context: Context,
    onQuickConnect: () -> Unit,
    onWireless: () -> Unit,
    onUsb: () -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    private val help = L7Components.text(context, context.getString(R.string.l7_projection_placeholder), secondary = true)
    private val status = L7Components.text(context, "", secondary = true)
    internal val quick = action(R.string.l7_entry_connect, true, onQuickConnect)
    internal val wireless = action(R.string.l7_start_wireless, false, onWireless)
    internal val usb = action(R.string.l7_home_usb, false, onUsb)
    internal val settings = action(R.string.settings, false, onSettings)
    private var wirelessPrimary = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = context.getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(dp(80), dp(80)).apply { bottomMargin = dp(24) })
        addView(L7Components.text(context, context.getString(R.string.l7_entry_title)).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, entry())
        help.gravity = Gravity.CENTER
        addView(help, entry(32))
        status.gravity = Gravity.CENTER
        addView(status, entry(20))
        addView(quick, entry(40))
        addView(wireless, entry(16))
        addView(usb, entry(16))
        addView(settings, entry(16))
    }

    fun update(configured: Boolean, running: Boolean, connected: Boolean, pending: Boolean, error: String?) {
        quick.visibility = if (configured || running || pending) View.VISIBLE else View.GONE
        val promoteWireless = quick.visibility == View.GONE
        if (wirelessPrimary != promoteWireless) {
            wirelessPrimary = promoteWireless
            L7Ui.button(wireless, wirelessPrimary)
        }
        val title = when {
            pending -> R.string.l7_preparing
            connected -> R.string.l7_resume_projection
            running -> R.string.l7_view_connection
            else -> R.string.l7_entry_connect
        }
        if (quick.text.toString() != context.getString(title)) {
            quick.setText(title)
            L7Icons.decorate(quick)
        }
        val enabled = !pending && (running || error == null)
        if (quick.isEnabled != enabled) {
            quick.isEnabled = enabled
            L7Ui.refresh(quick)
        }
        usb.isEnabled = !running && !pending
        val message = error ?: context.getString(when {
            connected -> R.string.l7_session_connected_hint
            running -> R.string.l7_session_waiting_hint
            configured -> R.string.ready_when_you_are
            else -> R.string.l7_home_first_use
        })
        if (status.text.toString() != message) status.text = message
    }

    private fun action(title: Int, primary: Boolean, onClick: () -> Unit) =
        L7Components.actionButton(context, context.getString(title), primary, onClick)

    private fun entry(top: Int = 0) = LayoutParams(-1, -2).apply {
        topMargin = L7Components.dp(context, top)
    }

    private fun dp(value: Int) = L7Components.dp(context, value)

    companion object {
        fun configured(context: Context): Boolean {
            // 默认方式为无线；USB 无需热点配置，曾明确选择 USB 即可再次发起。
            if (!AirPlayPersistence.loadWirelessEnabled(context)) return true
            return DiPlayPreferences.phoneAddress(context) != null && ManualHotspotValidation.error(
                AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context),
            ) == null
        }
    }
}
