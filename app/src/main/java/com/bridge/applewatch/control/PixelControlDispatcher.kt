package com.bridge.applewatch.control

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PixelControlDispatcher(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun executeCommand(command: String): String {
        return when (command.trim().uppercase()) {
            "FIND_MY_PIXEL", "RING" -> {
                triggerFindMyPixelAlarm()
                "Triggered Find My Pixel 9 Alarm (5s)"
            }
            "MEDIA_PLAY_PAUSE" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "Toggled Pixel 9 Media Play/Pause"
            }
            "MEDIA_NEXT" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "Skipped to Next Track"
            }
            "MEDIA_PREV" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Returned to Previous Track"
            }
            "VOLUME_UP" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                "Increased Pixel 9 Media Volume"
            }
            "VOLUME_DOWN" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                "Decreased Pixel 9 Media Volume"
            }
            "CAMERA_SHUTTER" -> {
                val dispatched = triggerCameraOrVolumeShutter()
                if (dispatched) {
                    "Dispatched Pixel 9 Camera Shutter KeyEvent"
                } else {
                    "Camera shutter dispatch failed (broadcast and key event both rejected)"
                }
            }
            else -> "Ignored Unknown Command: $command"
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    /**
     * Best-effort shutter dispatch. Returns false when both the broadcast and the
     * fallback key event failed, so the caller stops reporting a false success (D-09).
     */
    private fun triggerCameraOrVolumeShutter(): Boolean {
        var broadcastOk = false
        try {
            val intent = Intent("com.google.android.camera.action.SHUTTER").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            broadcastOk = true
        } catch (_: Exception) {
        }
        val keyOk = try {
            dispatchMediaKey(KeyEvent.KEYCODE_CAMERA)
            true
        } catch (_: Exception) {
            false
        }
        return broadcastOk || keyOk
    }

    private fun triggerFindMyPixelAlarm() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1)
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone?.play()
        scope.launch {
            delay(5000L)
            ringtone?.stop()
        }
    }
}
