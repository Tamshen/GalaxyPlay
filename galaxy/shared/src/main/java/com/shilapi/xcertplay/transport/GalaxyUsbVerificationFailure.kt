package com.shilapi.xcertplay.transport

import java.io.IOException

/** 固定原因码供界面与日志共用，系统异常正文不越过实验边界。 */
class GalaxyUsbVerificationFailure(val reason: Reason) : IOException("USB_VERIFY_${reason.name}") {
    enum class Reason {
        CANCELLED, LOCAL_CONTROL_UNAVAILABLE, CONTROL_NOT_ROOT, RESTORE_REQUIRED,
        CONFIG_UNSUPPORTED, GADGET_RULE_MISSING, CONTROLLER_UNAVAILABLE,
        IPOD_NODE_UNAVAILABLE, PHONE_UNAVAILABLE, PHONE_CAPABILITY_REJECTED,
        PHONE_ROLE_REJECTED, CONFIG_NOT_APPLIED, GADGET_NOT_CONFIGURED,
        NETWORK_NOT_READY, NODE_OPEN_FAILED, LINK_FAILED, SESSION_FAILED, RESTORE_FAILED,
    }
}
