package com.voice2text.android.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.voice2text.android.service.TranscriptionService
import com.voice2text.android.settings.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Receives media button events (e.g. from Bluetooth headsets) and
 * toggles transcription.
 *
 * This receiver works in tandem with [MediaSessionTrigger], which holds
 * the active [android.media.session.MediaSession]. Android routes hardware
 * media button presses to the active MediaSession's callback first, and
 * that callback delegates to this receiver for the actual start/stop logic.
 *
 * We only act on ACTION_DOWN to avoid double-firing (buttons send both
 * DOWN and UP events).
 *
 * Why a separate receiver?
 * - Keeps the MediaSession callback thin (just forwards the intent).
 * - Can also be declared in the manifest as a fallback for when the
 *   MediaSession isn't active (Android will route to the last-active
 *   media button receiver).
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return

        val isMediaButton = event.keyCode in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK
        )
        if (!isMediaButton) return

        // BroadcastReceivers are short-lived — runBlocking is acceptable here
        val mode = runBlocking {
            PreferencesRepository(context.applicationContext).btTriggerMode.first()
        }

        when (mode) {
            "hold" -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> startTranscription(context)
                    KeyEvent.ACTION_UP -> stopTranscription(context)
                }
            }
            else -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    toggleTranscription(context)
                }
            }
        }
    }

    private fun toggleTranscription(context: Context) {
        if (TranscriptionService.isRunning) {
            stopTranscription(context)
        } else {
            startTranscription(context)
        }
    }

    private fun startTranscription(context: Context) {
        if (TranscriptionService.isRunning) return
        val startIntent = TranscriptionService.startIntent(context)
        ContextCompat.startForegroundService(context, startIntent)
    }

    private fun stopTranscription(context: Context) {
        if (!TranscriptionService.isRunning) return
        val stopIntent = TranscriptionService.stopIntent(context)
        context.startService(stopIntent)
    }
}
