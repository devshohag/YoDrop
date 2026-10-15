package com.yodrop.core.session

import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.OfferMessage

sealed interface SenderState {
    data object Idle : SenderState
    data class Grabbed(val files: List<FileMeta>) : SenderState
    data class Advertising(val offer: OfferMessage) : SenderState
    data class Claimed(val receiverPubKey: ByteArray) : SenderState
    data class AwaitAccept(val verifyCode: String) : SenderState
    data class Linking(val linkInfo: LinkInfoMessage) : SenderState
    data object Auth : SenderState
    data class Transferring(val progressPercent: Float, val currentFile: String) : SenderState
    data object Done : SenderState
    data class Failed(val error: String) : SenderState
    data class Cancelled(val reason: String) : SenderState
}

sealed interface ReceiverState {
    data object Idle : ReceiverState
    data object Scanning : ReceiverState
    data class OfferSeen(val offer: OfferMessage) : ReceiverState
    data object Claimed : ReceiverState
    data class AcceptPrompt(val offer: OfferMessage, val verifyCode: String) : ReceiverState
    data class Linking(val linkInfo: LinkInfoMessage) : ReceiverState
    data object Auth : ReceiverState
    data class Receiving(val progressPercent: Float, val currentFile: String) : ReceiverState
    data class Verifying(val fileId: String) : ReceiverState
    data object Done : ReceiverState
    data class Failed(val error: String) : ReceiverState
    data class Cancelled(val reason: String) : ReceiverState
}

object SessionTimeouts {
    const val OFFER_TIMEOUT_MS = 60_000L      // 60s offer window
    const val CLAIM_WINDOW_MS = 10_000L       // 10s claim window
    const val LINKING_TIMEOUT_MS = 20_000L    // 20s link setup timeout
    const val SOCKET_IDLE_TIMEOUT_MS = 15_000L // 15s socket idle timeout
    const val PING_INTERVAL_MS = 5_000L       // 5s ping
}
