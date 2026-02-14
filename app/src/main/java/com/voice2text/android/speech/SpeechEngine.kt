package com.voice2text.android.speech

import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction over speech recognition backends.
 *
 * Implementations must be safe to call from a coroutine on any dispatcher —
 * they handle their own threading internally (e.g. AndroidSpeechEngine
 * posts to the main looper as Android requires).
 *
 * Lifecycle:
 *   1. [initialize] — load model / acquire resources (may suspend)
 *   2. [startListening] / [stopListening] — toggle recording
 *   3. Collect [events] for live transcription results
 *   4. [release] — free resources when done
 */
interface SpeechEngine {

    /** Live stream of transcription events. Collectors receive events while listening. */
    val events: SharedFlow<TranscriptionEvent>

    /** Whether the engine's resources are loaded and ready to record. */
    val isReady: Boolean

    /** Whether the engine is actively recording audio. */
    val isListening: Boolean

    /**
     * Load the recognition model / allocate resources.
     * For Vosk this downloads or loads the model from disk.
     * For AndroidSpeechEngine this is a no-op.
     *
     * @throws SpeechEngineException if initialization fails.
     */
    suspend fun initialize()

    /**
     * Begin recording audio and emitting [TranscriptionEvent]s on [events].
     * Does nothing if already listening.
     */
    fun startListening()

    /**
     * Stop recording. A final [TranscriptionEvent.Final] is emitted with
     * any remaining buffered text before the flow goes quiet.
     * Does nothing if not listening.
     */
    fun stopListening()

    /**
     * Release all resources (model, audio recorder, etc.).
     * The engine cannot be used after this call without re-initializing.
     */
    fun release()
}

/** Thrown when a [SpeechEngine] operation fails. */
class SpeechEngineException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
