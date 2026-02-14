package com.voice2text.android.speech

import android.content.Context

/**
 * Creates the appropriate [SpeechEngine] based on a preference string.
 *
 * This is intentionally a simple factory rather than dependency injection —
 * keeps things lightweight for a single-activity app. If the app grows,
 * this could be replaced with Hilt/Koin modules.
 */
object EngineFactory {

    const val ENGINE_VOSK = "vosk"
    const val ENGINE_ANDROID = "android"

    /**
     * Returns a new (un-initialized) [SpeechEngine] for the given [engineId].
     *
     * @param engineId One of [ENGINE_VOSK] or [ENGINE_ANDROID].
     * @param context Application context (safe to hold long-term).
     */
    fun create(engineId: String, context: Context): SpeechEngine {
        return when (engineId) {
            ENGINE_VOSK -> VoskEngine(context.applicationContext)
            ENGINE_ANDROID -> AndroidSpeechEngine(context.applicationContext)
            else -> {
                // Fall back to Android's built-in engine for unknown values.
                // This avoids a crash if prefs contain a stale/invalid value.
                AndroidSpeechEngine(context.applicationContext)
            }
        }
    }
}
