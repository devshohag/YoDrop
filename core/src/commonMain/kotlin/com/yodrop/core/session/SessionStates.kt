package com.yodrop.core.session

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.OfferMessage

sealed interface SenderState {
    data object Idle : SenderState
    data object Claimed : SenderState
    data class Grabbed(val offer: OfferMessage) : SenderState
    data class AwaitAccept(val verifyCode: String) : SenderState
    data class Linking(val linkInfo: LinkInfoMessage? = null) : SenderState
    data object Auth : SenderState
    data class Transferring(val progress: Float, val currentFileName: String) : SenderState
    data object Done : SenderState
    data class Cancelled(val reason: String) : SenderState
    data class Failed(val error: String, val canResume: Boolean = false) : SenderState
}

sealed interface ReceiverState {
    data object Idle : ReceiverState
    data object Scanning : ReceiverState
    data class OfferSeen(val offer: OfferMessage) : ReceiverState
    data object Claimed : ReceiverState
    data class AcceptPrompt(val offer: OfferMessage, val verifyCode: String, val previewName: String) : ReceiverState
    data class Linking(val linkInfo: LinkInfoMessage? = null) : ReceiverState
    data object Auth : ReceiverState
    data class Receiving(val progress: Float, val currentFileName: String) : ReceiverState
    data class Verifying(val fileId: String) : ReceiverState
    data object Done : ReceiverState
    data class Cancelled(val reason: String) : ReceiverState
    data class Failed(val error: String, val canResume: Boolean = false) : ReceiverState
}

