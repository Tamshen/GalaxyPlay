package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class L7RoutesTest {
    @Test fun everyConfigurationPageBelongsToSettingsAndReturnsToItsRoot() {
        L7Routes.settings.filter { it != "settings" && !L7Routes.isDebug(it) }.forEach {
            assertEquals("settings", L7Routes.navigation(it))
            assertEquals("settings", L7Routes.back(it))
            assertEquals(it, L7Routes.normalize(it))
        }
    }

    @Test fun legacyEntriesOpenSettingsChildrenAndUnknownDestinationsAreRejected() {
        listOf("connection", "about").forEach {
            assertEquals("settings-$it", L7Routes.normalize(it))
            assertEquals("settings", L7Routes.navigation(it))
            assertEquals("settings", L7Routes.back(it))
        }
        assertEquals("home", L7Routes.normalize("settings-missing"))
    }

    @Test fun oldDiagnosticsAndRestoredPageResolveToAboutDebug() {
        assertEquals("settings-debug", L7Routes.normalize("settings-diagnostics"))
        assertEquals("settings-debug", L7Routes.normalize("diagnostics"))
        assertEquals("settings", L7Routes.navigation("settings-debug"))
        assertEquals("settings-about", L7Routes.back("settings-debug"))
        assertEquals("settings-debug", L7Routes.destination("home", "settings", "settings-diagnostics"))
        listOf("settings-debug-results", "settings-debug-history", "settings-debug-logs").forEach {
            assertEquals("settings-debug", L7Routes.back(it))
            assertEquals("settings", L7Routes.navigation(it))
        }
    }

    @Test fun tappingSettingsInsideItsChildReturnsToCategoriesAndOutsideRestoresChild() {
        assertEquals("settings", L7Routes.destination("settings-display", "settings", "settings-display"))
        assertEquals("settings-audio", L7Routes.destination("home", "settings", "settings-audio"))
        assertEquals("settings", L7Routes.destination("home", "settings", "missing"))
    }
}
