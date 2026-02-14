package com.voice2text.android.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Speech recognition using Android's built-in [SpeechRecognizer].
 *
 * Uses Google's speech services under the hood (online by default, some
 * devices support offline). Simpler setup than Vosk — no model download —
 * but requires network for best results.
 *
 * Threading: Android's SpeechRecognizer MUST be created and called on the
 * main thread. We use a [Handler] to ensure all operations post to the
 * main looper, so callers can invoke start/stop from any thread safely.
 */
class AndroidSpeechEngine(
    private val context: Context
) : SpeechEngine, RecognitionListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _events = MutableSharedFlow<TranscriptionEvent>(
        replay = 1,
        extraBufferCapacity = 64
    )
    override val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    override var isReady: Boolean = false
        private set

    override var isListening: Boolean = false
        private set

    /**
     * Checks that speech recognition is available on this device.
     * No heavy resources to load — this is essentially a capability check.
     */
    override suspend fun initialize() {
        if (isReady) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw SpeechEngineException(
                "Speech recognition is not available on this device. " +
                "Ensure Google app is installed and up to date."
            )
        }
        isReady = true
    }

    override fun startListening() {
        if (isListening) return
        if (!isReady) {
            _events.tryEmit(
                TranscriptionEvent.Error("Engine not initialized — call initialize() first")
            )
            return
        }

        mainHandler.post {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(this)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    // Return partial results as they come in
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Don't auto-stop after silence — we control stop explicitly
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30_000L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30_000L
                    )
                }

                recognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                _events.tryEmit(
                    TranscriptionEvent.Error("Failed to start recognizer: ${e.message}")
                )
            }
        }
    }

    override fun stopListening() {
        if (!isListening) return

        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                // Ignore — we're tearing down anyway
            }
            isListening = false
        }
    }

    override fun release() {
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            recognizer = null
            isReady = false
            isListening = false
        }
    }

    // ── RecognitionListener callbacks (called on main thread) ────────────

    override fun onReadyForSpeech(params: Bundle?) {
        // Recognition is ready — mic is active
    }

    override fun onBeginningOfSpeech() {
        // User started speaking
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Audio level changed — could drive a visualizer
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        // Raw audio buffer — not needed for transcription
    }

    override fun onEndOfSpeech() {
        // User stopped speaking — results will follow
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Unknown error ($error)"
        }
        _events.tryEmit(TranscriptionEvent.Error(message))
        isListening = false

        // Android's SpeechRecognizer stops after an error or result,
        // so if we want continuous listening we need to restart.
        // For now we just report the error — the service can decide to restart.
    }

    override fun onResults(results: Bundle?) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = texts?.firstOrNull() ?: ""
        if (text.isNotBlank()) {
            _events.tryEmit(TranscriptionEvent.Final(text))
        }
        isListening = false
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = texts?.firstOrNull() ?: ""
        if (text.isNotBlank()) {
            _events.tryEmit(TranscriptionEvent.Partial(text))
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        // Reserved for future use by Android
    }
}
