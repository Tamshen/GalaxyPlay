package com.shilapi.xcertplay

import android.content.Context
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

/** 车型入口从目录生成；新增已适配模板只需登记名称和参数构造，不改页面布局。 */
internal object GalaxyVehicleTemplates {
    data class Entry(val id: String, @StringRes val title: Int, val create: (Context) -> GalaxyConfiguration)

    val entries = listOf(
        Entry("l7", R.string.template_l7) { GalaxyConfigurationFields.factory(it, "l7") },
        Entry("l6", R.string.template_l6) { GalaxyConfigurationFields.factory(it, "l6") },
        Entry("custom", R.string.template_custom) { GalaxyConfigurationFields.factory(it, "custom") }
    )

    fun find(id: String) = entries.firstOrNull { it.id == id }
}
