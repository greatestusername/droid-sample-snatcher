---
title: Sample Snatcher — Product requirements
version: 1.3.16
last_updated: 2026-05-02
source_plan: android_sample_snatcher_71757450.plan.md
---

# Sample Snatcher — requirements

Changes to behavior or scope **must** update this document: bump **`version`** (semver), set **`last_updated`**, and add a line under **Changelog**. Maintenance workflow: [requirements-maintenance-rule.md](requirements-maintenance-rule.md) (install as `.cursor/rules/requirements-and-docs.mdc` per file header when using Agent mode).

## Development workflow (human-owned commits)

- **Agents do not run `git commit` / `git push`** unless you explicitly ask for that in the same instruction. After each logical unit of work, agents should **suggest** a commit message and `git add` scope for **you** to run after review. See [no-autonomous-commits-rule.md](no-autonomous-commits-rule.md) (install as `.cursor/rules/no-autonomous-commits.mdc` when using Agent mode).

## Goal

Continuously buffer recent audio from the device **master playback output mix** (typically **60–90 s**, default **75 s**). The user opens the app, selects a range on a **waveform**, **previews** (one-shot or loop), optionally uses **snapping**, and **exports** a **WAV** whose filename includes **user label**, **date**, and **BPM** when applicable.

## Functional requirements

### 1. Background capture

- Rolling **PCM** buffer; ring buffer or chunked storage; size for chosen sample rate/channels (e.g. 48 kHz stereo 16-bit ≈ **11.5 MB/min**). **Default duration: 75 s** (configurable **60–90 s** in app settings).
- **Foreground service** + persistent notification; start/stop per OS rules.
- First-run and settings explain capturability (see Limitations).

### 2. Save / editor view

- **Waveform** of full buffer (downsampled peaks); **pinch-zoom** and **pan** on the waveform strip for inspection (**display only** — selection remains normalized to the **full** buffer; sliders do not change meaning).
- **Selection**: draggable handles or brush; show duration; sample-accurate edges where feasible.
- **Snapping** (toolbar toggles): **zero-crossing**; **transient** (onset/energy peaks); optional advanced tuning.
- **Preview**: separate **Preview** and **Stop** buttons; optional **Loop preview** switch (**on** by default). Live **playhead** uses **wall-clock elapsed vs clip duration** ([PreviewPlayheadMath], independent of `AudioTrack.getPlaybackHeadPosition`); **compose coroutine** calls `tickPlayhead` ~60 Hz while playing.
- **Export**: uncompressed **WAV**; filename e.g. `{userLabel}_BPM{nnn}_{YYYYMMDD}.wav` when loop + BPM confidence OK; else omit BPM segment. User picks **folder and filename** via the system **Save / Storage Access** dialog; the suggested name includes label + optional BPM + date; write goes to the returned **content URI**. Opening the save flow **stops editor preview** if it is playing.

### 3. BPM (loops)

- On export or explicit analyze: **on-device** tempo estimation; show **confidence**; low confidence → omit BPM or mark estimate in UI/filename per policy.
- User **tap tempo** override while loop plays.

## Non-functional

- Editor opens quickly (~1–2 s target); waveform may progressive-render.
- Controls feel **near–real-time** where reasonable.
- Capture service recovers when possible; surface permission/revocation errors.
- **Privacy**: no cloud in v1; audio leaves device only on user export.
- **Accessibility**: large touch targets; readable waveform contrast.

### Distribution and SDK (decided)

- **Shipping posture**: **Play-ready** — correct foreground service types, in-app disclosure for capture/`RECORD_AUDIO`, and Play **Data safety**–accurate behavior even if the artifact is also sideloaded for testing.
- **targetSdk** / **compileSdk**: **35** in the current Android module (meets **34+** product minimum); adjust with toolchain updates.
- **minSdk**: **29** (**decided**). **AudioPlaybackCapture** requires API **29**; choosing **29** keeps **Android 10–11** and does **not** omit capture features—only **minSdk 31** would drop older OS versions while slightly simplifying some **FGS** declarations on 12+ (optional tradeoff rejected for broader device support).
- **Distribution channel**: Primary intent **Google Play**; sideload remains possible but should not relax privacy or disclosure quality.

## Limitations (onboarding)

