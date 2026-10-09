package com.shilapi.xcertplay

import android.content.SharedPreferences

/** 配对、身份和恢复标记透传原存储，不能被配置切换清除或生成新身份。 */
internal class GalaxyScopedPreferences(private val name: String, private val local: SharedPreferences,
    private val protected: SharedPreferences, private val writeThrough: Boolean = false) : SharedPreferences by local {
    private fun source(key: String) = if (GalaxyConfigurationFields.allowed(name, key)) local else protected
    override fun contains(key: String) = source(key).contains(key)
    override fun getString(key: String, defValue: String?) = source(key).getString(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = source(key).getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = source(key).getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = source(key).getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = source(key).getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = source(key).getBoolean(key, defValue)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val current = if (writeThrough) protected.edit() else local.edit()
        val other = protected.edit()
        private fun target(key: String) = if (GalaxyConfigurationFields.allowed(name, key)) current else other
        override fun putString(key: String, value: String?) = apply { target(key).putString(key, value) }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { target(key).putStringSet(key, value) }
        override fun putInt(key: String, value: Int) = apply { target(key).putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { target(key).putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { target(key).putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { target(key).putBoolean(key, value) }
        override fun remove(key: String) = apply { target(key).remove(key) }
        override fun clear() = apply {
            if (writeThrough) protected.all.keys.filter { GalaxyConfigurationFields.allowed(name, it) }.forEach(current::remove)
            else current.clear()
        }
        override fun commit() = current.commit() && other.commit()
        override fun apply() { current.apply(); other.apply() }
    }
}
