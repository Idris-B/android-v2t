package com.voice2text.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voice2text.android.R
import com.voice2text.android.notes.NoteRepository
import com.voice2text.android.settings.PreferencesRepository
import com.voice2text.android.speech.EngineFactory
import com.voice2text.android.speech.SpeechEngine
import com.voice2text.android.speech.TranscriptionEvent
import com.voice2text.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the speech engine lifecycle.
 *
 * Why a foreground service?
 * - Android kills background audio recording within seconds.
 * - A foreground service with type "microphone" keeps the mic alive
 *   and shows a persistent notification so the user knows we're recording.
 *
 * The Activity binds to this service to observe [transcriptionState] and
 * call [startTranscription] / [stopTranscription]. External triggers
 * (notification actions, Quick Settings tile, BT button) send intents
 * with [ACTION_START] or [ACTION_STOP].
 */
class TranscriptionService : Service() {

    companion object {
        const val CHANNEL_ID = "transcription_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.voice2text.android.ACTION_START"
        const val ACTION_STOP = "com.voice2text.android.ACTION_STOP"

        /**
         * Simple static flag so external components (Quick Settings tile,
         * BroadcastReceiver) can check recording state without binding.
         *
         * This is safe because [TranscriptionService] is the sole writer
         * and state transitions happen on the main thread. Readers (tile,
         * receiver) also run on the main thread.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context): Intent =
            Intent(context, TranscriptionService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, TranscriptionService::class.java).apply { action = ACTION_STOP }
    }

    // ── Binder for Activity ─────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): TranscriptionService = this@TranscriptionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── Service state ───────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: SpeechEngine? = null
    private var collectionJob: Job? = null

    private lateinit var prefs: PreferencesRepository
    private lateinit var noteRepo: NoteRepository

    /** Accumulated text from all Final events during this session. */
    private val _fullTranscript = StringBuilder()

    /** Observable state for the UI. */
    sealed class State {
        object Idle : State()
        object Initializing : State()
        data class Listening(val partialText: String) : State()
        data class Saving(val text: String) : State()
        data class Error(val message: String) : State()
    }

    private val _transcriptionState = MutableStateFlow<State>(State.Idle)
    val transcriptionState: StateFlow<State> = _transcriptionState.asStateFlow()

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(applicationContext)
        noteRepo = NoteRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranscription()
            ACTION_STOP -> stopTranscription()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        engine?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun startTranscription() {
        val current = _transcriptionState.value
        if (current is State.Initializing || current is State.Listening) return

        _transcriptionState.value = State.Initializing
        isRunning = true
        _fullTranscript.clear()

        // Promote to foreground immediately so Android doesn't kill us
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))

        serviceScope.launch {
            try {
                // Read which engine the user prefers
                val engineId = prefs.speechEngine.first()

                // Release previous engine if switching
                engine?.release()

                val newEngine = EngineFactory.create(engineId, applicationContext)
                newEngine.initialize()
                engine = newEngine

                // Collect transcription events
                collectionJob = launch {
                    newEngine.events.collect { event ->
                        when (event) {
                            is TranscriptionEvent.Partial -> {
                                _transcriptionState.value = State.Listening(
                                    _fullTranscript.toString() + event.text
                                )
                                updateNotification("Listening...")
                            }
                            is TranscriptionEvent.Final -> {
                                if (_fullTranscript.isNotEmpty()) {
                                    _fullTranscript.append(" ")
                                }
                                _fullTranscript.append(event.text)
                                _transcriptionState.value = State.Listening(
                                    _fullTranscript.toString()
                                )
                            }
                            is TranscriptionEvent.Error -> {
                                _transcriptionState.value = State.Error(event.message)
                                updateNotification("Error: ${event.message}")
                            }
                        }
                    }
                }

                newEngine.startListening()
                _transcriptionState.value = State.Listening("")
                updateNotification("Listening...")

            } catch (e: Exception) {
                _transcriptionState.value = State.Error(
                    e.message ?: "Failed to start transcription"
                )
                updateNotification("Error")
            }
        }
    }

    fun stopTranscription() {
        engine?.stopListening()
        collectionJob?.cancel()
        collectionJob = null

        val finalText = _fullTranscript.toString().trim()

        if (finalText.isNotBlank()) {
            _transcriptionState.value = State.Saving(finalText)

            serviceScope.launch {
                try {
                    val folderUri = prefs.notesFolderUri.first()
                    noteRepo.saveNote(finalText, folderUri)
                } catch (e: Exception) {
                    // Note saving failed — not critical, the text is still
                    // visible in the UI for the user to copy manually.
                    _transcriptionState.value = State.Error(
                        "Transcription complete but failed to save note: ${e.message}"
                    )
                }

                cleanupService()
            }
        } else {
            cleanupService()
        }
    }

    private fun cleanupService() {
        isRunning = false
        _transcriptionState.value = State.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription",
            NotificationManager.IMPORTANCE_LOW  // no sound, just persistent icon
        ).apply {
            description = "Shows when Voice2Text is actively transcribing"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        // Tap notification → open the app
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action button in the notification
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice2Text")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPending
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
