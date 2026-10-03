package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class L7RoutesTest {
    @Test fun everyConfigurationPageBelongsToSettingsAndReturnsToItsRoot() {
        L7Routes.settings.filter { it != "settings" }.forEach {
            assertEquals("settings", L7Routes.navigation(it))
            assertEquals("settings", L7Routes.back(it))
            assertEquals(it, L7Routes.normalize(it))
        }
    }

    @Test fun legacyEntriesOpenSettingsChildrenAndUnknownDestinationsAreRejected() {
        listOf("connection", "diagnostics", "about").forEach {
            assertEquals("settings-$it", L7Routes.normalize(it))
            assertEquals("settings", L7Routes.navigation(it))
            assertEquals("settings", L7Routes.back(it))
        }
        assertEquals("home", L7Routes.normalize("settings-missing"))
    }

    @Test fun oldDebugEntryAndRestoredPageResolveToCombinedDiagnostics() {
        assertEquals("settings-diagnostics", L7Routes.normalize("settings-debug"))
        assertEquals("settings", L7Routes.navigation("settings-debug"))
        assertEquals("settings", L7Routes.back("settings-debug"))
        assertEquals("settings-diagnostics", L7Routes.destination("home", "settings", "settings-debug"))
    }

    @Test fun tappingSettingsInsideItsChildReturnsToCategoriesAndOutsideRestoresChild() {
        assertEquals("settings", L7Routes.destination("settings-display", "settings", "settings-display"))
        assertEquals("settings-audio", L7Routes.destination("home", "settings", "settings-audio"))
        assertEquals("settings", L7Routes.destination("home", "settings", "missing"))
    }
}
