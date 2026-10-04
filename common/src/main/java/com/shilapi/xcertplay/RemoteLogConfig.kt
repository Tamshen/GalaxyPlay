package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.net.URI
import java.util.Base64

/** 应用内配置优先于打包默认值；上传凭据与 MFi 认证完全独立。 */
internal data class RemoteLogConfig(val endpoint: String, val authorization: String) {
    fun valid(): Boolean = validEndpoint(endpoint) && validAuthorization(authorization)
    val stream: String get() = URI(endpoint).path.split('/').dropLast(1).last()

    /** 保留用户配置的服务器、代理前缀及组织，只替换示例中的日志流名称。 */
    fun forDevice(device: String): RemoteLogConfig {
        require(validEndpoint(endpoint))
        require(device.matches(Regex("l7_[a-f0-9]{5}(?:_[a-f0-9]{5}){3}")))
        val organizationUrl = endpoint.substringBeforeLast('/').substringBeforeLast('/')
        return copy(endpoint = "$organizationUrl/$device/_json")
    }

    companion object {
        fun validEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 2048 && uri.scheme in listOf("https", "http") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535) &&
                Regex("(?:/[^/]+)*/api/[^/]+/[^/]+/_json").matches(uri.path)
        }.getOrDefault(false)

        fun validAuthorization(value: String): Boolean = runCatching {
            if (value.length > 4096 || !value.matches(Regex("Basic [A-Za-z0-9+/]+={0,2}"))) return false
            val decoded = Base64.getDecoder().decode(value.removePrefix("Basic ")).toString(Charsets.UTF_8)
            val colon = decoded.indexOf(':')
            colon > 0 && colon < decoded.lastIndex && decoded.none { it.code < 32 || it.code == 127 }
        }.getOrDefault(false)

        fun load(context: Context): RemoteLogConfig {
            val prefs = context.getSharedPreferences("l7_remote_log", Context.MODE_PRIVATE)
            return RemoteLogConfig(
                prefs.getString("endpoint", context.getString(R.string.l7_log_default_url)).orEmpty(),
                prefs.getString("authorization", context.getString(R.string.l7_log_default_authorization)).orEmpty(),
            )
        }

        fun save(context: Context, config: RemoteLogConfig): Boolean = context
            .getSharedPreferences("l7_remote_log", Context.MODE_PRIVATE).edit()
            .putString("endpoint", config.endpoint).putString("authorization", config.authorization).remove("token").commit()

        fun reset(context: Context): Boolean = context
            .getSharedPreferences("l7_remote_log", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
