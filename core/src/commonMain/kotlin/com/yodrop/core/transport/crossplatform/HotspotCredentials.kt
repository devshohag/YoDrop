package com.yodrop.core.transport.crossplatform

import kotlinx.serialization.Serializable

/** Actual credentials supplied by AndroidHotspotManager; no fabricated join data. */
@Serializable
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val gatewayIp: String,
    val port: Int
)
