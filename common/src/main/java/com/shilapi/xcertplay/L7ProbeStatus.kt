package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

/** 展示结论不扩大证据范围；未知定义不等于不支持，授权不等于业务调用成功。 */
internal enum class L7ProbeStatus(val label: Int, val color: Int, val icon: Int) {
    SUPPORTED(R.string.l7_probe_supported, R.color.l7_probe_green, R.drawable.ic_l7_check),
    GRANTED(R.string.l7_probe_granted, R.color.l7_probe_green, R.drawable.ic_l7_check),
    NO_PERMISSION(R.string.l7_probe_no_permission, R.color.product_ui_muted, R.drawable.ic_l7_probe_minus),
    ERROR(R.string.l7_probe_error, R.color.l7_probe_yellow, R.drawable.ic_l7_warning),
    UNSUPPORTED(R.string.l7_probe_unsupported, R.color.product_ui_danger, R.drawable.ic_l7_close),
    PENDING(R.string.l7_probe_pending, R.color.product_ui_muted, R.drawable.ic_l7_probe_minus),
    NOT_RUN(R.string.l7_probe_not_run, R.color.product_ui_muted, R.drawable.ic_l7_probe_minus),
    NOT_APPLICABLE(R.string.l7_probe_not_applicable, R.color.product_ui_muted, R.drawable.ic_l7_probe_minus);

    fun matches(filter: Int) = when (filter) {
        1 -> this == SUPPORTED || this == GRANTED
        2 -> this == NO_PERMISSION
        3 -> this == ERROR
        4 -> this == UNSUPPORTED
        5 -> this == PENDING || this == NOT_APPLICABLE
        6 -> this == NOT_RUN
        else -> true
    }

    companion object {
        fun of(item: L7ProbeItem): L7ProbeStatus = when {
            item.reason == "NOT_RUN" -> NOT_RUN
            item.result == L7ProbeOutcome.DENIED -> NO_PERMISSION
            item.id in setOf("ENV-OVERLAY", "ENV-WRITE-SETTINGS") && item.facts["allowed"] == "false" -> NO_PERMISSION
            item.reason == "FEATURE_NOT_SUPPORTED" || item.id == "ENV-USB" && item.facts["usbHostFeature"] == "false" -> UNSUPPORTED
            item.result == L7ProbeOutcome.FAILED || item.reason == "QUERY_FAILED" -> ERROR
            item.result == L7ProbeOutcome.VERIFIED -> SUPPORTED
            item.result == L7ProbeOutcome.OBSERVED && item.reason == "GRANTED_NOT_CALLED" -> GRANTED
            item.result == L7ProbeOutcome.NOT_APPLICABLE -> NOT_APPLICABLE
            else -> PENDING
        }
    }
}
