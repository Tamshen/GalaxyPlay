package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** 原厂服务可能占用 7000；改用可用端口后，通过 Bonjour 与 iAP2 告知手机。 */
object AirPlayPortSelector {
    /** 首选端口被占用后依次尝试，最后由系统分配临时端口。 */
    val FALLBACK_PORTS: IntRange = 7001..7010

    /** Specific per-family listeners avoid relying on a platform's IPV6_V6ONLY default. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServerSocket> {
        require(addresses.isNotEmpty()) { "At least one listener address is required" }
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        for (candidate in candidates) {
            val servers = mutableListOf<ServerSocket>()
            try {
                for (address in addresses.distinct()) {
                    servers.add(bindPort(address, servers.firstOrNull()?.localPort ?: candidate))
                }
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                if (error is BindException) continue
                throw error
            }
            try {
                val port = servers.first().localPort
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return servers
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                throw error
            }
        }
        throw BindException("No common AirPlay port available for the selected interface addresses")
    }

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        return reportFallback(bindPort(address, 0), preferredPort, onFallback)
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? = try {
        bindPort(address, port)
    } catch (_: BindException) {
        null
    }

    private fun bindPort(address: InetAddress, port: Int): ServerSocket {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (error: Throwable) {
            closeAfterFailure(server, error)
            throw error
        }
    }

    private fun reportFallback(
        server: ServerSocket,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): ServerSocket = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // 通知成功后才移交所有权，通知失败也必须关闭监听。
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: ServerSocket, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}
