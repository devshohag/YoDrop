package com.yodrop.core.session

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.OfferMessage

sealed interface SenderState {
    object Idle : SenderState
    data class Advertising(val offer: OfferMessage) : SenderState
    data class AwaitAccept(val verifyCode: String) : SenderState
    data class Linking(val linkInfo: LinkInfoMessage) : SenderState
    object Auth : SenderState
    data class Transferring(val progress: Float, val currentFileName: String) : SenderState
    object Done : SenderState
    data class Cancelled(val reason: String) : SenderState
    data class Failed(val error: String) : SenderState
}

sealed interface ReceiverState {
    object Idle : ReceiverState
    object Scanning : ReceiverState
    data class OfferSeen(val offer: OfferMessage) : ReceiverState
    object Claimed : ReceiverState
    data class AcceptPrompt(val offer: OfferMessage, val verifyCode: String) : ReceiverState
    data class Linking(val linkInfo: LinkInfoMessage) : ReceiverState
    object Auth : ReceiverState
    data class Receiving(val progress: Float, val currentFileName: String) : ReceiverState
    data class Verifying(val fileId: String) : ReceiverState
    object Done : ReceiverState
    data class Cancelled(val reason: String) : ReceiverState
    data class Failed(val error: String) : ReceiverState
}

object SessionTimeouts {
    var OFFER_TIMEOUT_MS: Long = 60_000L
    var CLAIM_TIMEOUT_MS: Long = 10_000L
    var LINKING_TIMEOUT_MS: Long = 20_000L
    var SOCKET_IDLE_TIMEOUT_MS: Long = 15_000L
    var PING_INTERVAL_MS: Long = 5_000L

    fun resetToDefaults() {
        OFFER_TIMEOUT_MS = 60_000L
        CLAIM_TIMEOUT_MS = 10_000L
        LINKING_TIMEOUT_MS = 20_000L
        SOCKET_IDLE_TIMEOUT_MS = 15_000L
        PING_INTERVAL_MS = 5_000L
    }
}
