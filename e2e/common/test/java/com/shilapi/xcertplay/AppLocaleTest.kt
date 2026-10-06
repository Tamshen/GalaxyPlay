package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Test fun pickerAndSystemSettingsShareTheSamePreference() {
        AppLocale.save(context, AppLocale.ENGLISH)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertSame(context, AppLocale.wrap(context))
        AppLocale.save(context, AppLocale.SYSTEM)
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun oldPreferenceMigratesOnceAndCannotOverrideLaterSystemChanges() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "en").commit()
        AppLocale.wrap(context)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.wrap(context)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun existingSystemChoiceWinsOverLegacyPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "en").commit()
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        AppLocale.wrap(context)
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
    }

    @Test @Config(sdk = [29, 32])
    fun olderAndroidWrapsEnglishAndReturnsToSystemWithoutChangingGlobalResources() {
        val original = context.resources.configuration.locales.toLanguageTags()
        AppLocale.save(context, AppLocale.ENGLISH)
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale.ENGLISH, wrapped.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_LTR, wrapped.resources.configuration.layoutDirection)
        assertEquals(original, context.resources.configuration.locales.toLanguageTags())
        AppLocale.save(context, AppLocale.SYSTEM)
        assertSame(context, AppLocale.wrap(context))
    }

    @Test @Config(sdk = [29])
    fun removedLegacyLanguagesFallBackToSystem() {
        for (language in listOf("ar", "ru", "es")) {
            context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
                .putString("app_language", language).commit()
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
            assertSame(context, AppLocale.wrap(context))
        }
    }

    @Test fun removedPlatformLanguagesDoNotRestoreOlderPreferences() {
        for (language in listOf("ar", "ru", "es")) {
            context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
                .putString("app_language", "en")
                .putBoolean("app_language_platform_migrated", false).commit()
            manager.applicationLocales = LocaleList.forLanguageTags(language)
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
            AppLocale.wrap(context)
            assertTrue(manager.applicationLocales.isEmpty)
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
        }
    }

    @Test @Config(sdk = [29])
    fun chineseServiceContextFollowsSystemDayNightChanges() {
        RuntimeEnvironment.setQualifiers("notnight")
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        val wrapped = AppLocale.wrap(context)
        assertEquals("zh", wrapped.resources.configuration.locales[0].language)
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
            wrapped.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

        // 同一个长期持有的 Context 必须随系统切换，不能靠重启服务取得新颜色。
        RuntimeEnvironment.setQualifiers("night")
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            wrapped.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("zh", wrapped.resources.configuration.locales[0].language)

        RuntimeEnvironment.setQualifiers("notnight")
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
            wrapped.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("zh", wrapped.resources.configuration.locales[0].language)
    }
}
