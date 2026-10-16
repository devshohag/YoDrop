package com.yodrop.core.trust

data class PeerIdentityStatus(val sessionToken: String, val fingerprint: String, val label: String, val trusted: Boolean, val pairingCode: String)

class TrustedPairing(
    val identity: LocalDeviceIdentity,
    val registry: TrustedDeviceRegistry,
    val receiveWindow: TrustedReceiveWindow
) {
    var requiredWindow: ReceiveWindow? = null
        private set
    fun armReceive(fingerprint: String): ReceiveWindow = receiveWindow.open(fingerprint).also { requiredWindow = it }
    fun clearReceiveSession() { receiveWindow.close(); requiredWindow = null }
}
