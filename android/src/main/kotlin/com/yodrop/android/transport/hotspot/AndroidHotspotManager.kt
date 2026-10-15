package com.yodrop.android.transport.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidHotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun startLocalHotspot(port: Int = 8988): Result<HotspotCredentials> = withContext(Dispatchers.Main) {
        val wm = wifiManager ?: return@withContext Result.failure(IllegalStateException("WifiManager unavailable"))
        val deferred = CompletableDeferred<Result<HotspotCredentials>>()

        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    val config = reservation?.wifiConfiguration
                    if (config != null) {
                        val creds = HotspotCredentials(
                            ssid = config.SSID ?: "YoDrop-Hotspot",
                            passphrase = config.preSharedKey ?: "",
                            gatewayIp = "192.168.43.1",
                            port = port
                        )
                        deferred.complete(Result.success(creds))
                    } else {
                        deferred.complete(Result.failure(IllegalStateException("Hotspot configuration is null")))
                    }
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    deferred.complete(Result.failure(IllegalStateException("Failed to start LocalOnlyHotspot: reason=$reason")))
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                }
            }, null)
        } catch (e: Exception) {
            deferred.complete(Result.failure(e))
        }

        deferred.await()
    }

    fun stopLocalHotspot() {
        hotspotReservation?.close()
        hotspotReservation = null
    }
}
