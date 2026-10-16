package com.yodrop.core.trust

import com.yodrop.core.gesture.GestureContext
import com.yodrop.core.gesture.GestureMode

/** UI command binding only; the controller rechecks proof, pin, window and prompt at consent. */
data class TrustedCatchTicket(val windowToken: String, val sessionToken: String, val fingerprint: String) {
    val gestureContext get() = GestureContext(GestureMode.CATCH, "$windowToken:$sessionToken")
}
