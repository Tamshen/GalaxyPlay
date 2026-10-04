package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class RemoteLogEditorTest {
    private val builtIn = RemoteLogConfig("https://built-in.example/api/test/{DeviceID}/_json", "Basic dGVzdDpwYXNzd29yZA==")

    @Test fun builtInAddressNeverEntersDisplayOrInputText() {
        val editor = RemoteLogEditor(builtIn, builtIn.endpoint)
        assertTrue(editor.masksEndpoint)
        assertEquals("", editor.endpointText)
        assertEquals("*****", editor.serverText("https://built-in.example/api/test/test-device/_json"))
        assertTrue(editor.hasAuthorization)
    }

    @Test fun leavingMaskedFieldsBlankRetainsRealConfiguration() {
        assertEquals(builtIn, RemoteLogEditor(builtIn, builtIn.endpoint).resolve("", ""))
    }

    @Test fun newAddressDoesNotReuseHiddenAuthorization() {
        val next = RemoteLogEditor(builtIn, builtIn.endpoint).resolve("https://custom.example/api/test/123/_json", "")
        assertEquals("", next.authorization)
        assertFalse(next.valid())
    }

    @Test fun explicitCustomConfigurationReplacesBothFields() {
        val custom = RemoteLogConfig("https://custom.example/api/test/123/_json", "Basic Y3VzdG9tOnRlc3Q=")
        assertEquals(custom, RemoteLogEditor(builtIn, builtIn.endpoint).resolve(custom.endpoint, custom.authorization))
        val editor = RemoteLogEditor(custom, builtIn.endpoint)
        assertFalse(editor.masksEndpoint)
        assertEquals(custom.endpoint, editor.endpointText)
        assertEquals(custom.endpoint, editor.serverText(custom.endpoint))
        assertEquals(custom, editor.resolve(custom.endpoint, ""))
    }

    @Test fun changingOnlyAuthorizationKeepsMaskedAddress() {
        val next = RemoteLogEditor(builtIn, builtIn.endpoint).resolve("", "Basic Y3VzdG9tOnRlc3Q=")
        assertEquals(builtIn.endpoint, next.endpoint)
        assertNotEquals(builtIn.authorization, next.authorization)
        assertTrue(next.valid())
    }

    @Test fun blankBuildDefaultsRemainUnconfigured() {
        val editor = RemoteLogEditor(RemoteLogConfig("", ""), "")
        assertFalse(editor.masksEndpoint)
        assertFalse(editor.hasAuthorization)
        assertEquals("not configured", editor.serverText("not configured"))
        assertFalse(editor.resolve("", "").valid())
    }

    @Test fun literalMaskCannotBecomeAValidServer() {
        assertFalse(RemoteLogEditor(builtIn, builtIn.endpoint).resolve("*****", "*****").valid())
    }
}
