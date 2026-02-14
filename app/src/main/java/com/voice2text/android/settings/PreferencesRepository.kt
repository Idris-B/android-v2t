package com.voice2text.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Singleton DataStore instance scoped to the application. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Centralized access to all user preferences via Jetpack DataStore.
 *
 * Each preference is exposed as a [Flow] for reactive observation and
 * a suspend setter for writes. DataStore handles threading and disk I/O.
 */
class PreferencesRepository(private val context: Context) {

    companion object {
        // ── Keys ─────────────────────────────────────────────────────────
        val SPEECH_ENGINE = stringPreferencesKey("speech_engine")
        val NOTES_FOLDER_URI = stringPreferencesKey("notes_folder_uri")
        val BLUETOOTH_TRIGGER_ENABLED = booleanPreferencesKey("bluetooth_trigger_enabled")
        val SAVE_AUDIO = booleanPreferencesKey("save_audio_recording")
        val BT_TRIGGER_MODE = stringPreferencesKey("bt_trigger_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
    }

    // ── Speech engine preference ────────────────────────────────────────

    /** Which speech engine to use: "vosk" or "android". Defaults to "android". */
    val speechEngine: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SPEECH_ENGINE] ?: "android"
    }

    suspend fun setSpeechEngine(engine: String) {
        context.dataStore.edit { prefs ->
            prefs[SPEECH_ENGINE] = engine
        }
    }

    // ── Notes folder preference ─────────────────────────────────────────

    /**
     * Persisted SAF URI for the user-chosen notes folder.
     * Null means "use internal app storage" (the default).
     */
    val notesFolderUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[NOTES_FOLDER_URI]
    }

    suspend fun setNotesFolderUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[NOTES_FOLDER_URI] = uri
            } else {
                prefs.remove(NOTES_FOLDER_URI)
            }
        }
    }

    // ── Bluetooth trigger preference ────────────────────────────────────

    /** Whether the Bluetooth media button trigger is enabled. Defaults to false. */
    val bluetoothTriggerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BLUETOOTH_TRIGGER_ENABLED] ?: false
    }

    suspend fun setBluetoothTriggerEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BLUETOOTH_TRIGGER_ENABLED] = enabled
        }
    }

    // ── Save audio recording preference ──────────────────────────────

    /** Whether to save audio recordings alongside notes. Defaults to false. */
    val saveAudioRecording: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SAVE_AUDIO] ?: false
    }

    suspend fun setSaveAudioRecording(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SAVE_AUDIO] = enabled
        }
    }

    // ── Bluetooth trigger mode preference ────────────────────────────

    /** BT button behavior: "toggle" (press to start/stop) or "hold" (hold to record). */
    val btTriggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BT_TRIGGER_MODE] ?: "toggle"
    }

    suspend fun setBtTriggerMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[BT_TRIGGER_MODE] = mode
        }
    }

    // ── App theme preference ──────────────────────────────────────────

    /** Selected color theme: "light", "dark", "amoled", or "sepia". Defaults to "light". */
    val appTheme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[APP_THEME] ?: "light"
    }

    suspend fun setAppTheme(theme: String) {
        context.dataStore.edit { prefs ->
            prefs[APP_THEME] = theme
        }
    }
}
