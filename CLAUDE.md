# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

Android app (Kotlin) that transcribes speech to text and saves it as notes. Inspired by the desktop Python "voice to text" project in the sibling directory. Uses either Vosk (offline, ~50 MB model) or Android SpeechRecognizer (online/Google) for recognition, configurable in settings.

## Build Commands

```bash
# Build debug APK (requires Android SDK with API 34)
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug
```

No test framework is configured yet. No linter is configured.

## Architecture

```
app/src/main/java/com/voice2text/android/
├── speech/                     # Speech engine abstraction layer
│   ├── SpeechEngine.kt         # Interface: initialize, startListening, stopListening, events flow
│   ├── TranscriptionEvent.kt   # Sealed class: Partial, Final, Error
│   ├── VoskEngine.kt           # Offline engine wrapping Vosk SpeechService
│   ├── AndroidSpeechEngine.kt  # Online engine wrapping Android SpeechRecognizer
│   ├── EngineFactory.kt        # Factory to create engines by ID string
│   └── VoskModelManager.kt     # Downloads/extracts Vosk model with progress notifications
├── service/
│   └── TranscriptionService.kt # Foreground service (microphone type) — owns engine lifecycle
├── notes/
│   ├── NoteEntity.kt           # Data class: title, text, timestamp, filePath
│   └── NoteRepository.kt       # Save/list/read/delete notes (internal storage or SAF folder)
├── settings/
│   └── PreferencesRepository.kt # DataStore-backed preferences
├── trigger/
│   ├── TriggerManager.kt       # Coordinates trigger activation
│   ├── MediaSessionTrigger.kt  # Captures Bluetooth headset button via MediaSession
│   ├── MediaButtonReceiver.kt  # BroadcastReceiver fallback for media button events
│   └── TranscriptionTileService.kt # Quick Settings tile toggle
└── ui/
    ├── MainActivity.kt         # Main screen: record button, live transcript, notes list
    ├── NoteDetailActivity.kt   # Full note view with share/copy/delete
    ├── SettingsActivity.kt     # Engine, storage, triggers configuration
    └── adapter/
        └── NotesAdapter.kt     # RecyclerView adapter for notes list
```

### Key patterns

- **Speech engines** implement `SpeechEngine` interface, emit `TranscriptionEvent` via `SharedFlow`
- **TranscriptionService** is a bound+started foreground service; exposes state via `StateFlow`
- **Notes** are saved as `.md` files, either in internal storage or a SAF-selected folder
- **Triggers**: Quick Settings tile (always available), Bluetooth media button (opt-in), notification actions (while recording)
- **Preferences** use Jetpack DataStore (not SharedPreferences)

### Tech stack

- Kotlin, minSdk 26, targetSdk 34, compileSdk 34
- AndroidX (AppCompat, ConstraintLayout, Lifecycle, DataStore, DocumentFile)
- Material Components for Android
- Vosk Android SDK 0.3.47
- Coroutines + Flow for async/reactive patterns
- View Binding for type-safe UI access

## Conventions

- Package: `com.voice2text.android`
- Android-standard import ordering (android → androidx → com → kotlinx → java)
- KDoc comments on public classes and non-obvious functions
- Sealed classes for state machines and events
- `companion object` for constants used across classes
