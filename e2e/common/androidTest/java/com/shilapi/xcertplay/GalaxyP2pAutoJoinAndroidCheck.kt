package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.GalaxyP2pAutoJoin
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android VM 中执行产品准备器与真实 shell；合成 wpa 服务不访问车机维护端口。 */
object GalaxyP2pAutoJoinAndroidCheck {
    @JvmStatic fun run(context: Context) {
        check(android.os.Build.VERSION.SDK_INT == 30)
        val directory = File(context.cacheDir, "p2p-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val script = File(directory, "wpa-test.sh").apply { writeText("""
            case "${'$'}3" in
              vendor_elem_get) cat "${'$'}P2P_TEST_DIR/frame-${'$'}4" ;;
              vendor_elem_add) if [ -f "${'$'}P2P_TEST_DIR/reject" ]; then echo FAIL; else
                printf '%s' "${'$'}5" >> "${'$'}P2P_TEST_DIR/frame-${'$'}4"; echo OK; fi ;;
              *) exit 2 ;;
            esac
        """.trimIndent()) }
        val frame2 = File(directory, "frame-2").apply { writeText("dd0411223344") }
        val frame3 = File(directory, "frame-3").apply { writeText("") }
        val loopback = Class.forName("com.shilapi.xcertplay.network.GalaxyP2pLoopback")
        val companion = loopback.getField("Companion").get(null)
        val attach = companion.javaClass.declaredMethods.first { it.name.startsWith("attach") && it.parameterTypes.size == 3 }
            .apply { isAccessible = true }
        val ensure = GalaxyP2pAutoJoin::class.java.declaredMethods.first {
            it.name.startsWith("ensure") && it.parameterTypes.size == 4 && it.parameterTypes[0] == WirelessHotspotInfo::class.java
        }
            .apply { isAccessible = true }
        val info = WirelessHotspotInfo("synthetic", "Synthetic9", Iap2WirelessSecurity.WPA_WPA2,
            149, 5745, null, "p2p0", InetAddress.getByName("192.168.49.1"), "5 GHz", WirelessHotspotBackend.WIFI_P2P)
        val logs = mutableListOf<String>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val peer = Thread({
            try {
                repeat(3) {
                    server.accept().use { socket ->
                        socket.soTimeout = 6000
                        val output = socket.getOutputStream()
                        output.write("avd:/ # ".toByteArray()); output.flush()
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = try { input.readLine() } catch (_: java.net.SocketException) { null } ?: break
                            val command = line.replace("/vendor/bin/wpa_cli", "/system/bin/sh '${script.absolutePath}'")
                            val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                                .apply { environment()["P2P_TEST_DIR"] = directory.absolutePath }.start()
                            val response = process.inputStream.bufferedReader().readText()
                            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
                            output.write((line + "\r\n" + response + "avd:/ # ").toByteArray()); output.flush()
                        }
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }, "p2p-android-e2e").apply { isDaemon = true; start() }
        try {
            val cancelled = { false }
            val report: (String) -> Unit = logs::add
            val connect: (Long) -> Any = { deadline ->
                attach.invoke(companion, Socket(InetAddress.getLoopbackAddress(), server.localPort), deadline, cancelled)
            }
            try { repeat(2) { ensure.invoke(GalaxyP2pAutoJoin, info, cancelled, report, connect) } }
            catch (error: Exception) { throw IllegalStateException(logs.joinToString(";"), error) }
            check(frame2.readText() == "dd0411223344dd0800a0400000020021")
            check(frame3.readText() == "dd0800a0400000020021")
            check(logs.count { "stage=ADD" in it } == 2 && logs.count { "stage=VERIFIED" in it } == 2)
            frame2.writeText(""); frame3.writeText(""); File(directory, "reject").writeText("synthetic")
            val rejected = runCatching { ensure.invoke(GalaxyP2pAutoJoin, info, cancelled, report, connect) }.exceptionOrNull()
            check(rejected != null && logs.last().contains("WRITE_REJECTED"))
            check(logs.count { "stage=VERIFIED" in it } == 2)
            peer.join(2000); check(!peer.isAlive && failure.get() == null)
        } finally { server.close(); peer.join(1000); directory.deleteRecursively() }
    }
}
