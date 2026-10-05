package com.shilapi.xcertplay

import android.os.Build

/** 只匹配用户确认的车型标识；冲突和未知保持手动选择，不保存固件原文。 */
internal object L7AudioModelDetector {
    fun detect(device: String = Build.DEVICE, product: String = Build.PRODUCT,
               model: String = Build.MODEL): L7AudioTemplates.Model? {
        val values = listOf(device, product, model)
        val found = L7AudioTemplates.Model.entries.filter { candidate ->
            val code = if (candidate == L7AudioTemplates.Model.L7) "g636" else "g733"
            val token = Regex("(?i)(?:^|[^a-z0-9])$code(?:$|[^a-z0-9])")
            values.any { token.containsMatchIn(it) }
        }
        return found.singleOrNull()
    }
}
