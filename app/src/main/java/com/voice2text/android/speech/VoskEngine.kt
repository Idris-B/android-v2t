package com.voice2text.android.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService
import org.vosk.android.RecognitionListener
import java.io.File

/**
 * Offline speech recognition powered by Vosk.
 *
 * Wraps Vosk's [SpeechService] which manages its own AudioRecord internally,
 * so we don't need to handle raw audio capture. The model (~50 MB for
 * vosk-model-small-en-us-0.15) must exist on disk before [initialize] returns.
 *
 * Threading: Vosk's SpeechService runs audio capture on its own thread and
 * delivers callbacks on that thread. We emit into a [MutableSharedFlow] which
 * is thread-safe, so collectors on any dispatcher will receive events.
 */
class VoskEngine(
    private val context: Context
) : SpeechEngine, RecognitionListener {

    companion object {
        /** Sample rate that matches the small English model. */
        private const val SAMPLE_RATE = 16000f

        /** Subdirectory inside filesDir where the model is stored. */
        const val MODEL_DIR_NAME = "vosk-model"
    }

    private val _events = MutableSharedFlow<TranscriptionEvent>(
        // Small replay so late collectors get the latest partial result
        replay = 1,
        extraBufferCapacity = 64
    )
    override val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    private var model: Model? = null
    private var speechService: SpeechService? = null

    override var isReady: Boolean = false
        private set

    override var isListening: Boolean = false
        private set

    /**
     * Loads the Vosk model from [Context.getFilesDir]/[MODEL_DIR_NAME].
     * The model directory must already exist (downloaded separately).
     *
     * This runs on [Dispatchers.IO] because model loading reads ~50 MB from disk.
     */
    override suspend fun initialize() {
        if (isReady) return

        withContext(Dispatchers.IO) {
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                throw SpeechEngineException(
                    "Vosk model not found at ${modelDir.absolutePath}. " +
                    "Download it first via the model manager."
                )
            }

            try {
                model = Model(modelDir.absolutePath)
                isReady = true
            } catch (e: Exception) {
                throw SpeechEngineException("Failed to load Vosk model", e)
            }
        }
    }

    override fun startListening() {
        if (isListening) return

        val currentModel = model
        if (currentModel == null) {
            _events.tryEmit(TranscriptionEvent.Error("Engine not initialized — call initialize() first"))
            return
        }

        try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(this)
            }
            isListening = true
        } catch (e: Exception) {
            _events.tryEmit(TranscriptionEvent.Error("Failed to start Vosk: ${e.message}"))
        }
    }

    override fun stopListening() {
        if (!isListening) return

        speechService?.stop()
        speechService = null
        isListening = false
    }

    override fun release() {
        stopListening()
        model?.close()
        model = null
        isReady = false
    }

    // ── RecognitionListener callbacks (called on Vosk's audio thread) ────

    override fun onPartialResult(hypothesis: String?) {
        val text = extractText(hypothesis, key = "partial")
        if (text.isNotBlank()) {
            _events.tryEmit(TranscriptionEvent.Partial(text))
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = extractText(hypothesis, key = "text")
        if (text.isNotBlank()) {
            _events.tryEmit(TranscriptionEvent.Final(text))
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = extractText(hypothesis, key = "text")
        if (text.isNotBlank()) {
            _events.tryEmit(TranscriptionEvent.Final(text))
        }
    }

    override fun onError(exception: Exception?) {
        _events.tryEmit(
            TranscriptionEvent.Error(exception?.message ?: "Unknown Vosk error")
        )
    }

    override fun onTimeout() {
        stopListening()
    }

    /**
     * Vosk returns JSON strings like {"partial": "hello world"} or {"text": "hello world"}.
     * This extracts the value for the given key, returning empty string on failure.
     */
    private fun extractText(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }
}
