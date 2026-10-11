package com.shilapi.xcertplay

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyUpdateTransferTest {
    private lateinit var server: HttpServer
    private lateinit var base: URI
    private val directory = Files.createTempDirectory("galaxy-update-test").toFile()
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private fun hash(value: ByteArray) = GalaxyUpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(value))
    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = URI("http://127.0.0.1:${server.address.port}/api/apps/com-ecarx-carplay/latest.json")
        server.start()
    }
    @After fun stop() { server.stop(0); directory.deleteRecursively() }
    private fun serve(path: String, body: ByteArray, code: Int = 200) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
    private fun manifest() = updateManifest(bytes.size, hash(bytes))
    private fun release(json: JSONObject = manifest()) = GalaxyUpdateManifest.parse(json.toString(), base, "com.ecarx.carplay")
    @Test fun directDownloadUsesExactBytesAndRemovesPartialFile() {
        serve(base.path, manifest().toString().toByteArray())
        serve("/downloads/com-ecarx-carplay/v147/update.apk", bytes)
        val transfer = GalaxyUpdateTransfer(base)
        val request = GalaxyUpdateRequest()
        val fetched = transfer.latest("com.ecarx.carplay", request)
        val file = transfer.download(fetched, directory, request) {}
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(file), directory.listFiles()!!.toList())
    }
    @Test fun chunksAreConcatenatedAndEachHashIsChecked() {
        val chunks = listOf(bytes.copyOfRange(0, 3), bytes.copyOfRange(3, 5))
        val parts = JSONArray()
        chunks.forEachIndexed { index, chunk ->
            val path = "/downloads/com-ecarx-carplay/v147/p$index"
            serve(path, chunk)
            parts.put(JSONObject().put("index", index).put("url", path).put("size", chunk.size).put("sha256", hash(chunk)))
        }
        val data = release(manifest().put("distribution", "chunked").put("file", JSONObject.NULL).put("chunkSize", 3).put("parts", parts))
        var progress = 0L
        assertArrayEquals(bytes, GalaxyUpdateTransfer(base).download(data, directory, GalaxyUpdateRequest()) { progress = it }.readBytes())
        assertEquals(5L, progress)
    }
    @Test fun hashMismatchNeverLeavesAnInstallableFile() {
        serve("/downloads/com-ecarx-carplay/v147/update.apk", byteArrayOf(9, 9, 9, 9, 9))
        failure(GalaxyUpdateFailure.INTEGRITY) { GalaxyUpdateTransfer(base).download(release(), directory, GalaxyUpdateRequest()) {} }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun truncatedAndOversizedBodiesAreRejected() {
        serve("/downloads/com-ecarx-carplay/v147/update.apk", byteArrayOf(1, 2))
        failure(GalaxyUpdateFailure.INTEGRITY) { GalaxyUpdateTransfer(base).download(release(), directory, GalaxyUpdateRequest()) {} }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun cancellationDuringTransferCleansPartialFile() {
        serve("/downloads/com-ecarx-carplay/v147/update.apk", bytes)
        val request = GalaxyUpdateRequest()
        failure(GalaxyUpdateFailure.CANCELLED) {
            GalaxyUpdateTransfer(base).download(release(), directory, request) { request.cancel() }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun badStatusAndExcessiveMetadataAreBounded() {
        serve(base.path, "not found".toByteArray(), 404)
        failure(GalaxyUpdateFailure.NETWORK) { GalaxyUpdateTransfer(base).latest("com.ecarx.carplay", GalaxyUpdateRequest()) }
        server.removeContext(base.path)
        serve(base.path, ByteArray(GalaxyUpdateManifest.MAX_METADATA_SIZE + 1) { 32 })
        failure(GalaxyUpdateFailure.MANIFEST) { GalaxyUpdateTransfer(base).latest("com.ecarx.carplay", GalaxyUpdateRequest()) }
    }
    @Test fun crossHostRedirectIsRejectedWithoutFetchingIt() {
        server.createContext(base.path) { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/other")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        failure(GalaxyUpdateFailure.NETWORK) { GalaxyUpdateTransfer(base).latest("com.ecarx.carplay", GalaxyUpdateRequest()) }
    }
    @Test fun sameOriginRedirectWorks() {
        server.createContext(base.path) { exchange ->
            exchange.responseHeaders.add("Location", "/latest")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        val moved = manifest().also { it.getJSONObject("file").put("url", "/downloads/com-ecarx-carplay/v147/update.apk") }
        serve("/latest", moved.toString().toByteArray())
        assertEquals(147L, GalaxyUpdateTransfer(base).latest("com.ecarx.carplay", GalaxyUpdateRequest()).code)
    }
    private fun failure(expected: GalaxyUpdateFailure, work: () -> Unit) {
        try { work(); fail("failure was expected") }
        catch (error: GalaxyUpdateException) { assertEquals(expected, error.reason) }
    }
}
