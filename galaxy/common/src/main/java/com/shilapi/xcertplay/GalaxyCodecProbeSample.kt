package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** 固定合成样例与真实手机载荷分开；输入数量和字节数均有上限。 */
internal data class GalaxyCodecProbeSample(val format: MediaFormat, val packets: Array<ByteArray>,
    val times: LongArray, val csd: Array<ByteArray>) {
    companion object {
        fun read(context: Context, video: CodecProbeVideo): GalaxyCodecProbeSample {
            val extractor = MediaExtractor()
            try {
                context.assets.openFd(video.asset).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == video.mime }
                val format = extractor.getTrackFormat(track)
                check(format.getInteger(MediaFormat.KEY_WIDTH) == 640 && format.getInteger(MediaFormat.KEY_HEIGHT) == 360)
                extractor.selectTrack(track)
                val csd = (0..2).mapNotNull { i -> format.getByteBuffer("csd-$i")?.duplicate()?.let {
                    ByteArray(it.remaining()).apply { it.get(this) }
                } }.toTypedArray()
                val buffer = ByteBuffer.allocate(512 * 1024)
                val packets = mutableListOf<ByteArray>()
                val times = mutableListOf<Long>()
                var total = 0
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    check(size in 1..buffer.capacity() && packets.size < 120 && total + size <= 4 * 1024 * 1024)
                    check(extractor.sampleTime in 0..3_000_000)
                    packets += ByteArray(size).apply { buffer.get(this) }
                    times += extractor.sampleTime
                    total += size
                    if (!extractor.advance()) break
                }
                check(packets.size == 60 && csd.isNotEmpty())
                return GalaxyCodecProbeSample(format, packets.toTypedArray(), times.toLongArray(), csd)
            } finally { extractor.release() }
        }
    }
}
