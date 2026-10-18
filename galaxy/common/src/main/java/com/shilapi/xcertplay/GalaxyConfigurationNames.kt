package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** 名称计数只属于分享操作，不作为车型参数或连接条件。 */
internal object GalaxyConfigurationNames {
    private fun preferences(context: Context) = context.getSharedPreferences("galaxy_configuration_sharing", 0)
    fun next(context: Context): String = context.getString(R.string.config_share_default_name,
        preferences(context).getInt("next", 1).coerceIn(1, 999999))
    fun forShare(context: Context, profile: GalaxyProfile): String = profile.name.takeUnless {
        it == context.getString(R.string.profile_migrated) || it.matches(Regex("(?:配置|Profile )[0-9]{2,6}"))
    } ?: next(context)
    fun used(context: Context) {
        val prefs = preferences(context)
        prefs.edit().putInt("next", (prefs.getInt("next", 1) + 1).coerceIn(1, 999999)).apply()
    }
}
