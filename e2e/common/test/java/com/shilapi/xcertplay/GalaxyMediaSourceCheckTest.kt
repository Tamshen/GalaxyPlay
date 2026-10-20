package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import ecarx.xsf.mediacenter.session.AppSourceInfo
import ecarx.xsf.mediacenter.session.EasMediaController
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyMediaSourceCheckTest {
    class Api(private val controller: EasMediaController) {
        fun getEasMediaController() = controller
    }

    @Test fun configurationSuccessDoesNotPretendRuntimeOrUiSuccessAndRepairsOnce() {
        val app = RuntimeEnvironment.getApplication()
        val controller = EasMediaController()
        val other = AppSourceInfo.Builder().packageName("synthetic.other").sourceTypeList(intArrayOf(6)).build()
        controller.configuration.add(other)
        val api = Api(controller)
        val redeclared = AtomicInteger()
        val results = LinkedBlockingQueue<Map<String, Long>>()
        val check = GalaxyMediaSourceCheck(app, api, Api::class.java, 6, { true },
            { redeclared.incrementAndGet() }, { if ("runtimeSourcePresent" in it) results.offer(it) }, {})
        try {
            check.start()
            val result = results.poll(3, TimeUnit.SECONDS)!!
            assertEquals(1L, result["catalogPresent"])
            assertEquals(0L, result["runtimeSourcePresent"])
            assertEquals(-1L, result["uiSourcePresent"])
            assertEquals(1, redeclared.get())
            assertEquals(1, controller.writes)
            assertEquals(other, controller.configuration.first())
            results.poll(2, TimeUnit.SECONDS)!!
            assertEquals(1, controller.writes)
            assertEquals(1, redeclared.get())
        } finally { check.close() }
    }
    @Test fun uiEvidenceDistinguishesPresentMissingAndUnavailable() {
        assertEquals(1L, GalaxyMediaSourceCheck.uiContains("""[{"packageName":"own"}]""", "own"))
        assertEquals(0L, GalaxyMediaSourceCheck.uiContains("""[{"packageName":"other"}]""", "own"))
        assertEquals(0L, GalaxyMediaSourceCheck.uiContains("[]", "own"))
        for (input in listOf(null, "", "{}", "[{}]"))
            assertEquals(-1L, GalaxyMediaSourceCheck.uiContains(input, "own"))
    }
}
