# Voice2Text Android

An Android app that captures speech via a hardware trigger (Bluetooth button, Quick Settings tile, or notification action), transcribes it using either Vosk (offline) or Android's built-in SpeechRecognizer, and saves the result as a note.

Forked from the desktop [voice-to-text](../voice%20to%20text/) Python project — reimagined for mobile use.

## Status

**Pre-alpha** — project scaffolding complete. See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the full roadmap.

## Features (planned)

- Real-time speech-to-text with live partial results
- Dual engine support: Vosk (fully offline, ~50MB model) and Android SpeechRecognizer (online)
- Hardware triggers: Bluetooth media button, Quick Settings tile, notification actions
- Notes saved as plain text/markdown files to a user-chosen folder
- Foreground service for background recording

## Requirements

- Android 8.0+ (API 26)
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17

## Building

Open the project in Android Studio and sync Gradle. Build with:

```bash
./gradlew assembleDebug
```

## Project Structure

```
voice2text-android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/voice2text/android/
│       │   ├── ui/MainActivity.kt
│       │   └── service/TranscriptionService.kt
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/ (colors, strings, themes)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── IMPLEMENTATION_PLAN.md
```

## Key Dependencies

- **Vosk** (0.3.47) — Offline speech recognition
- **AndroidX** — Core, AppCompat, Material, Lifecycle, ConstraintLayout
- **Kotlin Coroutines** — Async / reactive streams
- **DataStore** — Preferences storage

## License

See [LICENSE](LICENSE) file.
