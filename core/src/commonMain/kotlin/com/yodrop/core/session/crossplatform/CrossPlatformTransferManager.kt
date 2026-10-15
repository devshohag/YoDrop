package com.yodrop.core.session.crossplatform

import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LanTransport
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class CrossPlatformTransferManager(
    private val scope: CoroutineScope,
    val senderMachine: SenderStateMachine = SenderStateMachine(scope),
    val receiverMachine: ReceiverStateMachine = ReceiverStateMachine(scope)
) {
    private val lanTransport = LanTransport()

    fun prepareAndroidSender(
        files: List<FileMeta>,
        fileData: Map<String, ByteArray>,
        hotspotCreds: HotspotCredentials
    ): LinkInfoMessage {
        senderMachine.onGrabbed(files, fileData)
        return hotspotCreds.toLinkInfo()
    }

    suspend fun connectClientOverHotspot(linkInfo: LinkInfoMessage): Connection {
        requireNotNull(HotspotCredentials.fromLinkInfo(linkInfo)) {
            "Invalid hotspot link info: missing ssid or passphrase"
        }
        return lanTransport.connect(linkInfo)
    }

    fun cancelAll(reason: String = "User aborted cross-platform transfer") {
        senderMachine.cancel(reason)
        receiverMachine.cancel(reason)
    }
}
