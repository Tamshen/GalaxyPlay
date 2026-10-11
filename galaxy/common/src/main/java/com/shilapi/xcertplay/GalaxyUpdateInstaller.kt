package com.shilapi.xcertplay

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import java.io.File

/** 授权后回到应用由用户再次安装；系统安装结果不等于新版本已经运行。 */
internal class GalaxyUpdateInstaller(
    private val activity: ComponentActivity,
    private val status: (Int) -> Unit,
    private val occupied: () -> Boolean = { CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.isStopping() },
    registry: ActivityResultRegistry = activity.activityResultRegistry,
) {
    private val permission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), registry) {
        status(if (activity.packageManager.canRequestPackageInstalls()) R.string.update_permission_ready else R.string.update_permission_denied)
    }
    private val installer = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), registry) {
        status(R.string.update_installer_returned)
    }

    fun install(file: File) {
        if (occupied()) { status(R.string.update_end_connection); return }
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                L7Dialogs.builder(activity).setTitle(R.string.update_install_permission)
                    .setMessage(R.string.update_permission_hint)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.update_open_permission) { _, _ ->
                        runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))) }
                            .onFailure { status(R.string.update_permission_unavailable) }
                    }.show()
                return
            }
            installer.launch(intent(activity, file))
        } catch (_: Exception) { status(R.string.update_installer_unavailable) }
    }

    companion object {
        @Suppress("DEPRECATION")
        internal fun intent(activity: ComponentActivity, file: File): Intent {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.app-updates", file)
            return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("GalaxyPlay update", uri)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
        }
    }
}
