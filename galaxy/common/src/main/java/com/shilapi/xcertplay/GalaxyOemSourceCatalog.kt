package com.shilapi.xcertplay

/** 只更新自身目录项；写前重读，写后分别核对自身字段和其他应用字段。 */
internal object GalaxyOemSourceCatalog {
    data class Fields(
        val packageName: String?, val appName: String?, val iconPath: String?, val sources: List<Int>,
        val priority: Int, val running: Boolean, val multipleOpen: Boolean,
    )
    data class Entry<T>(val value: T, val fields: Fields)

    fun <T> ensure(
        packageName: String, source: Int, read: () -> List<Entry<T>>?,
        create: (Entry<T>?) -> Entry<T>, write: (List<T>) -> Unit, active: () -> Boolean,
    ): String {
        if (packageName.isBlank() || source < 0) return "INVALID_SOURCE"
        if (!active()) return "STALE"
        val initial = read()
        if (!active()) return "STALE"
        invalid(initial)?.let { return it }
        val initialOwn = initial!!.firstOrNull { it.fields.packageName == packageName }
        val proposed = create(initialOwn)
        if (!validOwn(proposed, packageName, source)) return "INVALID_ENTRY"
        if (initialOwn?.fields == proposed.fields) return "PRESENT"
        if (!active()) return "STALE"
        // 配置属于原厂服务，其他客户端可能在构造目录项期间改过列表。
        val before = read()
        if (!active()) return "STALE"
        invalid(before)?.let { return it }
        val own = before!!.firstOrNull { it.fields.packageName == packageName }
        val desired = create(own)
        if (!validOwn(desired, packageName, source)) return "INVALID_ENTRY"
        if (own?.fields == desired.fields) return "PRESENT"
        if (!active()) return "STALE"
        val replacement = before.map { if (it.fields.packageName == packageName) desired.value else it.value }
        write(if (own == null) replacement + desired.value else replacement)
        if (!active()) return "STALE"
        val after = read()
        if (!active()) return "STALE"
        return verify(packageName, before, desired, after, own != null)
    }

    private fun <T> validOwn(entry: Entry<T>, packageName: String, source: Int) =
        entry.fields.packageName == packageName && source in entry.fields.sources

    private fun <T> invalid(entries: List<Entry<T>>?): String? = when {
        entries.isNullOrEmpty() -> "CONFIG_UNAVAILABLE"
        entries.any { it.fields.packageName.isNullOrBlank() } ||
            entries.map { it.fields.packageName }.distinct().size != entries.size -> "CONFIG_AMBIGUOUS"
        else -> null
    }

    private fun <T> verify(packageName: String, before: List<Entry<T>>, desired: Entry<T>,
                           after: List<Entry<T>>?, updating: Boolean): String {
        if (invalid(after) != null) return "READBACK_MISSING"
        val retained = after!!.associate { it.fields.packageName to it.fields }
        if (retained[packageName] != desired.fields) return "READBACK_MISMATCH"
        if (before.any { it.fields.packageName != packageName && retained[it.fields.packageName] != it.fields })
            return "READBACK_CHANGED"
        return if (updating) "UPDATED" else "ADDED"
    }
}
