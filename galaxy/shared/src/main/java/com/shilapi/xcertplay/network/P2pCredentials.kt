package com.shilapi.xcertplay.network

/** 同一轮建组凭据在内存核对；厂商带引号的回读不应作为密码字符发给手机。 */
internal object P2pCredentials {
    fun passphrase(reported: String?, requested: String?): String {
        val actual = reported?.takeIf { it.isNotBlank() }
        val unquoted = actual?.takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }
            ?.substring(1, actual.length - 1)
        val selected = when {
            requested != null && actual == requested -> requested
            requested != null && unquoted == requested -> requested
            requested != null && actual == null -> requested
            requested != null -> throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Wi-Fi Direct credential readback does not match this startup request")
            else -> unquoted ?: actual
        }
        if (selected == null || selected.length !in 8..63 || '\u0000' in selected)
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Wi-Fi Direct did not provide a usable credential")
        return selected
    }

    fun accessPointHint(address: String?): ByteArray? {
        if (address == null || !address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = address.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return bytes.takeUnless { it.all { value -> value == 0.toByte() } || it[0].toInt() and 1 != 0 ||
            address.equals("02:00:00:00:00:00", true) }
    }
}
