package com.yodrop.android.gesture

import android.content.Context
import android.app.NotificationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.provider.Settings

/** Application context only; feedback failures never change transfer outcome. */
class AndroidTransferFeedback(context: Context) {
    private val app = context.applicationContext
    fun grabbed() = haptic(false)
    fun completed() { haptic(true); sound() }
    private fun haptic(done: Boolean) {
        runCatching {
            if (Settings.System.getInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val vibrator = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
                else app.getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createPredefined(
                if (done) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_CLICK))
        }
    }
    private fun sound() {
        runCatching {
            val audio = app.getSystemService(AudioManager::class.java)
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return
            if (app.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            try {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
                Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 250)
            } catch (failure: Exception) { tone.release(); throw failure }
        }
    }
}
