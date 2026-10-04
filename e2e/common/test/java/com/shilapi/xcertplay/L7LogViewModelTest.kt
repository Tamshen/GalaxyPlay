package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7LogViewModelTest {
    @Test fun literalSearchCopiesOnlyMatchesAndRefreshKeepsTheQuery() {
        val model = L7LogViewModel()
        model.replace(listOf("HEVC error [12]", "audio route BUS00_MEDIA", "hevc frame ready"))
        model.search("hevc")
        assertEquals(2, model.matches.size)
        assertEquals("HEVC error [12]\nhevc frame ready", model.copyParts(true).single())
        assertTrue(model.copyParts(false).single().contains("BUS00_MEDIA"))
        model.search("[12]")
        assertEquals(listOf("HEVC error [12]"), model.matches)
        model.replace(listOf("error [12] latest", "other"))
        assertEquals(listOf("error [12] latest"), model.matches)
        model.search("not found")
        assertTrue(model.copyParts(true).isEmpty())
    }

    @Test fun clipboardPartsPreserveEveryCharacterAndSurrogatePair() {
        val original = "x".repeat(179999) + "🚗" + "解码".repeat(130000)
        val model = L7LogViewModel()
        model.replace(listOf(original))
        val parts = model.copyParts(false)
        assertTrue(parts.size > 1)
        assertEquals(original, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 180000 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
