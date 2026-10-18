package com.shilapi.xcertplay.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/** 只访问本机已有维护端口；固定命令，不启动服务、不尝试账号密码、不记录终端原文。 */
internal class GalaxyP2pLoopback private constructor(private val socket: Socket,
    private val deadline: Long, private val cancelled: () -> Boolean) : GalaxyP2pAutoJoin.Shell {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun run(command: String): String {
        checkCurrent()
        val id = UUID.randomUUID().toString().replace("-", "")
        val begin = "GALAXY_P2P_BEGIN_$id"
        val end = "GALAXY_P2P_END_$id"
        val framed = "printf '\\n$begin\\n'; $command; printf '\\n$end:%s\\n' \"\$?\"\n"
        output.write(framed.toByteArray(Charsets.US_ASCII)); output.flush()
        val lines = prompt().replace("\r", "").lines()
        val start = lines.indexOf(begin)
        val finish = lines.indexOfFirst { it.startsWith("$end:") }
        if (start < 0 || finish <= start || lines[finish] != "$end:0")
            throw IOException("P2P_AUTO_JOIN_SHELL_REJECTED")
        return lines.subList(start + 1, finish).joinToString("\n").trim()
    }

    private fun checkCurrent() {
        if (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
            throw IOException("P2P_AUTO_JOIN_CANCELLED_OR_TIMEOUT")
    }

    private fun prompt(until: Long = deadline): String {
        val body = ByteArrayOutputStream()
        var state = 0
        var verb = 0
        var received = 0
        while (received < 32768) {
            checkCurrent()
            if (System.nanoTime() >= until) throw IOException("P2P_AUTO_JOIN_PROMPT_TIMEOUT")
            socket.soTimeout = minOf(100, ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt())
            val value = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (value < 0) throw IOException("P2P_AUTO_JOIN_SHELL_CLOSED")
            received++
            when (state) {
                0 -> if (value == 255) state = 1 else body.write(value)
                1 -> when (value) {
                    255 -> { body.write(value); state = 0 }
                    250 -> state = 3
                    in 251..254 -> { verb = value; state = 2 }
                    else -> state = 0
                }
                2 -> {
                    if (verb == 251 || verb == 253) {
                        output.write(byteArrayOf(255.toByte(), (if (verb == 251) 254 else 252).toByte(), value.toByte()))
                        output.flush()
                    }
                    state = 0
                }
                3 -> if (value == 255) state = 4
                4 -> state = if (value == 240) 0 else 3
            }
            if (state == 0 && value == '#'.code) {
                val text = body.toString("UTF-8")
                if (text.endsWith(":/ #")) return text
            }
        }
        throw IOException("P2P_AUTO_JOIN_RESPONSE_LIMIT")
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        fun open(deadline: Long, cancelled: () -> Boolean, newSocket: () -> Socket = { Socket() }): GalaxyP2pLoopback {
            for (address in listOf(byteArrayOf(127, 0, 0, 1), ByteArray(16).also { it[15] = 1 })) {
                val socket = newSocket()
                try {
                    if (cancelled() || System.nanoTime() >= deadline) throw IOException("P2P_AUTO_JOIN_CANCELLED")
                    socket.connect(InetSocketAddress(InetAddress.getByAddress(address), 23),
                        minOf(350, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt()))
                    return attach(socket, deadline, cancelled)
                } catch (_: IOException) { socket.close() }
            }
            throw IOException("P2P_AUTO_JOIN_LOCAL_SERVICE_UNAVAILABLE")
        }

        internal fun attach(socket: Socket, deadline: Long, cancelled: () -> Boolean): GalaxyP2pLoopback {
            val session = GalaxyP2pLoopback(socket, deadline, cancelled)
            try {
                session.prompt(minOf(deadline, System.nanoTime() + 1_500_000_000))
                return session
            } catch (error: Exception) { session.close(); throw error }
        }
    }
}
