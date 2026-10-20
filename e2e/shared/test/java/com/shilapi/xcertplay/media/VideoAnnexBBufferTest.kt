package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class VideoAnnexBBufferTest {
    private fun packet(vararg nals: ByteArray): ByteArray = ByteBuffer.allocate(nals.sumOf { it.size + 4 }).apply {
        nals.forEach { putInt(it.size); put(it) }
    }.array()

    @Test fun conversionMatchesDefaultForVariableSizeAvcHevcAndMalformedFrames() {
        val buffer = VideoAnnexBBuffer()
        val random = Random(76)
        val frames = List(300) {
            packet(*Array(1 + random.nextInt(5)) { random.nextBytes(2 + random.nextInt(9000)) })
        } + listOf(ByteArray(0), byteArrayOf(0, 0, 0), byteArrayOf(-1, -1, -1, -1),
            byteArrayOf(0, 0, 0, 8, 0x65), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, 0, 1, 0x65, 4), byteArrayOf(0, 0, 0, 1, 0x26, 1))
        frames.forEach { frame ->
            val expected = MediaCodecSupport.toAnnexB(frame)
            val size = buffer.convert(frame)
            assertArrayEquals(expected, buffer.bytes.copyOf(size))
            for (codec in VideoCodec.entries) assertEquals(
                MediaCodecSupport.isRandomAccess(expected, codec),
                MediaCodecSupport.isRandomAccess(buffer.bytes, codec, size))
            assertArrayEquals(expected, MediaCodecSupport.toAnnexB(frame))
        }
    }

    @Test fun shorterPredictedFrameDoesNotReuseKeyframeInStaleBufferTail() {
        val buffer = VideoAnnexBBuffer()
        val long = packet(ByteArray(80) { 0x41 }, byteArrayOf(0x65, 1))
        assertEquals(long.size, buffer.convert(long))
        val storage = buffer.bytes
        val short = packet(byteArrayOf(0x41, 1))
        val size = buffer.convert(short)
        assertSame(storage, buffer.bytes)
        assertFalse(VideoReferenceChain().accepts(buffer.bytes, VideoCodec.H264, size))
        assertArrayEquals(MediaCodecSupport.toAnnexB(short), buffer.bytes.copyOf(size))
        assertEquals(0, VideoAnnexBBuffer(32).convert(long))
    }
}
