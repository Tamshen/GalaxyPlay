package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogDeviceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("l7_log_device", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun scopedIdentifierDoesNotRequireSerialPermissionOrPersistRawValues() {
        var serialRead = false
        val id = RemoteLogDevice.id(context, serial = { serialRead = true; throw SecurityException() },
            androidId = { "synthetic-android-id" })
        assertFalse(serialRead)
        assertEquals(RemoteLogDevice.derive("android_id", "synthetic-android-id"), id)
        assertTrue(id.matches(Regex("l7_[a-f0-9]{5}(?:_[a-f0-9]{5}){3}")))
        assertEquals(mapOf("id" to id), prefs.all)
    }

    @Test fun serialFallbackIsHashedAndNeverStoredRaw() {
        val id = RemoteLogDevice.id(context, serial = { "synthetic-serial" }, androidId = { "unknown" })
        assertEquals(RemoteLogDevice.derive("serial", "synthetic-serial"), id)
        assertFalse(prefs.all.toString().contains("synthetic-serial"))
    }

    @Test fun unavailableIdentifiersProducePersistentInstallationFallback() {
        val id = RemoteLogDevice.id(context, serial = { throw SecurityException() }, androidId = { null })
        assertEquals(id, RemoteLogDevice.id(context, serial = { error("不应重复读取") }, androidId = { error("不应重复读取") }))
        prefs.edit().clear().commit()
        assertNotEquals(id, RemoteLogDevice.id(context, serial = { "unknown" }, androidId = { "00000000" }))
    }

    @Test fun sameDeviceCanRegenerateItsIdAndDifferentDevicesStaySeparate() {
        val first = RemoteLogDevice.id(context, serial = { null }, androidId = { "synthetic-a" })
        prefs.edit().clear().commit()
        assertEquals(first, RemoteLogDevice.id(context, serial = { null }, androidId = { "synthetic-a" }))
        prefs.edit().clear().commit()
        assertNotEquals(first, RemoteLogDevice.id(context, serial = { null }, androidId = { "synthetic-b" }))
    }

    @Test fun resettingServerConfigurationKeepsTheDeviceId() {
        val id = RemoteLogDevice.id(context, serial = { null }, androidId = { "synthetic-device" })
        RemoteLogConfig.reset(context)
        assertEquals(id, RemoteLogDevice.id(context))
    }

    @Test fun emptyReportsDoNotRepeatDeviceIdentity() {
        val record = JSONArray(RemoteLogReport.create(emptyList(), "test", "test").body.toString(Charsets.UTF_8)).getJSONObject(0)
        assertFalse(record.has("device_id"))
    }

    @Test fun legacyDeviceNamesKeepTheirHashWhenConvertedToStreamNames() {
        prefs.edit().putString("id", "L7-ABCDE-12345-ABCDE-67890").commit()
        val id = RemoteLogDevice.id(context, serial = { error("不应重建编号") }, androidId = { error("不应重建编号") })
        assertEquals("l7_abcde_12345_abcde_67890", id)
        assertEquals(id, prefs.getString("id", null))
    }
}
