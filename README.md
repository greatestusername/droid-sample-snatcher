# droid-sample-snatcher

Android app: rolling capture of the device **master playback mix**, waveform editing with preview and export to WAV (with optional BPM in the filename when applicable).

## Build

1. Install [Android Studio](https://developer.android.com/studio) and Android SDK **35** (project uses `compileSdk` / `targetSdk` **35**).
2. Create `local.properties` at the repo root (see `local.properties.example`):

   ```properties
   sdk.dir=/path/to/Android/sdk
   ```

3. Build a debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

   Output: `app/build/outputs/apk/debug/app-debug.apk`

On a **device or emulator**, run instrumented UI tests (e.g. waveform empty-state smoke):

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Debugging (crashes and logs)

In **Android Studio**: open **View → Tool Windows → Logcat**. Pick your **device/emulator** and the **`com.samplesnatcher`** process (or **Show only selected application**). Use the **Log level** dropdown (**Error** filters noise) or search the filter box for **`FATAL EXCEPTION`**, **`AndroidRuntime`**, or your own tag. Stack traces for uncaught exceptions appear there; if the app disappears on a specific screen, reproduce the steps and scroll to the red **E** lines at the failure time.

## Documentation

| Document | Purpose |
|----------|---------|
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | **Versioned** product requirements and limitations |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Index to the above + UI skill |
| [.cursor/skills/mobile-realtime-ui/SKILL.md](.cursor/skills/mobile-realtime-ui/SKILL.md) | UI standards for real-time / control surfaces |
| [docs/no-autonomous-commits-rule.md](docs/no-autonomous-commits-rule.md) | Agents suggest commits; humans run `git commit` after review |

Requirement or behavior change? Update **`docs/REQUIREMENTS.md`** (semver + changelog) and align this README and other docs per [docs/requirements-maintenance-rule.md](docs/requirements-maintenance-rule.md).

## Behavior notes

- **Capture** uses **MediaProjection** + **AudioPlaybackCapture** (`RECORD_AUDIO`, foreground service type `mediaProjection`). Some sources may not appear in the buffer (DRM / policy); see in-app onboarding copy.
- **Buffer length** is configurable **60–90 s** (default **75 s**) under Settings; a new buffer size applies on the **next** capture start.
- **WAV export** opens the system **Save** / Storage Access dialog. The default name is still `{base}_BPM{n}_{yyyyMMdd}.wav` (or without the BPM segment when it does not apply); you pick the folder and can edit the name in that screen.
- **Editor waveform**: **pinch to zoom** and **drag to pan** (when zoomed) to inspect the buffer; start/end **sliders** still move the selection along the **entire** buffer (0…1), independent of zoom. **Preview** / **Stop** are separate controls; the orange **playhead** follows **elapsed time vs selection duration** (not `AudioTrack` head position).

## Status

Android application code lives under `app/`. Requirements remain authoritative in `docs/REQUIREMENTS.md`.
