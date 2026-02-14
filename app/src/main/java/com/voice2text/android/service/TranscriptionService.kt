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
import com.voice2text.android.speech.AudioRecorder
import com.voice2text.android.speech.EngineFactory
import com.voice2text.android.speech.SpeechEngine
import com.voice2text.android.speech.TranscriptionEvent
import com.voice2text.android.ui.MainActivity
import com.voice2text.android.ui.NoteDetailActivity
import java.io.File
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
        const val NOTE_SAVED_CHANNEL_ID = "notes_saved_channel"
        const val NOTIFICATION_ID = 1
        const val NOTE_SAVED_NOTIFICATION_ID = 2

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
    private var audioRecorder: AudioRecorder? = null
    private var audioFilePath: String? = null

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
        audioRecorder?.release()
        audioRecorder = null
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

                // Start audio recording if enabled
                val saveAudio = prefs.saveAudioRecording.first()
                if (saveAudio) {
                    try {
                        val notesDir = File(filesDir, "notes").also { it.mkdirs() }
                        val timestamp = java.time.LocalDateTime.now()
                        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                        val audioFile = File(notesDir, "VoiceNote_${fmt.format(timestamp)}.m4a")
                        val recorder = AudioRecorder()
                        recorder.start(applicationContext, audioFile)
                        audioRecorder = recorder
                        audioFilePath = audioFile.absolutePath
                    } catch (e: Exception) {
                        // Audio recording is best-effort; don't fail transcription
                        android.util.Log.e("TranscriptionService", "Failed to start audio recording", e)
                        audioRecorder = null
                        audioFilePath = null
                    }
                }

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

        // Stop audio recording
        val savedAudioPath = try {
            audioRecorder?.stop()?.absolutePath
        } catch (_: Exception) {
            null
        }
        audioRecorder = null

        val finalText = _fullTranscript.toString().trim()

        if (finalText.isNotBlank()) {
            _transcriptionState.value = State.Saving(finalText)

            serviceScope.launch {
                try {
                    val folderUri = prefs.notesFolderUri.first()
                    val savedNote = noteRepo.saveNote(finalText, folderUri, savedAudioPath ?: audioFilePath)
                    showNoteSavedNotification(savedNote.filePath, finalText)
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
        audioFilePath = null
        _transcriptionState.value = State.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        val transcriptionChannel = NotificationChannel(
            CHANNEL_ID,
            "Transcription",
            NotificationManager.IMPORTANCE_LOW  // no sound, just persistent icon
        ).apply {
            description = "Shows when Voice2Text is actively transcribing"
        }
        manager.createNotificationChannel(transcriptionChannel)

        val noteSavedChannel = NotificationChannel(
            NOTE_SAVED_CHANNEL_ID,
            getString(R.string.note_saved_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT  // sound + vibration
        ).apply {
            description = "Shows when a transcribed note has been saved"
        }
        manager.createNotificationChannel(noteSavedChannel)
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

    private fun showNoteSavedNotification(notePath: String, noteText: String) {
        val tapIntent = Intent(this, NoteDetailActivity::class.java).apply {
            putExtra(NoteDetailActivity.EXTRA_NOTE_PATH, notePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 2, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val preview = if (noteText.length > 60) noteText.take(60) + "…" else noteText

        val notification = NotificationCompat.Builder(this, NOTE_SAVED_CHANNEL_ID)
            .setContentTitle(getString(R.string.note_saved_notification_title))
            .setContentText(preview)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTE_SAVED_NOTIFICATION_ID, notification)
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
