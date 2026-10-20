package com.shilapi.xcertplay.media

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 两块各 20ms 的 PCM（含正在写的一块），满时背压，不丢半包、不调速。
 * 解码线程先释放 codec 输出，再提交副本；播放器独占写入和水位维护。
 */
class MusicPcmWriter(
    sampleRate: Int,
    frameBytes: Int,
    private val write: (ByteArray, Int, Int) -> Unit,
    private val maintain: () -> Unit,
    private val failed: (Throwable) -> Unit,
    private val initialize: () -> Unit = {},
) {
    private class Block(size: Int) { val bytes = ByteArray(size); var length = 0 }
    private val blockBytes = (sampleRate / 50).coerceAtLeast(1) * frameBytes
    private val free = ArrayBlockingQueue<Block>(2).apply { repeat(2) { add(Block(blockBytes)) } }
    private val ready = ArrayBlockingQueue<Block>(2)
    @Volatile private var active = true
    private val worker = Thread(::run, "galaxy-music-pcm").apply { isDaemon = true }

    fun start() = worker.start()
    fun hasPending(): Boolean = free.size < 2

    fun submit(data: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        while (active && cursor < end) {
            val block = free.poll(10, TimeUnit.MILLISECONDS) ?: continue
            block.length = minOf(blockBytes, end - cursor)
            data.copyInto(block.bytes, 0, cursor, cursor + block.length)
            cursor += block.length
            if (!active) { free.offer(block); return }
            check(ready.offer(block))
        }
    }

    private fun run() {
        try {
            initialize()
            while (active) {
                val block = ready.poll(10, TimeUnit.MILLISECONDS)
                if (block != null) {
                    try { if (active) write(block.bytes, 0, block.length) }
                    finally { free.offer(block) }
                }
                if (active) maintain()
            }
        } catch (_: InterruptedException) {
            // 停止唤醒写入等待与队列等待，资源仍由宿主 worker 在 join 后释放。
        } catch (error: Throwable) {
            if (active) failed(error)
        } finally {
            active = false
            ready.clear()
        }
    }

    fun stop() { active = false; worker.interrupt() }

    /** 只能由音频后台 worker 调用；完成前不得释放它正在使用的 AudioTrack。 */
    fun stopAndJoin() {
        stop()
        var interrupted = false
        while (worker.isAlive) {
            try { worker.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
