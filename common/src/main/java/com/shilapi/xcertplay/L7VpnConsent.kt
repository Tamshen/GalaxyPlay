package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle

/** 授权页面启动异常与用户返回分别处理；销毁后的迟到回调不能启动连接。 */
internal class L7VpnConsent(
    private val prepare: () -> Intent?,
    private val launch: (Intent) -> Unit,
    private val trace: (String, String, Throwable?) -> Unit,
    private val ready: () -> Unit,
    private val failed: (Failure) -> Unit,
) {
    enum class Failure { PAGE_MISSING, SYSTEM_DENIED, PREPARE_FAILED, LAUNCH_FAILED, DECLINED }
    private var waiting = false
    private var disposed = false

    fun restoreWaiting(savedAttempt: String?) {
        if (disposed || waiting || savedAttempt.isNullOrBlank()) return
        waiting = true
        trace("VPN_RESTORE", "WAITING", null)
    }

    fun saveWaiting(state: Bundle, attempt: String?) {
        if (!disposed && waiting && !attempt.isNullOrBlank()) state.putString(PENDING_ATTEMPT, attempt)
        else state.remove(PENDING_ATTEMPT)
    }

    fun request() {
        if (disposed || waiting) return
        trace("VPN_PREPARE", "BEGIN", null)
        val consent = try { prepare() } catch (error: RuntimeException) {
            failure("VPN_PREPARE", error)
            return
        } catch (error: LinkageError) {
            failure("VPN_PREPARE", error)
            return
        }
        if (consent == null) {
            trace("VPN_PREPARE", "READY", null)
            ready()
            return
        }
        trace("VPN_PREPARE", "CONSENT_REQUIRED", null)
        waiting = true
        trace("VPN_LAUNCH", "BEGIN", null)
        try {
            launch(consent)
            if (waiting) trace("VPN_LAUNCH", "WAITING", null)
        } catch (error: RuntimeException) {
            failure("VPN_LAUNCH", error)
        } catch (error: LinkageError) {
            failure("VPN_LAUNCH", error)
        }
    }

    fun returned(code: Int) {
        if (disposed || !waiting) return
        waiting = false
        if (code == Activity.RESULT_OK) {
            trace("VPN_RESULT", "GRANTED", null)
            // 系统返回成功后再次查实际授权；失效时停止，不循环拉起同一授权页。
            val consent = try { prepare() } catch (error: RuntimeException) {
                failure("VPN_PREPARE", error); return
            } catch (error: LinkageError) {
                failure("VPN_PREPARE", error); return
            }
            if (consent == null) {
                trace("VPN_RECHECK", "READY", null)
                ready()
            } else {
                trace("VPN_RECHECK", "NOT_PREPARED", null)
                failed(Failure.PREPARE_FAILED)
            }
        } else {
            // RESULT_CANCELED 无法可靠区分用户拒绝、返回和系统取消。
            trace("VPN_RESULT", "DECLINED_OR_CANCELLED", null)
            failed(Failure.DECLINED)
        }
    }

    fun dispose() { disposed = true; waiting = false }

    private fun failure(stage: String, error: Throwable) {
        waiting = false
        trace(stage, "FAILED", error)
        failed(when (error) {
            is ActivityNotFoundException -> Failure.PAGE_MISSING
            is SecurityException -> Failure.SYSTEM_DENIED
            else -> if (stage == "VPN_PREPARE") Failure.PREPARE_FAILED else Failure.LAUNCH_FAILED
        })
    }

    companion object {
        private const val PENDING_ATTEMPT = "l7.vpn.pendingAttempt"
        fun savedAttempt(state: Bundle?, wireless: Boolean): String? =
            if (wireless) null else state?.getString(PENDING_ATTEMPT)?.takeIf { it.isNotBlank() }
    }
}
