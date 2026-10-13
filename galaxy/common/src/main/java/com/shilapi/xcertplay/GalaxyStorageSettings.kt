package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 占用只读展示，页面可见时后台更新；离开后停止统计，不保留旧窗口回调。 */
internal object GalaxyStorageSettings {
    fun add(activity: Activity, parent: LinearLayout) {
        val main = Handler(Looper.getMainLooper())
        val rows = mutableListOf<L7SettingRow>()
        L7SettingsSection.add(parent, activity.getString(R.string.galaxy_storage_title),
            footer = activity.getString(R.string.galaxy_storage_policy)) { card ->
            for (title in listOf(R.string.galaxy_storage_total, R.string.galaxy_storage_logs,
                R.string.galaxy_storage_cache, R.string.galaxy_storage_other, R.string.galaxy_storage_memory)) {
                card.addView(L7SettingRow(activity, activity.getString(title)).also {
                    it.setValue(activity.getString(R.string.galaxy_storage_loading)); rows += it
                })
            }
        }
        var future: ScheduledFuture<*>? = null
        var generation = 0
        rows.first().addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val ticket = ++generation
                future = GalaxyStorageMaintenance.worker.scheduleWithFixedDelay({
                    val result = runCatching { GalaxyStorageUsage.read(activity.applicationContext) }
                    main.post {
                        if (!view.isAttachedToWindow || ticket != generation) return@post
                        result.onSuccess { usage ->
                            val values = listOf(usage.total, usage.logs, usage.cache, usage.other, usage.memory)
                            rows.zip(values).forEach { (row, bytes) -> row.setValue(Formatter.formatFileSize(activity, bytes)) }
                        }.onFailure { rows.forEach { it.setValue(activity.getString(R.string.galaxy_storage_unavailable)) } }
                    }
                }, 0, 5, TimeUnit.SECONDS)
            }
            override fun onViewDetachedFromWindow(view: View) { generation++; future?.cancel(false); future = null }
        })
    }
}
