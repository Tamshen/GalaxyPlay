package com.shilapi.xcertplay

/** 展示与真实配置分离，内置地址和已有凭据不进入可复制的输入框。 */
internal class RemoteLogEditor(private val original: RemoteLogConfig, builtInEndpoint: String) {
    // 旧版本可能把默认地址另存为覆盖值；地址相同也必须遮蔽。
    val masksEndpoint = original.endpoint.isNotBlank() && original.endpoint == builtInEndpoint
    val endpointText get() = if (masksEndpoint) "" else original.endpoint
    val hasAuthorization get() = original.authorization.isNotEmpty()

    fun serverText(resolvedEndpoint: String) = if (masksEndpoint) MASK else resolvedEndpoint

    fun resolve(endpointInput: String, authorizationInput: String): RemoteLogConfig {
        val endpoint = endpointInput.trim().ifEmpty { if (masksEndpoint) original.endpoint else "" }
        val authorization = authorizationInput.trim().ifEmpty {
            // 改地址必须提供新认证，避免把打包凭据转发到另一个服务器。
            if (endpoint == original.endpoint) original.authorization else ""
        }
        return RemoteLogConfig(endpoint, authorization)
    }

    companion object { const val MASK = "*****" }
}