- **Intent**: **Master playback mix**, not per-app isolation (v1).
- **Android**: **AudioPlaybackCapture** exposes the **system playback mix** per policy; **DRM / non-capturable** sources may be **absent** from the buffer while still audible.
- **Android TV / third-party YouTube clients** (e.g. [SmartTube](https://github.com/yuliskov/SmartTube)): the app may declare `allowAudioPlaybackCapture` yet still be missing from the capture mix if playback uses **tunneled video** (hardware path) or **HDMI / SPDIF bitstream** (compressed passthrough) instead of **PCM** into the mixer. That is a **player/device path** limitation, not fixed by sideloading Sample Snatcher; users can try **disabling tunneled playback** in the player’s tweak settings and preferring **PCM / stereo** output so audio enters the normal mix—**no APK patch** to the other app is required, only settings.
- **MediaProjection** (or equivalent) may be required; consent can be revoked.
- **Bluetooth / output routing**: BT receives the **same routed mix** as speakers; capture does not read “from Bluetooth.” Routing to BT **does not** bypass capture restrictions.

## UI design standard (repo)

All **Compose (and other UI) work** for capture controls, editor transport (play/pause/loop), **sliders/scrubbers**, timing readouts, and **waveform interaction** MUST:

1. **Read** [.cursor/skills/mobile-realtime-ui/SKILL.md](../.cursor/skills/mobile-realtime-ui/SKILL.md) and apply **[design-intake.md](../.cursor/skills/mobile-realtime-ui/design-intake.md)** before substantial UI implementation.
2. Follow **[accessibility.md](../.cursor/skills/mobile-realtime-ui/accessibility.md)** and **[realtime-controls.md](../.cursor/skills/mobile-realtime-ui/realtime-controls.md)** for gestures, latency, and continuous controls (**vertical drag default** for scrubbers/sliders where applicable).
3. Use **[platforms.md](../.cursor/skills/mobile-realtime-ui/platforms.md)** for Compose vs Views choices.

Generic static settings-only screens may use Material defaults without the full intake workflow.

## Technical notes (summary)

- **Capture**: `AudioRecord` + `AudioPlaybackCaptureConfiguration`; sample rate follows `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` (mix alignment); foreground service types per target SDK.
- **Editor**: preview via `AudioTrack` (stream); playhead position for UI = **wall clock vs selection duration** (not `getPlaybackHeadPosition` for stream preview).
- **Snapping**: zero-cross search ±N samples; transient via envelope/spectral flux peaks.
- **BPM**: autocorrelation / lightweight estimator; run on export or explicit action.

## Changelog

- **1.3.16** (2026-05-02): **Capture reliability (Samsung / One UI)** — `AudioPlaybackCaptureConfiguration` matchers reduced to **MEDIA + GAME + UNKNOWN** (extra usages caused `UnsupportedOperationException: could not register audio policy` on some devices). **Defer** `AudioRecord` `build()` by **200 ms** on the main looper after `getMediaProjection` to avoid audio-policy race after the system consent dialog. **Catch** `UnsupportedOperationException` / `SecurityException` during `build()` with user-visible `lastError` and clean teardown. **`START_NOT_STICKY`** for the capture service so the OS does not **restart** with a **stale** projection intent (which produced `SecurityException` on `startForeground` in a new process).
- **1.3.15** (2026-05-02): **Revert** — removed capture **diagnostics** (`CaptureUiState` telemetry, Settings card, log spam); restore simple capture loop only (fixes post-permission instability reported after 1.3.14).
- **1.3.14** (2026-05-02): **Capture diagnostics** — non-obtrusive telemetry on `CaptureUiState` (`lastPeakDb`, `lastReadBytes`, `totalBytes`, `zeroBufferStreak`, `sampleRateHz`); throttled to ~5 Hz from the capture thread; `Log.i("SnatcherCapture", …)`. **Settings** screen shows a small **Capture diagnostics** block (sample rate, last peak, zero-buffer streak, total KB) — Home screen unchanged. Helps diagnose silent capture (e.g. SmartTube on Android TV) without changing user-facing flow. **Superseded by 1.3.15** (reverted in tree).
- **1.3.13** (2026-05-02): **Capture** — ring buffer + `AudioRecord` use **device mixer sample rate** (`PROPERTY_OUTPUT_SAMPLE_RATE`); widen **usage** matchers (`VOICE_COMMUNICATION`, `ASSISTANT`, `VIRTUAL_SOURCE` on Q+). **Docs / onboarding** — Android TV players (e.g. SmartTube): tunneled playback / bitstream vs PCM mix; settings-only workaround.
- **1.3.12** (2026-05-02): **Playhead** — **time-based** (`PreviewPlayheadMath`: elapsed / duration, loop wraps with `mod`); UI **`LaunchedEffect` + `tickPlayhead` ~60 Hz**; **unit tests** `PreviewPlayheadMathTest`. **Preview** + **Stop** split again; **Stop** sets **`_isPlaying` synchronously** (no `Handler` post) so playback stops immediately. **Stop** no longer `enabled`-gated.
- **1.3.11** (2026-05-02): **Preview** — **one-shot** waits after the last **`write`** until **`playbackHeadPosition`** reaches end (was releasing **AudioTrack** immediately → silence). **Playhead** uses **`playbackHeadPosition`** (loop: `head % fc`) + **Main `Handler`** posts for Compose. **Loop preview** default **on**.
- **1.3.10** (2026-05-02): **Preview** — **playhead** driven by **`playheadFraction` `StateFlow`** (writer thread updates; fixes static line). **Stop** calls **`AudioTrack.pause()` + `flush()`** so blocked **`write()`** returns; worker still **release**s in `finally`. Removed clearing playhead data before the worker joined.
- **1.3.9** (2026-05-02): **Preview** — **playhead** (orange line) on waveform during playback; **Preview**/**Stop** on one button + **Loop preview** switch; **`PreviewPlayer`** exposes **`StateFlow` playback** and frame-accurate **playhead** (fixes overlapping previews). Empty selection shows a toast.
- **1.3.8** (2026-05-02): **Waveform** — **pinch-zoom** and horizontal **pan** (`detectTransformGestures`) over a sliding normalized window; selection mapping unchanged (0…1 = full buffer).
- **1.3.7** (2026-05-02): **Export** — **PreviewPlayer** is **stopped** when export starts so audio does not continue during the system save dialog.
- **1.3.6** (2026-05-02): **Export** — **CreateDocument** (system save UI): user chooses **location** and can edit the **suggested** name; default name is still **`{label}_BPM{n}_{yyyyMMdd}.wav`** or **`{label}_{yyyyMMdd}.wav`**. **`WavExport.writeWavToUri`** writes PCM to the chosen URI (no silent **Music/SampleSnatcher** insert-only path).
- **1.3.5** (2026-05-02): **Editor** — while the editor route is visible, capture **discards** incoming PCM so the **ring buffer does not advance** (`CaptureAudioService.setCapturePaused`); disposal resumes writes. **Transient snap** reworked (adaptive block size, denser flux grid, fallback onset via block RMS delta); **transient applies before zero-cross** when both are on. Editor body is **vertically scrollable** so export and controls fit one screen. Waveform refresh slows when buffer is frozen.
- **1.3.4** (2026-05-02): **WaveformCanvas** — empty peak buffer (`FloatArray(0)` before first envelope compute) no longer indexes **`peaks[0]`** (`ArrayIndexOutOfBoundsException`). Bars iterate **`peaks.indices`** only; slot width uses **`layoutSlots`**. **Instrumented** `WaveformCanvasTest` (Compose smoke: empty + filled peaks).
- **1.3.3** (2026-05-02): Editor crash fix — **atomic** `PcmRingBuffer.getFrameWindow()` for selection math; avoid **`coerceIn` with inverted bounds** when oldest/newest were read inconsistently across threads; use **`coerceAtLeast` / `coerceAtMost`** only; waveform loop catches **Throwable**; **applySnapping** uses one **framesFromSelection** pass + consistent window for slider ratios.
- **1.3.2** (2026-05-02): Editor stability — `computePeakEnvelope` off main thread; try/catch in waveform loop; **PcmRingBuffer** synchronized reads/writes with consistent frame snapshots (avoids races / ANR when opening editor). Editor route uses **captureService** directly.
- **1.3.1** (2026-05-02): UX — explain Android **MediaProjection** system dialog (audio capture uses the same consent as screen capture; app does not encode/send screen video). Reliability — foreground service must **startForeground** immediately; avoid **stopForeground** during capture restart setup (fixes crashes). In-app explainer dialog before permission flow.
- **1.3.0** (2026-05-02): Initial **Android app** module (`:app`) — playback capture + ring buffer, editor (waveform, range, snap, preview, WAV export, BPM / tap tempo). `README.md` updated. Use `local.properties` / `local.properties.example` for `sdk.dir`.
- **1.2.1** (2026-05-02): **Frozen** default buffer **75 s** (60–90 s range). Cursor rules `requirements-and-docs.mdc` and `no-autonomous-commits.mdc` added under `.cursor/rules/`.
- **1.2.0** (2026-05-02): **Decided** Play-ready default, **minSdk 29**, **targetSdk 34+**; rationale for 29 vs 31.
- **1.1.0** (2026-05-02): Development workflow — no autonomous commits; suggest reviewed commits. Distribution/SDK guidance recorded in project plan (Play vs sideload tradeoffs; targetSdk 34+; minSdk 29 vs 31).
- **1.0.0** (2026-05-02): Initial document from project plan; UI skill mandate; limitations including BT FAQ.
