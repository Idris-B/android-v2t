package com.voice2text.android.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.voice2text.android.service.TranscriptionService

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

        // Only respond to key-down to avoid double-firing
        if (event.action != KeyEvent.ACTION_DOWN) return

        // We respond to the "play/pause" button — the most common single
        // button on Bluetooth headsets and wired earbuds.
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                toggleTranscription(context)
            }
            // Other media buttons (skip, rewind) are intentionally ignored
            // so they don't interfere with music apps.
        }
    }

    private fun toggleTranscription(context: Context) {
        if (TranscriptionService.isRunning) {
            val stopIntent = TranscriptionService.stopIntent(context)
            context.startService(stopIntent)
        } else {
            val startIntent = TranscriptionService.startIntent(context)
            ContextCompat.startForegroundService(context, startIntent)
        }
    }
}
