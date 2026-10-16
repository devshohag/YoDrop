package com.yodrop.android.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yodrop.android.MainActivity
import com.yodrop.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TransferForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: TransferRuntime
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var observing = false
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        runtime = TransferRuntime.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YoDrop transfers", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.hasExtra(TransferRuntime.SERVICE_GENERATION) == true)
                generation = intent.getIntExtra(TransferRuntime.SERVICE_GENERATION, 0)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing transfer..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            if (intent?.action == ACTION_CANCEL) runtime.scope.launch { runtime.cancel("Cancelled from notification") }
            if (!runtime.active()) { stopSelf(); return START_NOT_STICKY }
            if (!observing) {
                observing = true
                serviceScope.launch {
                    combine(runtime.sender.state, runtime.receiver.state) { a, b ->
                        Status(ForegroundSessionPolicy.required(a, b), ForegroundSessionPolicy.powerRequired(a, b), label(a, b))
                    }.distinctUntilChanged().collect { status ->
                        if (!status.active) {
                            releasePower()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            try {
                                if (status.power) holdPower() else releasePower()
                                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status.label))
                            } catch (_: RuntimeException) {
                                releasePower()
                                runtime.cancel("Foreground transfer resources unavailable")
                            }
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            runtime.scope.launch { runtime.cancel("Foreground service could not start") }
            releasePower()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun holdPower() {
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YoDrop:transfer").also { it.setReferenceCounted(false) }
        if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YoDrop:transfer").also { it.setReferenceCounted(false) }
        if (wake?.isHeld != true) wake?.acquire()
        if (wifi?.isHeld != true) wifi?.acquire()
    }
    private fun releasePower(): Boolean {
        val wifiReleased = runCatching {
            if (wifi?.isHeld == true) wifi?.release()
            wifi?.isHeld != true
        }.getOrDefault(false)
        val wakeReleased = runCatching {
            if (wake?.isHeld == true) wake?.release()
            wake?.isHeld != true
        }.getOrDefault(false)
        if (wifiReleased) wifi = null
        if (wakeReleased) wake = null
        if (!wifiReleased || !wakeReleased) Log.w("YoDropService", "Power lock release unconfirmed")
        return wifiReleased && wakeReleased
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TransferForegroundService::class.java)
            .setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("YoDrop transfer")
            .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
    }
    override fun onDestroy() {
        val released = releasePower()
        serviceScope.cancel()
        if (::runtime.isInitialized) runtime.onServiceDestroyed(generation)
        Log.d("YoDropService", "FOREGROUND_STOPPED locksReleased=$released")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private data class Status(val active: Boolean, val power: Boolean, val label: String)
    private fun label(a: SenderState, b: ReceiverState): String = when {
        (a is SenderState.Failed && a.canResume) || (b is ReceiverState.Failed && b.canResume) -> "Connection lost. Reconnecting..."
        a is SenderState.Transferring -> "Sending files securely"
        b is ReceiverState.Receiving -> "Receiving files securely"
        b is ReceiverState.Verifying -> "Verifying received files"
        a is SenderState.Linking || b is ReceiverState.Linking || a is SenderState.Auth || b is ReceiverState.Auth -> "Connecting securely"
        else -> "Waiting for peer or confirmation"
    }
    companion object {
        const val CHANNEL_ID = "YoDropTransferChannel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.yodrop.android.CANCEL_TRANSFER"
    }
}
