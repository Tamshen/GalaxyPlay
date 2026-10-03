package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AirPlayPortSelectorTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun bindsPreferredPortWhenFree() {
        val preferred = freePort()
        var fallback: Pair<Int, Int>? = null
        AirPlayPortSelector.bind(loopback, preferred, emptyList()) { busy, bound -> fallback = busy to bound }.use {
            assertEquals(preferred, it.localPort)
        }
        assertNull(fallback)
    }

    @Test fun fallsBackWhenAnotherListenerOwnsTheWildcardPort() {
        // 模拟原厂服务监听所有地址，验证同端口的指定地址监听发生冲突。
        ServerSocket().use { factory ->
            factory.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            val busy = factory.localPort
            // 部分 BSD/JDK 允许地址重叠，仅在系统实际禁止重叠时检查 Android/Linux 的冲突回退。
            val wildcardBlocksLoopback = try {
                ServerSocket().use { it.bind(InetSocketAddress(loopback, busy)) }
                false
            } catch (_: java.net.BindException) {
                true
            }
            assumeTrue("Host allows wildcard and specific-address listeners to overlap", wildcardBlocksLoopback)
            val alternative = freePort()
            var fallback: Pair<Int, Int>? = null
            AirPlayPortSelector.bind(loopback, busy, listOf(busy, alternative)) { b, bound -> fallback = b to bound }.use {
                assertEquals(alternative, it.localPort)
                assertEquals(busy to alternative, fallback)
            }
        }
    }

    @Test fun fallsBackWhenAnotherListenerOwnsTheSameAddress() {
        ServerSocket(0, 50, loopback).use { factory ->
            val alternative = freePort()
            var fallback: Pair<Int, Int>? = null
            AirPlayPortSelector.bind(loopback, factory.localPort, listOf(alternative)) { busy, bound ->
                fallback = busy to bound
            }.use {
                assertEquals(alternative, it.localPort)
                assertEquals(factory.localPort to alternative, fallback)
            }
        }
    }

    @Test fun closesFallbackListenerWhenNotificationThrows() {
        assertNotificationFailureClosesListener(useEphemeralPort = false)
    }

    @Test fun closesEphemeralListenerWhenNotificationThrows() {
        assertNotificationFailureClosesListener(useEphemeralPort = true)
    }

    @Test fun usesEphemeralPortWhenAllCandidatesAreBusy() {
        ServerSocket(0, 50, loopback).use { first ->
            ServerSocket(0, 50, loopback).use { second ->
                AirPlayPortSelector.bind(loopback, first.localPort, listOf(second.localPort)).use {
                    assertNotEquals(first.localPort, it.localPort)
                    assertNotEquals(second.localPort, it.localPort)
                    assertTrue(it.localPort in 1..65535)
                }
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 50, loopback).use { it.localPort }

    private fun assertNotificationFailureClosesListener(useEphemeralPort: Boolean) {
        ServerSocket(0, 50, loopback).use { factory ->
            val alternatives = if (useEphemeralPort) emptyList() else listOf(freePort())
            val failure = IllegalStateException("notification failed")
            var selectedPort = 0
            val caught = assertThrows(IllegalStateException::class.java) {
                AirPlayPortSelector.bind(loopback, factory.localPort, alternatives) { _, bound ->
                    selectedPort = bound
                    throw failure
                }
            }
            assertSame(failure, caught)
            assertTrue(selectedPort in 1..65535)
            // 泄漏的监听会阻止再次绑定同一地址和端口。
            ServerSocket().use { replacement ->
                replacement.bind(InetSocketAddress(loopback, selectedPort))
            }
        }
    }
}
