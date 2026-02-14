package com.voice2text.android.trigger

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.voice2text.android.service.TranscriptionService

/**
 * Manages a [MediaSession] that captures hardware media button events
 * (Bluetooth headset button, wired earbuds button, etc.) and uses them
 * to toggle transcription.
 *
 * How it works:
 * - We create an active MediaSession with an empty playback state.
 *   Android routes media button events to the most recently active
 *   MediaSession, so ours will receive them as long as no music app
 *   has taken over.
 * - The callback intercepts play/pause and headsethook key events
 *   and toggles the TranscriptionService.
 * - We set a "paused" PlaybackState so Android treats our session as
 *   active enough to receive buttons, but doesn't show playback UI
 *   in the notification shade.
 *
 * Lifecycle: call [activate] when the user enables the Bluetooth trigger
 * in settings, [deactivate] when they disable it or the app is destroyed.
 *
 * Why MediaSession over AccessibilityService?
 * - No special permissions needed.
 * - No user trip to Settings → Accessibility.
 * - No Google Play review scrutiny.
 * - Works with any standard Bluetooth media button.
 * - Limitation: if another media app takes focus (e.g. Spotify playing),
 *   it steals the media button. This is expected — most users wouldn't
 *   want to accidentally start recording while listening to music.
 */
class MediaSessionTrigger(private val context: Context) {

    private var mediaSession: MediaSession? = null

    /** BT button behavior: "toggle" (default) or "hold" (hold-to-record). */
    var mode: String = "toggle"

    val isActive: Boolean get() = mediaSession != null

    fun activate() {
        if (mediaSession != null) return

        val session = MediaSession(context, "Voice2TextTrigger")

        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false

                val isMediaButton = event.keyCode in listOf(
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK
                )
                if (!isMediaButton) return false

                when (mode) {
                    "hold" -> {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> startTranscription()
                            KeyEvent.ACTION_UP -> stopTranscription()
                        }
                    }
                    else -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            toggleTranscription()
                        }
                    }
                }
                return true
            }
        }, Handler(Looper.getMainLooper()))

        // Set a "paused" playback state so Android considers this session
        // active and routes media buttons to it. Without this, buttons go
        // to the last music app that was playing.
        val playbackState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE
            )
            .build()
        session.setPlaybackState(playbackState)

        session.isActive = true
        mediaSession = session
    }

    fun deactivate() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun toggleTranscription() {
        if (TranscriptionService.isRunning) {
            stopTranscription()
        } else {
            startTranscription()
        }
    }

    private fun startTranscription() {
        if (TranscriptionService.isRunning) return
        val startIntent = TranscriptionService.startIntent(context)
        ContextCompat.startForegroundService(context, startIntent)
    }

    private fun stopTranscription() {
        if (!TranscriptionService.isRunning) return
        val stopIntent = TranscriptionService.stopIntent(context)
        context.startService(stopIntent)
    }
}
