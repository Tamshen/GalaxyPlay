package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import com.shilapi.xcertplay.host.R

/** 名称计数只在用户确认保存成功后递增，不作为车型参数或连接条件。 */
internal object GalaxyConfigurationNames {
    private fun preferences(context: Context) = context.getSharedPreferences("galaxy_configuration_names", 0)
    fun next(context: Context): String = context.getString(R.string.config_share_default_name,
        preferences(context).getInt("next", 1).coerceIn(1, 999999))
    /** 自动名称随当前语言显示；只调整展示，不因切换语言改写车型文件。 */
    fun display(context: Context, name: String): String = when {
        legacy(context, name) -> context.getString(R.string.config_share_default_name, 1)
        else -> ordinal(name)?.let { context.getString(R.string.config_share_default_name, it) } ?: name
    }
    fun forSave(context: Context, profile: GalaxyProfile): String =
        if (ordinal(profile.name) != null || legacy(context, profile.name)) next(context) else profile.name
    private fun ordinal(name: String): Int? = Regex("(?:配置|Profile )([0-9]{2,6})")
        .matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999999 }
    private fun legacy(context: Context, name: String): Boolean = listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).any { locale ->
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        name == context.createConfigurationContext(configuration).getString(R.string.profile_migrated)
    }
    fun used(context: Context) {
        val prefs = preferences(context)
        prefs.edit().putInt("next", (prefs.getInt("next", 1) + 1).coerceIn(1, 999999)).apply()
    }
}
