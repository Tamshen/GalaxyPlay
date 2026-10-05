package com.shilapi.xcertplay.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AudioRoutingTemplateTest {
    private fun base() = JSONObject(AudioRoutingTemplate.system().toJson())
    private fun rejected(obj: JSONObject) = assertTrue(runCatching { AudioRoutingTemplate.parse(obj.toString()) }.isFailure)

    @Test fun focusOverridesRoundTripWithChoiceEditsAndLeaveOldTemplatesUnchanged() {
        assertNull(AudioRoutingTemplate.system().focusGain(AudioChannel.NAVIGATION))
        val obj = base().put("focusGains", JSONObject().put("navigation", 3).put("phone", 2))
        val template = AudioRoutingTemplate.parse(obj.toString()).withChoice(AudioOutputRole.MEDIA, 20)
        val restored = AudioRoutingTemplate.parse(template.toJson())
        assertEquals(3, restored.focusGain(AudioChannel.NAVIGATION))
        assertEquals(2, restored.focusGain(AudioChannel.PHONE))
        assertEquals(20, restored.choice(AudioOutputRole.MEDIA))
        assertFalse(AudioRoutingTemplate.system().toJson().contains("focusGains"))
    }

    @Test fun invalidFocusGainsAndPermanentCallFocusAreRejected() {
        for (value in listOf(0, 5, "3", 3.5, JSONObject.NULL))
            rejected(base().put("focusGains", JSONObject().put("navigation", value)))
        rejected(base().put("focusGains", JSONObject().put("unknown", 2)))
        rejected(base().put("focusGains", JSONObject().put("phone", 1)))
        rejected(base().put("focusGains", JSONObject.NULL))
    }

    @Test fun allTwentyStreamsAndFactoryStrategyRoundTripIndependently() {
        for (choice in listOf(0) + (1..20) + listOf(101, 102, 103)) {
            val old = AudioRoutingTemplate.system()
            val edited = old.withChoice(AudioOutputRole.NAVIGATION, choice)
            assertEquals(choice, AudioRoutingTemplate.parse(edited.toJson()).choice(AudioOutputRole.NAVIGATION))
            assertEquals(101, edited.choice(AudioOutputRole.MEDIA))
            assertEquals(103, old.choice(AudioOutputRole.NAVIGATION))
        }
    }

    @Test fun rejectsUnsupportedSchemaChoicesAndPhoneOverrides() {
        rejected(base().put("version", 2))
        rejected(base().put("deviceId", 17))
        rejected(base().apply { getJSONObject("choices").put("media", 21) })
        rejected(base().apply { getJSONObject("choices").put("navigation", "12") })
        rejected(base().apply { getJSONObject("choices").put("phone", 101) })
        rejected(base().put("preferBus", "true"))
        rejected(base().put("preferBus", true))
    }

    @Test fun rejectsPrivateAddressesAndInputGuesses() {
        for (address in listOf("01:23:45:67:89:ab", "../vendor/etc/policy.xml", "bus0;reboot", "", "bus" + "a".repeat(96)))
            rejected(base().apply { getJSONObject("outputBuses").put("media", address) })
        rejected(base().apply { getJSONObject("outputBuses").put("microphone", "bus20_input") })
        rejected(base().put("name", "x\nsecret"))
    }

    @Test fun sizeLimitAndTrailingPayloadAreRejected() {
        assertTrue(runCatching { AudioRoutingTemplate.parse(" ".repeat(AudioRoutingTemplate.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { AudioRoutingTemplate.parse(base().toString() + " {}") }.isFailure)
        assertTrue(runCatching { AudioRoutingTemplate.parse("[]") }.isFailure)
    }
}
