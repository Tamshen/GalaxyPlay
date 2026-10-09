package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 所选硬件不可用时显示默认策略；修改只从下次连接生效。 */
internal object GalaxyVideoDecoderSettings {
    fun add(context: Context, parent: LinearLayout,
            available: Set<String> = GalaxyVideoDecoderPreferences.availableHardwareDecoders()) {
        val model = L7AudioTemplates.model(context)
        val options = GalaxyVideoDecoderPreferences.options(context, available)
        val current = GalaxyVideoDecoderPreferences.load(context, available)
        fun label(mode: GalaxyVideoDecoderPreferences.Mode) = context.getString(when (mode) {
            GalaxyVideoDecoderPreferences.Mode.DEFAULT -> R.string.galaxy_video_decoder_default
            GalaxyVideoDecoderPreferences.Mode.C2 -> R.string.galaxy_video_decoder_c2
            GalaxyVideoDecoderPreferences.Mode.OMX -> R.string.galaxy_video_decoder_omx
        })
        val title = context.getString(R.string.galaxy_video_decoder_title)
        val detail = context.getString(if (options.size == 1) R.string.galaxy_video_decoder_unavailable
            else R.string.galaxy_video_decoder_hint)
        if (options.size == 1) {
            parent.addView(L7SettingRow(context, title, detail).apply { setValue(label(current)) })
            return
        }
        lateinit var row: L7SettingRow
        row = L7Components.actionRow(context, title, detail) {
            val selected = GalaxyVideoDecoderPreferences.load(context, available)
            L7Components.select(context, title, options.map(::label), options.indexOf(selected),
                context.getString(R.string.l7_save_next_connection)) { index ->
                if (runCatching { GalaxyVideoDecoderPreferences.save(context, options[index], model) }.isSuccess)
                    row.setValue(label(options[index]))
                else Toast.makeText(context, R.string.l7_template_save_failed, Toast.LENGTH_LONG).show()
            }
        }.apply { setValue(label(current)) }
        parent.addView(row)
    }
}
