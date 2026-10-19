package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 真正经过 TCP、Telnet 协商和 shell 命令；仅将 wpa_cli 替换为临时目录中的合成无线服务。 */
class GalaxyP2pAutoJoinE2eTest {
    @get:Rule val temporary = TemporaryFolder()
    private val info = WirelessHotspotInfo("synthetic", "Synthetic9", Iap2WirelessSecurity.WPA_WPA2,
        149, 5745, null, "p2p0", InetAddress.getByName("192.168.49.1"), "5 GHz", WirelessHotspotBackend.WIFI_P2P)

    @Test fun coldStartThenReconnectExecutesRealShellAndKeepsExactlyOneElementPerFrame() {
        val directory = temporary.newFolder()
        val script = java.io.File(directory, "wpa-test").apply {
            writeText("""
                case "${'$'}3" in
                  vendor_elem_get) cat "${'$'}P2P_TEST_DIR/frame-${'$'}4" ;;
                  vendor_elem_add) printf '%s' "${'$'}5" >> "${'$'}P2P_TEST_DIR/frame-${'$'}4"; printf 'OK\n' ;;
                  *) exit 2 ;;
                esac
            """.trimIndent())
        }
        java.io.File(directory, "frame-2").writeText("dd0411223344")
        java.io.File(directory, "frame-3").writeText("")
        val logs = mutableListOf<String>()
        Peer { line ->
            val command = line.replace("/vendor/bin/wpa_cli", "sh '${script.absolutePath}'")
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).apply {
                environment()["P2P_TEST_DIR"] = directory.absolutePath
            }.start()
            val response = process.inputStream.bufferedReader().readText()
            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
            response
        }.use { peer ->
            repeat(2) {
                GalaxyP2pAutoJoin.ensure(info, { false }, logs::add) { deadline -> peer.connect(deadline) }
            }
        }
        assertEquals("dd0411223344" + GalaxyP2pAutoJoin.element, java.io.File(directory, "frame-2").readText())
        assertEquals(GalaxyP2pAutoJoin.element, java.io.File(directory, "frame-3").readText())
        assertEquals(2, logs.count { "stage=ADD" in it })
        assertEquals(2, logs.count { "stage=VERIFIED" in it })
        assertTrue(logs.last().contains("changed=false"))
    }

    @Test fun scanPauseHelperUsesFramedMaintenanceShellAndParsesOnlyExecutedResult() {
        val commands = mutableListOf<String>()
        Peer { line ->
            commands += line
            // 只替换 Android 助手，保留真实 shell 的引用、分隔和回执执行。
            val command = line.replace(Regex("CLASSPATH=.*?WifiScanPauseMain (pause|restore)")) {
                "printf 'DIPLAY_WIFI_SCAN_V1|DONE\\n'"
            }
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText().also {
                check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
            }
        }.use { peer ->
            for (enabled in listOf(false, true)) {
                assertEquals(WifiScanSwitchResult.DONE, GalaxyWifiScanShell.run(
                    WifiScanPause.command("/data/app/synthetic/base.apk", enabled), 30, peer::connect))
            }
        }
        assertTrue(commands[0].contains("WifiScanPauseMain pause"))
        assertTrue(commands[1].contains("WifiScanPauseMain restore"))
        Peer { line -> "$line\nDIPLAY_WIFI_SCAN_V1|DONE\n" }.use { peer ->
            assertEquals(WifiScanSwitchResult.UNKNOWN, GalaxyWifiScanShell.run(
                WifiScanPause.command("/data/app/synthetic/base.apk", false), 30, peer::connect))
        }
    }

    @Test fun echoedCommandAndFakeOkWithoutExecutedMarkersAreRejected() {
        Peer { line -> "$line\nOK\n" }.use { peer ->
            assertThrows(WirelessStartupException::class.java) {
                GalaxyP2pAutoJoin.ensure(info, { false }, {}) { deadline -> peer.connect(deadline) }
            }
        }
    }

    @Test fun cancellationUnblocksSilentPromptAndClosesTheSocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            val cancelled = AtomicBoolean()
            val job = worker.submit<Any> {
                assertThrows(java.io.IOException::class.java) {
                    GalaxyP2pLoopback.attach(Socket(InetAddress.getLoopbackAddress(), server.localPort),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(3), cancelled::get)
                }
            }
            try {
                server.accept().use { peer ->
                    cancelled.set(true)
                    job.get(1, TimeUnit.SECONDS)
                    peer.soTimeout = 1000
                    assertEquals(-1, peer.getInputStream().read())
                }
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun unavailableServiceOnlyTriesTheTwoLoopbackAddressesAndClosesBothSockets() {
        val targets = mutableListOf<java.net.InetSocketAddress>()
        var closed = 0
        assertThrows(java.io.IOException::class.java) {
            GalaxyP2pLoopback.open(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), { false }) {
                object : Socket() {
                    override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                        targets += endpoint as java.net.InetSocketAddress
                        assertTrue(timeout in 1..350)
                        throw java.io.IOException("synthetic unavailable service")
                    }
                    override fun close() { closed++ }
                }
            }
        }
        assertEquals(2, targets.size); assertEquals(2, closed)
        assertTrue(targets.all { it.address.isLoopbackAddress && it.port == 23 })
    }

    private class Peer(private val execute: (String) -> String) : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        private val stopped = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread({
            try {
                while (!stopped.get()) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(255.toByte(), 251.toByte(), 1)); out.flush()
                    check(socket.getInputStream().readNBytes(3).contentEquals(byteArrayOf(255.toByte(), 254.toByte(), 1)))
                    out.write("headunit:/ ".toByteArray()); out.flush()
                    out.write("# ".toByteArray()); out.flush()
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        out.write((line + "\r\n" + execute(line) + "headunit:/ # ").toByteArray()); out.flush()
                    }
                }
            } catch (error: Exception) { if (!stopped.get()) failure.set(error) }
        }, "synthetic-p2p-maintenance").apply { isDaemon = true; start() }

        fun connect(deadline: Long) = GalaxyP2pLoopback.attach(
            Socket(InetAddress.getLoopbackAddress(), server.localPort), deadline) { false }
        override fun close() {
            stopped.set(true); server.close(); thread.join(1000)
            assertFalse(thread.isAlive); assertNull(failure.get())
        }
    }
}
