package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarPlayVpnService

/** 未确认时独占任务入口；从设置查看时仅展示协议，不打断已有连接。 */
class L7AgreementActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var reviewing = false
    private var panel: L7AgreementPanel? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(L7UiDensity.wrap(AppLocale.wrap(newBase)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (L7AppExit.exiting) { finish(); return }
        reviewing = L7Agreement.accepted(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        if (!reviewing) stopUsage()
    }

    private fun render() {
        val document = runCatching { L7Agreement.displayDocument(this) }.getOrNull()
        if (document == null) {
            setContentView(L7Components.actionButton(this, getString(R.string.l7_agreement_load_failed)) { leave() })
            return
        }
        panel = L7AgreementPanel(this, document, reviewing, ::accept, ::leave, ::confirmRevoke)
        setContentView(panel)
        panel?.setReady(reviewing)
        window.statusBarColor = getColor(R.color.product_ui_background)
        window.navigationBarColor = getColor(R.color.product_ui_background)
    }

    override fun onResume() {
        super.onResume()
        panel?.setReadingActive(true)
    }

    override fun onPause() {
        panel?.setReadingActive(false)
        super.onPause()
    }

    private fun accept() {
        if (!L7Agreement.accept(this)) {
            Toast.makeText(this, R.string.l7_agreement_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        // 确认后进入首页，由用户选择连接；不恢复确认之前的 USB 或自动连接请求。
        startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "home")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun leave() {
        if (reviewing) finish() else L7AppExit.exit(applicationContext)
    }

    private fun confirmRevoke() {
        L7Dialogs.builder(this).setTitle(R.string.l7_agreement_revoke)
            .setMessage(R.string.l7_agreement_revoke_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.l7_agreement_revoke_confirm) { _, _ ->
                if (!L7Agreement.revoke(this)) {
                    // 内存闸门仍关闭；持久化失败明确提示，不能宣称撤回已保存。
                    Toast.makeText(this, R.string.l7_agreement_save_failed, Toast.LENGTH_LONG).show()
                }
                L7Agreement.showGate(this)
            }.show()
    }

    private fun stopUsage() {
        val context = applicationContext
        L7DesktopNavigation.stop(context)
        L7DebugOverlayService.stop(context)
        // 控制器释放有界；若清理卡住则结束应用，不能在旧连接仍运行时重新同意。
        val timeout = Runnable { L7AppExit.exit(context) }
        handler.postDelayed(timeout, 6_000)
        CarPlayBackgroundSession.stop {
            handler.post {
                handler.removeCallbacks(timeout)
                context.stopService(Intent(context, DiPlaySessionService::class.java))
                context.stopService(Intent(context, CarPlayVpnService::class.java))
                if (!isFinishing && !isDestroyed) panel?.setReady(true)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 同一正文保持阅读进度，主题只刷新颜色。
        theme.applyStyle(R.style.Theme_Xcertplay, true)
        L7Ui.refresh(window.decorView)
        L7Dialogs.refresh(this)
        window.navigationBarColor = getColor(R.color.product_ui_background)
    }
}
