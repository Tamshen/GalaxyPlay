package com.shilapi.xcertplay

import android.content.Intent
import androidx.activity.ComponentActivity

/** 只创建覆盖控件；协议、控制器和 Surface 的所有权仍留在核心宿主。 */
internal class GalaxyHostChrome(private val activity: ComponentActivity) {
    private val context get() = L7UiDensity.wrap(activity)

    fun waiting(wireless: Boolean, home: () -> Unit, cancel: () -> Unit, recovery: () -> Unit,
        logs: () -> Unit = home, diagnostic: (String) -> Unit = {}) =
        L7ConnectionPanel(context, wireless, onReturn = home, onCancel = cancel, onRecovery = recovery,
            onLogs = logs, onDiagnostic = diagnostic)

    fun navigation(releaseTouches: () -> Unit, settings: (String) -> Unit) =
        L7ProjectionNavigation(context, releaseTouches, projection = true, returnHome = { settings("home") }) { destination ->
            when (destination) {
                "home" -> Unit
                "connection" -> GalaxyConnectionMenu.select(activity) { settings("settings-connection") }
                "exit" -> L7AppExit.confirm(activity)
                "car-home" -> activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                else -> settings(destination)
            }
        }

    fun videoRecovery(retry: () -> Boolean, settings: () -> Unit) =
        L7VideoRecoveryPanel(context, retry, settings)
}
