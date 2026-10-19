package com.shilapi.xcertplay.transport

/** 只接受固件已定义的完整组合，不凭 ncm/iap 关键词自行创建 configfs 或写控制器寄存器。 */
internal object GalaxyUsbGadgetRules {
    const val CONFIG = "iap,ncm"
    fun supports(text: String): Boolean {
        var selected = false
        var iap = false
        var ncm = false
        var bound = false
        fun complete() = selected && iap && ncm && bound
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("on ")) {
                if (complete()) return true
                selected = Regex("(?:^|\\s)property:sys\\.usb\\.config=iap,ncm(?:\\s|$)").containsMatchIn(line)
                selected = selected && line.removePrefix("on ").split("&&").all {
                    it.trim() in setOf("property:sys.usb.config=iap,ncm", "property:sys.usb.configfs=1")
                }
                iap = false; ncm = false; bound = false
            } else if (selected) {
                if (line.startsWith("symlink ")) {
                    iap = iap || line.contains("/functions/iap.")
                    ncm = ncm || line.contains("/functions/ncm.")
                }
                bound = bound || line.startsWith("write ") && line.contains("/UDC ")
            }
        }
        return complete()
    }
    fun safeConfig(value: String): Boolean = value.isNotEmpty() && value.length <= 96 &&
        value.split(',').all { it in setOf("none", "adb", "diag", "mtp", "ptp", "rndis", "ncm", "iap",
            "accessory", "audio_source", "midi", "serial_cdev", "rmnet", "qdss", "dpl", "mass_storage") }
    fun safeController(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.-]{1,96}")) && value != ".."
}
