package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.concentus.OpusApplication
import org.concentus.OpusEncoder as ConcentusEncoder

class SoftwareOpusCodecTest {
    private fun signal(channels: Int = 1): ByteArray = ByteArray(960 * channels * 2).also { pcm ->
        repeat(960 * channels) { i ->
            val value = (kotlin.math.sin(i.toDouble() / channels * 0.08) * 8_000).toInt()
            pcm[i * 2] = value.toByte()
            pcm[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    @Test fun monoRoundTripProducesTwentyMillisecondsOfNonSilentPcm() {
        SoftwareOpusEncoder(24_000).use { encoder ->
            SoftwareOpusDecoder(48_000, 1).use { decoder ->
                val packets = encoder.encode(signal())
                assertEquals(1, packets.size)
                assertEquals(1_920, decoder.decode(packets.single()))
                assertTrue(decoder.pcm.take(1_920).any { it != 0.toByte() })
            }
        }
    }

    @Test fun stereoDecoderUsesInterleavedLittleEndianPcm() {
        val encoder = ConcentusEncoder(48_000, 2, OpusApplication.OPUS_APPLICATION_AUDIO)
        val pcm = signal(2)
        val samples = ShortArray(1_920) { i ->
            ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }
        val packet = ByteArray(1_275)
        val size = encoder.encode(samples, 0, 960, packet, 0, packet.size)
        SoftwareOpusDecoder(48_000, 2).use { decoder ->
            assertEquals(3_840, decoder.decode(packet.copyOf(size)))
            assertTrue(decoder.pcm.take(3_840).any { it != 0.toByte() })
        }
    }

    @Test fun threeByteSilencePacketIsValidAudio() {
        SoftwareOpusDecoder(48_000, 1).use { decoder ->
            assertEquals(1_920, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())))
        }
    }

    @Test fun invalidPacketIsRejectedAndNextValidPacketStillDecodes() {
        SoftwareOpusDecoder(48_000, 1).use { decoder ->
            assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(7_651)) }
            assertThrows(Exception::class.java) { decoder.decode(byteArrayOf(3)) }
            assertEquals(1_920, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())))
        }
    }

    @Test fun encoderRejectsPartialFrameAndClearsResourcesIdempotently() {
        val encoder = SoftwareOpusEncoder(24_000)
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(ByteArray(1_919)) }
        assertEquals(1, encoder.encode(signal()).size)
        encoder.close(); encoder.close()
        assertFalse(encoder.available)
        assertTrue(encoder.encode(signal()).isEmpty())
        val decoder = SoftwareOpusDecoder(48_000, 1)
        decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
        decoder.close(); decoder.close()
        assertTrue(decoder.pcm.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { decoder.decode(byteArrayOf(0)) }
    }
}
