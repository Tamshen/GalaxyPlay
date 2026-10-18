package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.widget.EditText
import com.shilapi.xcertplay.host.R

/** 确认备注后才启动采集和上传，取消不会创建报告或发起请求。 */
internal object L7LogUploadPrompt {
    fun show(context: Context, confirmed: (String) -> Unit, dismissed: () -> Unit): AlertDialog {
        val input = EditText(context).apply {
            L7Ui.input(this)
            hint = context.getString(R.string.l7_log_upload_remark)
            filters = arrayOf(InputFilter.LengthFilter(RemoteLogUploadRequest.MAX_REMARK))
            minLines = 2; maxLines = 3
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = L7Dialogs.builder(context).setTitle(R.string.l7_log_upload)
            .setMessage(R.string.l7_log_upload_remark_hint).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.l7_log_start_upload, null).create()
        var applied = false
        dialog.setOnDismissListener { dismissed() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (applied || !dialog.isShowing) return@setOnClickListener
                applied = true
                val remark = input.text.toString()
                dialog.dismiss()
                confirmed(remark)
            }
        }
        dialog.show()
        return dialog
    }
}
