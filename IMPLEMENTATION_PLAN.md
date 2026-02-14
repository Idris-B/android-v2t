# Voice2Text Android — Implementation Plan

## Overview

A mobile Android app (Kotlin) that captures speech via a hardware trigger, transcribes it in real time, and saves the result as a note. It supports two speech engines — Vosk (fully offline) and Android's built-in SpeechRecognizer (online/offline hybrid) — and lets the user choose which to use.

---

## Phase 1: Core Transcription Engine

**Goal:** Record audio from the microphone and produce text using either engine.

### 1A — Speech engine abstraction layer

Create an interface `SpeechEngine` that both engines implement, so the rest of the app never couples to a specific recognizer.

```
speech/
├── SpeechEngine.kt          # interface: start(), stop(), Flow<TranscriptionEvent>
├── VoskEngine.kt             # Vosk implementation
├── AndroidSpeechEngine.kt    # SpeechRecognizer implementation
└── EngineFactory.kt          # returns the user-selected engine
```

- `SpeechEngine` exposes a `Kotlin Flow<TranscriptionEvent>` where events are `Partial(text)`, `Final(text)`, or `Error(msg)`.
- `VoskEngine` wraps `org.vosk.android.SpeechService`. It needs a model downloaded to internal storage on first launch (we'll ship `vosk-model-small-en-us-0.15`, ~50 MB, downloaded at first-run or bundled as an asset).
- `AndroidSpeechEngine` wraps `android.speech.SpeechRecognizer`. Simpler setup, but depends on device/Google availability and is primarily online.
- `EngineFactory` reads the user preference (DataStore) and returns the right implementation.

**Why this works / risks:**
- The abstraction keeps trigger and storage code completely engine-agnostic.
- Vosk's Android SDK (`com.alphacephei:vosk-android:0.3.47`) is well-maintained and provides `SpeechService` that handles AudioRecord internally, so we don't need low-level audio code.
- `SpeechRecognizer` must run on the main thread (Android requirement) — the wrapper will handle thread-hopping with coroutine dispatchers.

### 1B — Foreground service

Transcription must continue if the screen turns off or the user switches apps.

- `TranscriptionService` (already declared in manifest) will be a bound + started foreground service with type `microphone`.
- Posts a persistent notification ("Listening…") while active. Required for Android 14+ foreground service rules.
- The Activity binds to the service to observe the `Flow<TranscriptionEvent>` and display partial results in a live text view.

### 1C — Model management (Vosk)

- On first launch (or when user switches to Vosk), check if model exists in `filesDir/vosk-model/`.
- If missing, download from `alphacephei.com` in a `WorkManager` job with progress notification.
- Provide a "Download Model" button in settings with status feedback.

---

## Phase 2: Trigger / Hotkey System

**Goal:** Start and stop recording from a physical button, even when the app is in the background.

### Trigger options (ranked by feasibility)

| Trigger | How it works | Background? | Limitations |
|---|---|---|---|
| **Volume button (double/triple press)** | `AccessibilityService` intercepts key events | Yes | Requires user to enable the Accessibility Service in device settings. Can conflict with system volume. |
| **Bluetooth media button** | `MediaSession` callback receives `KEYCODE_MEDIA_*` | Yes, via MediaSession | Requires active MediaSession. Works well with BT headset buttons. |
| **Quick Settings tile** | Custom `TileService` in notification shade | Yes | Two taps (pull shade + tap) — less instant, but very reliable and no special permissions. |
| **Notification action buttons** | Start/Stop buttons in persistent notification | Yes | Requires notification to be visible. Clean UX. |
| **Shake gesture** | Accelerometer via `SensorManager` | Only with foreground service | Battery drain if always listening. |

**Recommended first implementation:** Bluetooth media button + Quick Settings tile + notification actions. These three cover the most use cases with no special permissions beyond what's already needed.

Volume-button triple-press via AccessibilityService is powerful but carries UX friction (the user must manually enable it in Settings → Accessibility) and Google Play reviewers scrutinize Accessibility Service usage. We'll add it as an opt-in later.

### Architecture

```
trigger/
├── TriggerManager.kt            # registers/unregisters active triggers
├── MediaButtonTrigger.kt        # MediaSession-based BT button
├── QSTileTrigger.kt             # Quick Settings tile
└── NotificationActionTrigger.kt # notification start/stop buttons
```

`TriggerManager` listens to user preferences and activates the selected triggers. Each trigger simply calls `TranscriptionService.startListening()` / `stopListening()` via a bound service reference or broadcast intent.

**Why this works / risks:**
- `MediaSession` callbacks survive background — Android keeps them alive for media control.
- Quick Settings tiles work on Android 7+ (our min is 8/API 26) with no extra permissions.
- No risk of side effects: each trigger is independently registered and only calls into the existing service.

---

## Phase 3: Notes Storage

**Goal:** When transcription finishes, save the text as a note in a user-chosen folder.

### 3A — Internal note format

Notes are plain `.txt` or `.md` files:
```
notes/2026-02-13_14-30-00.md
```
with a simple structure:
```markdown
# Voice Note — Feb 13, 2026 2:30 PM

<transcribed text here>
```

### 3B — Storage location

- Default: app-private `filesDir/notes/`.
- User can pick a folder via Android's `Storage Access Framework` (SAF) — `ACTION_OPEN_DOCUMENT_TREE`. The returned URI is persisted with `takePersistableUriPermission()` so it survives reboots.
- When the user chooses an external folder, notes are written there using `DocumentFile.createFile()`.

```
notes/
├── NoteRepository.kt     # save/load/list notes
├── NoteEntity.kt          # data class: id, title, text, timestamp, filePath
└── StorageManager.kt      # handles SAF URI persistence, folder selection
```

### 3C — Notes list UI

A simple `RecyclerView` showing saved notes sorted by date. Tap to view full text, long-press to delete (with confirmation).

**Why this works / risks:**
- SAF is the official Android way to let users pick arbitrary folders and persists across reboots — no `MANAGE_EXTERNAL_STORAGE` permission needed.
- Plain text files are the simplest format and easiest to integrate with other apps later (they're readable by any notes app).
- No database needed for v1 — the file system is the source of truth. If we later need search or metadata, we can add Room.

---

## Phase 4: Settings & Preferences

```
settings/
├── SettingsActivity.kt
└── PreferencesRepository.kt   # DataStore wrapper
```

User-configurable options:
- **Speech engine:** Vosk (offline) / Android (online) — dropdown
- **Active triggers:** checkboxes for each trigger type
- **Notes folder:** "Choose folder" button → SAF picker
- **Auto-punctuation:** on/off (Vosk model dependent)
- **Language:** model selection (future, Vosk supports multiple models)

---

## Phase 5: Future — External App Integration

This is explicitly out of scope for v1 but worth designing toward:

- **Share intent:** After saving a note, offer Android's share sheet so the user can send it to any app (Google Keep, Notion, Obsidian, etc.).
- **Content Provider:** Expose notes via a `ContentProvider` so other apps can query them.
- **Tasker/Automate plugin:** Expose an intent API so automation apps can trigger recording.
- **REST/local API:** A lightweight local HTTP server or Unix socket for IPC with specific apps (advanced).

The plain-text file format and SAF folder choice already make notes accessible to other apps. Share intent is the natural next step.

---

## Proposed Package Structure

```
com.voice2text.android/
├── ui/
│   ├── MainActivity.kt          # main screen: live transcript + note list
│   ├── NoteDetailActivity.kt    # view a saved note
│   ├── SettingsActivity.kt      # preferences
│   └── adapter/
│       └── NotesAdapter.kt      # RecyclerView adapter
├── service/
│   └── TranscriptionService.kt  # foreground service
├── speech/
│   ├── SpeechEngine.kt          # interface
│   ├── VoskEngine.kt
│   ├── AndroidSpeechEngine.kt
│   └── EngineFactory.kt
├── trigger/
│   ├── TriggerManager.kt
│   ├── MediaButtonTrigger.kt
│   ├── QSTileTrigger.kt
│   └── NotificationActionTrigger.kt
├── notes/
│   ├── NoteRepository.kt
│   ├── NoteEntity.kt
│   └── StorageManager.kt
└── settings/
    └── PreferencesRepository.kt
```

---

## Implementation Order

| Step | What | Depends on |
|------|------|------------|
| 1 | `SpeechEngine` interface + `VoskEngine` | — |
| 2 | `TranscriptionService` (foreground, with Vosk) | Step 1 |
| 3 | MainActivity UI: start/stop button + live transcript display | Step 2 |
| 4 | `AndroidSpeechEngine` + engine toggle in settings | Step 1 |
| 5 | `NoteRepository` + `StorageManager` (save notes on stop) | Step 2 |
| 6 | Notes list UI + NoteDetailActivity | Step 5 |
| 7 | Trigger system: notification actions | Step 2 |
| 8 | Trigger system: Quick Settings tile | Step 2 |
| 9 | Trigger system: Bluetooth media button | Step 2 |
| 10 | Settings screen (engine, triggers, folder) | Steps 4, 5, 7-9 |
| 11 | Vosk model download manager | Step 1 |
| 12 | Polish: permissions flow, error handling, edge cases | All |

---

## Tech Stack Summary

| Component | Choice | Reason |
|-----------|--------|--------|
| Language | Kotlin | Modern Android standard, coroutine support |
| Min SDK | 26 (Android 8) | Covers 95%+ of active devices, needed for foreground service types |
| UI | View Binding + Material Components | Simple, well-documented, no Compose learning curve |
| Async | Kotlin Coroutines + Flow | Clean reactive streams for transcription events |
| Speech (offline) | Vosk 0.3.47 | Same engine as desktop project, fully offline |
| Speech (online) | Android SpeechRecognizer | Built-in, no extra deps |
| Storage | SAF + plain text files | No special permissions, interoperable |
| Preferences | DataStore | Modern replacement for SharedPreferences |
| Background | Foreground Service | Required for ongoing mic access |
