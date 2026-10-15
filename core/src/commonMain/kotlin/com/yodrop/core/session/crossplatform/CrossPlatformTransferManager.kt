package com.yodrop.core.session.crossplatform

import com.yodrop.core.platform.FileSource
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.crossplatform.CrossPlatformBridge
import com.yodrop.core.transport.crossplatform.HotspotCredentials

class CrossPlatformTransferManager(
    private val senderStateMachine: SenderStateMachine,
    private val bridge: CrossPlatformBridge
) {
    suspend fun prepareAndroidSender(
        files: List<FileMeta>,
        fileDataMap: Map<String, ByteArray>
    ): LinkInfoMessage {
        val sources: Map<String, FileSource> = fileDataMap.mapValues { (id, data) ->
            MemoryFileSource(id, data)
        }
        senderStateMachine.onGrabbed(files, sources)

        val creds = bridge.startHotspot()
        return creds.toLinkInfo()
    }

    suspend fun joinFromIos(linkInfo: LinkInfoMessage): Boolean {
        if (linkInfo.linkType != LinkType.HOTSPOT) return false
        val creds = HotspotCredentials.fromLinkInfo(linkInfo) ?: return false
        return bridge.connectToHotspot(creds)
    }

    suspend fun cleanup() {
        bridge.stopHotspot()
    }
}
