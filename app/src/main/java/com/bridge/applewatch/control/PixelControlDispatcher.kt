package com.bridge.applewatch.control

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PixelControlDispatcher(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The alarm currently playing, so a repeat request coalesces instead of stacking. */
    private var activeRingtone: Ringtone? = null
    private var alarmJob: Job? = null

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
                // Reported as best-effort on purpose. `sendBroadcast` never throws when
                // no receiver exists, so the previous implementation reported success
                // whenever the intent was merely *queued* — a claim it could not support.
                triggerCameraShutterAttempt()
                "Camera shutter broadcast attempted (no confirmation available)"
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
     * Best-effort shutter trigger, targeted at the camera app rather than broadcast
     * device-wide.
     *
     * `sendBroadcast` cannot report whether anything handled the intent, so this returns
     * nothing and the caller must not claim success (D-09). An earlier version returned
     * `broadcastOk || keyOk` where `broadcastOk` meant only "sendBroadcast did not
     * throw", which is always true — so the failure branch was unreachable and the
     * dashboard always reported a shutter that had almost certainly not fired.
     *
     * `setPackage` also stops the intent reaching arbitrary apps, including stopped
     * packages, from an endpoint that is unauthenticated by default.
     */
    private fun triggerCameraShutterAttempt() {
        for (packageName in CAMERA_PACKAGES) {
            try {
                val intent = Intent(CAMERA_SHUTTER_ACTION).setPackage(packageName)
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // Not installed or refuses the broadcast; try the next candidate.
                continue
            }
        }
    }

    private fun triggerFindMyPixelAlarm() {
        // Coalesce repeats: pressing RING repeatedly used to start an overlapping
        // Ringtone and 5-second coroutine per request, so the live-object count grew
        // with the request rate.
        alarmJob?.cancel()
        activeRingtone?.stop()

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
        activeRingtone = ringtone
        alarmJob = scope.launch {
            delay(ALARM_DURATION_MS)
            activeRingtone?.stop()
            activeRingtone = null
        }
    }

    private companion object {
        /** Camera intents are app-specific; there is no standard system broadcast. */
        val CAMERA_PACKAGES = listOf(
            "com.google.android.GoogleCamera",
            "com.android.camera2",
            "com.android.camera"
        )
        const val CAMERA_SHUTTER_ACTION = "com.google.android.camera.action.SHUTTER"
        const val ALARM_DURATION_MS = 5_000L
    }
}
