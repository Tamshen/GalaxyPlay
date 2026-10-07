package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyCodecProbeRecipeTest {
    private fun sample() = GalaxyCodecProbeSample(MediaFormat.createVideoFormat("video/avc", 640, 360).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(1, 2)))
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    }, arrayOf(byteArrayOf(3)), longArrayOf(33000), arrayOf(byteArrayOf(1, 2)))

    @Test fun inBandConfigurationDoesNotKeepExtractorCsdAndUcarUsesItsOwnClock() {
        val sample = sample()
        val recipe = GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_UCAR)
        val format = recipe.format(sample)
        assertFalse(format.containsKey("csd-0"))
        assertFalse(format.containsKey(MediaFormat.KEY_FRAME_RATE))
        assertTrue(sample.format.containsKey("csd-0"))
        assertTrue(recipe.async); assertTrue(recipe.byType); assertTrue(recipe.inBandCsd)
        assertEquals(15L, recipe.presentationTime(3, sample))
        assertFalse(recipe.isQti("c2.android.avc.decoder"))
        assertTrue(recipe.isQti("c2.qti.avc.decoder"))
        assertTrue(recipe.isQti("OMX.qcom.video.decoder.avc"))
    }
    @Test fun bufferRecipeHasNoSurfaceAndRetainsFormatCsdWithoutMutatingTheSample() {
        val sample = sample()
        val recipe = GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_DMSDP_BUFFER)
        val format = recipe.format(sample)
        assertFalse(recipe.method.surfaceOutput)
        assertTrue(recipe.avcOnly); assertFalse(recipe.inBandCsd)
        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
        assertEquals(1536000, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        assertEquals(2, format.getInteger("low-latency"))
        assertTrue(format.containsKey("csd-0"))
        assertFalse(sample.format.containsKey("low-latency"))
    }
    @Test fun hdmiKeepsItsConfiguredRateAndUnsupportedVendorRequestsAreIsolated() {
        val hdmi = GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_DMSDP_HDMI).format(sample())
        assertEquals(33, hdmi.getInteger(MediaFormat.KEY_FRAME_RATE))
        assertFalse(hdmi.containsKey("low-latency"))
        val hiSight = GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_HISIGHT).format(sample())
        assertEquals(0, hiSight.getInteger(MediaFormat.KEY_PRIORITY))
        assertFalse(hiSight.containsKey("csd-0"))
        assertTrue(GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_HISIGHT).immediate)
        assertFalse(hiSight.containsKey("vendor.qti-ext-dec-low-latency.enable"))
        val qti = GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_UCAR_QTI).format(sample())
        assertEquals(1, qti.getInteger("vendor.qti-ext-dec-picture-order.enable"))
        assertEquals(1000L, GalaxyCodecProbeRecipe(CodecProbeMethod.OEM_LIBPAG).waitUs)
    }
}
