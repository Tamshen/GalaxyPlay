package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** 首次识别先确认再应用；已审阅的识别结果不反复弹出，手动复查可重新确认。 */
internal class L7AudioModelConfirmation(
    private val activity: Activity,
    private val detect: () -> L7AudioTemplates.Model? = { L7AudioModelDetector.detect() },
) : Closeable {
    private var dialog: AlertDialog? = null
    private var continuation: (() -> Unit)? = null
    private var closed = false

    fun ensure(after: () -> Unit): Boolean {
        if (closed || activity.isFinishing || activity.isDestroyed) return false
        if (dialog?.isShowing == true) { continuation = after; return true }
        val model = detect() ?: return false
        if (preferences(activity).getString(REVIEWED, null) == model.id) return false
        continuation = after
        dialog = confirm(activity, model, handled = {
            dialog = null
            val next = continuation
            continuation = null
            if (!closed && !activity.isFinishing && !activity.isDestroyed) next?.invoke()
        }, current = { !closed })
        return true
    }

    override fun close() {
        closed = true
        continuation = null
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        private const val REVIEWED = "detected_model_reviewed"
        private fun preferences(context: Context) = context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE)

        fun name(context: Context, model: L7AudioTemplates.Model): String = context.getString(
            if (model == L7AudioTemplates.Model.L6) R.string.l7_template_model_l6 else R.string.l7_template_model_l7)

        fun markReviewed(context: Context, model: L7AudioTemplates.Model? = L7AudioModelDetector.detect()) {
            model?.let { check(preferences(context).edit().putString(REVIEWED, it.id).commit()) }
        }

        fun confirm(context: Context, model: L7AudioTemplates.Model, handled: () -> Unit,
                    current: () -> Boolean = { true }): AlertDialog {
            L7DebugLog.record("Audio: modelDetection result=RECOGNIZED model=${model.id} applied=false")
            fun usable() = current() && !(context is Activity && (context.isFinishing || context.isDestroyed))
            fun retain() {
                if (!usable()) return
                runCatching { markReviewed(context, model) }
                L7DebugLog.record("Audio: modelDetection result=RETAINED model=${L7AudioTemplates.model(context).id}")
                handled()
            }
            return L7Dialogs.builder(context).setTitle(R.string.l7_template_detect_title)
                .setMessage(context.getString(R.string.l7_template_detect_confirm, name(context, model)))
                .setNegativeButton(R.string.l7_template_detect_retain) { _, _ -> retain() }
                .setOnCancelListener { retain() }
                .setPositiveButton(R.string.l7_save_next_connection) { _, _ ->
                    if (!usable()) return@setPositiveButton
                    if (runCatching { L7AudioTemplates.selectModel(context, model) }.isSuccess) {
                        runCatching { markReviewed(context, model) }
                        L7DebugLog.record("Audio: modelDetection result=CONFIRMED model=${model.id} applies=NEXT_CONNECTION")
                        handled()
                    } else Toast.makeText(context, R.string.l7_template_save_failed, Toast.LENGTH_LONG).show()
                }.show()
        }
    }
}
