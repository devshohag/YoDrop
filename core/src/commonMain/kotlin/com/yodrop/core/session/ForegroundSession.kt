package com.yodrop.core.session

/** Discovery/consent are bounded parts of a user-started session; retain service during resume. */
object ForegroundSessionPolicy {
    fun powerRequired(sender: SenderState, receiver: ReceiverState): Boolean =
        sender is SenderState.Linking || sender is SenderState.Auth || sender is SenderState.Transferring ||
            (sender is SenderState.Failed && sender.canResume) || receiver is ReceiverState.Linking ||
            receiver is ReceiverState.Auth || receiver is ReceiverState.Receiving || receiver is ReceiverState.Verifying ||
            (receiver is ReceiverState.Failed && receiver.canResume)

    fun required(sender: SenderState, receiver: ReceiverState): Boolean =
        (sender !is SenderState.Idle && !SessionPolicy.senderTerminal(sender)) ||
            (receiver !is ReceiverState.Idle && !SessionPolicy.receiverTerminal(receiver))
}

interface ForegroundServicePort {
    fun start()
    fun stop()
}

/** Main/actor-confined. A visible user action requests start; state changes only decide when to stop. */
class ForegroundSession(private val platform: ForegroundServicePort) {
    private var requested = false
    fun begin() {
        if (!requested) { platform.start(); requested = true }
    }
    fun update(sender: SenderState, receiver: ReceiverState) {
        if (requested && !ForegroundSessionPolicy.required(sender, receiver)) {
            requested = false
            platform.stop()
        }
    }
}

/** Platform-neutral permission plan so SDK cutovers and optional notification denial are testable. */
data class TransferPermissions(val required: Set<String>, val optional: Set<String>) {
    fun mayProceed(granted: Set<String>) = granted.containsAll(required)
    companion object {
        private const val PREFIX = "android.permission."
        fun forApi(api: Int): TransferPermissions {
            require(api >= 29)
            val required = mutableSetOf<String>()
            if (api >= 31) required.addAll(listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT"))
            // Android 12 requires both in the same runtime request; precise access is needed for Wi-Fi discovery.
            if (api <= 32) required.addAll(listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"))
            if (api >= 33) required.add("NEARBY_WIFI_DEVICES")
            return TransferPermissions(required.map { PREFIX + it }.toSet(),
                if (api >= 33) setOf(PREFIX + "POST_NOTIFICATIONS") else emptySet())
        }
    }
}
