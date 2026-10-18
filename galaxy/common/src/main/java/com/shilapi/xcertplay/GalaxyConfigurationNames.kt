package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import com.shilapi.xcertplay.host.R

/** 只为新文件分配名称；覆盖保存保持名称，自动名称按当前语言显示。 */
internal object GalaxyConfigurationNames {
    fun next(context: Context): String = context.getString(R.string.config_share_default_name, 1)
    /** 自动名称随当前语言显示；只调整展示，不因切换语言改写车型文件。 */
    fun display(context: Context, name: String): String = when {
        legacy(context, name) -> context.getString(R.string.config_share_default_name, 1)
        else -> ordinal(name)?.let { context.getString(R.string.config_share_default_name, it) } ?: name
    }
    fun forSave(context: Context, profile: GalaxyProfile, savedNames: List<String> = emptyList()): String {
        if (profile.revision > 0) return display(context, profile.name)
        val occupied = savedNames.mapNotNull(::ordinal).toSet()
        var number = 1
        while (number < 999999 && (number in occupied || savedNames.any {
            it.trim().equals(context.getString(R.string.config_share_default_name, number), true)
        })) number++
        return context.getString(R.string.config_share_default_name, number)
    }
    private fun ordinal(name: String): Int? = Regex("(?:配置|Profile )([0-9]{2,6})")
        .matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999999 }
    private fun legacy(context: Context, name: String): Boolean = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).any { locale ->
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        name == context.createConfigurationContext(configuration).getString(R.string.profile_migrated)
    }
}
