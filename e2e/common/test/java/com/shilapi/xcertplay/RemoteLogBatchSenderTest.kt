package com.shilapi.xcertplay

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogBatchSenderTest {
    @Test fun sevenThousandLinesUseThreeRequestsAndConfirmOutOfOrderWithoutLoss() {
        val metadata = RemoteLogMetadata("parallel-test", "test", "test", "test")
        val batches = (0 until 7).map { batch ->
            RemoteLogBatch(metadata, batch + 1, 7, (0 until 1000).map { line ->
                RemoteLogEntry(batch * 1000 + line, "synthetic", "event=${batch * 1000 + line}")
            })
        }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val received = CopyOnWriteArrayList<Int>()
        val eventIds = CopyOnWriteArrayList<String>()
        val initial = CountDownLatch(3)
        val release = CountDownLatch(1)
        val thirdAcknowledged = CountDownLatch(1)
        val acknowledged = CopyOnWriteArrayList<Int>()
        val serverWorkers = Executors.newFixedThreadPool(6)
        val client = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverWorkers
        server.createContext("/") { exchange ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            var processing = true
            try {
                val bytes = exchange.requestBody.readBytes()
                assertTrue(bytes.size <= RemoteLogReport.MAX_BODY)
                val data = JSONArray(bytes.toString(Charsets.UTF_8))
                assertEquals(1000, data.length())
                val index = data.getJSONObject(0).getInt("batch_index")
                received += index
                for (line in 0 until data.length()) eventIds += data.getJSONObject(line).getString("event_id")
                if (index <= 3) {
                    initial.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    if (index != 3) check(thirdAcknowledged.await(5, TimeUnit.SECONDS))
                }
                val response = JSONObject().put("code", 200).put("status", JSONArray().put(JSONObject()
                    .put("name", "test").put("successful", data.length()).put("failed", 0))).toString().toByteArray()
                // 客户端读到确认即可派下一批，服务端 close 收尾不计为新一批仍在处理。
                active.decrementAndGet(); processing = false
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } finally { if (processing) active.decrementAndGet() }
        }
        server.start()
        val config = RemoteLogConfig("http://127.0.0.1:${server.address.port}/api/default/test/_json", "Basic dGVzdDpwYXNz")
        RemoteLogBatchSender().use { sender ->
            try {
                val result = client.submit<Int> {
                    sender.send(config, batches, BooleanArray(7), {}) { index ->
                        acknowledged += index
                        if (index == 2) thirdAcknowledged.countDown()
                    }
                }
                assertTrue("三个请求必须同时在途，顺序上传无法通过此门闩", initial.await(5, TimeUnit.SECONDS))
                assertEquals(3, received.size)
                release.countDown()
                assertEquals(200, result.get(10, TimeUnit.SECONDS).toInt())
                assertEquals(3, peak.get())
                assertEquals(2, acknowledged.first())
                assertEquals((0 until 7).toList(), acknowledged.sorted())
                assertEquals((1..7).toList(), received.sorted())
                assertEquals(7000, eventIds.size)
                assertEquals(7000, eventIds.toSet().size)
            } finally {
                release.countDown(); thirdAcknowledged.countDown()
                client.shutdownNow(); server.stop(0); serverWorkers.shutdownNow()
            }
        }
    }
}
