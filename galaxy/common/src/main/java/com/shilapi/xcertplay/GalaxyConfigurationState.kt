package com.shilapi.xcertplay

import androidx.lifecycle.ViewModel

/** 当前配置与草稿只留内存，页面重建不提交参数或携带凭据进 Bundle。 */
internal class GalaxyConfigurationState : ViewModel() {
    var original: GalaxyProfile? = null
    var draft: GalaxyProfile? = null
    var selected: GalaxyProfile? = null
    var sourceTemplate: String? = null
    var group = 0
    val scroll = mutableMapOf<Int, Int>()
    val dirty get() = draft?.json()?.toString() != original?.json()?.toString()
    fun load(profile: GalaxyProfile, active: GalaxyProfile = profile, source: String? = null) {
        original = profile
        draft = profile
        selected = active
        sourceTemplate = source
        scroll.clear()
    }
}
