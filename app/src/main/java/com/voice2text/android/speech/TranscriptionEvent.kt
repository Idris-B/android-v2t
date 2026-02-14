package com.voice2text.android.speech

/**
 * Events emitted by a [SpeechEngine] during transcription.
 *
 * The flow is: zero or more [Partial] → one [Final] per utterance,
 * with [Error] possible at any point.
 */
sealed class TranscriptionEvent {
    /** Intermediate result that may change as more audio arrives. */
    data class Partial(val text: String) : TranscriptionEvent()

    /** Confirmed result for a completed utterance. */
    data class Final(val text: String) : TranscriptionEvent()

    /** Something went wrong — engine-specific message. */
    data class Error(val message: String) : TranscriptionEvent()
}
