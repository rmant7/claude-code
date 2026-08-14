# Whisper Transcriber (Android)

An Android app that transcribes audio and video files on-device using [OpenAI Whisper](https://github.com/openai/whisper)
via [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Whisper models are **not** bundled with the app — you pick a
model size and download it on demand from Hugging Face the first time you need it.

## How it works

1. **Pick a model size** — Tiny, Base, Small, Medium and Large v3 Turbo, each also offered as a **quantized**
   variant (q5_1/q5_0) that's roughly a third of the full-precision file size and noticeably faster to run on a
   phone CPU, at a small accuracy cost — worth trying first if a full-precision model feels too slow. The choice is
   remembered across launches (`Activity#getPreferences()`), so picking the same model every time (typically the
   largest one downloaded) doesn't mean re-picking it on every app open. Tap
   **Download model**. The download runs in `ModelDownloadService`, a **foreground service**
   with a progress notification, not a plain Activity-scoped coroutine — large models can take many minutes on a
   mobile connection, and a background download without a foreground service gets starved by Doze/App Standby
   network restrictions the moment the screen locks, which was causing big models to silently stall or never
   finish. The file streams into a `.part` temp file, resumable via HTTP `Range` requests after a dropped
   connection (retrying with backoff as long as forward progress keeps being made), is verified against the
   expected byte count, and is only then promoted to its final name. It lands in the app's private external storage
   (`Android/data/com.whispertranscriber.app/files/models`), so no storage permission is required. If a model ever
   fails to load, the app deletes the bad file automatically so you can just tap **Download model** again.
2. **Choose an input source**:
   - **File** — pick a single audio/video file with the system file picker.
   - **URL** — paste a direct link to a remote audio/video file; the app downloads it to a temp file first.
   - **Folder** — pick a directory (via Storage Access Framework); the app recursively scans it for every
     recognized audio/video file and queues them all.
3. Tap **Transcribe**. The whole batch runs in `TranscriptionService`, a **foreground service** with a progress
   notification — same reasoning as the model download service: a batch tied only to the Activity's lifecycle was
   getting killed by the OS once the app was backgrounded for a while, which for a quick single file went unnoticed
   but silently lost hours of progress on an overnight multi-hundred-file folder batch, with nothing to show for it
   afterwards. Every finished transcript is also written to disk immediately as its own `.txt` file (plus a combined
   `all_transcripts.txt`) under `Android/data/com.whispertranscriber.app/files/transcripts/<timestamp>/`, so even in
   the worst case — the process gets killed anyway — whatever finished before that point is safely on disk rather
   than lost. Reopening the app re-attaches to the service's live progress (or its finished results) instead of
   showing a blank slate. For every queued item the app:
   - Extracts and decodes the audio track using Android's built-in `MediaExtractor`/`MediaCodec` — this works for
     plain audio containers (mp3, wav, m4a/aac, flac, ogg/opus, amr, …) as well as video containers (mp4, mov, 3gp,
     webm, mkv, ts, …), since only the audio track is selected and video frames are never touched.
   - Downmixes to mono and resamples to 16 kHz, the format whisper.cpp expects.
   - Runs inference on-device through a small JNI bridge (`app/src/main/cpp/whisper_jni.cpp`) around whisper.cpp's C
     API. Total CPU budget for one `transcribe()` call is capped at 4 cores (`Transcriber.kt`) rather than the
     device's full core count — pinning every core at 100% for the many sustained minutes a long file needs is
     exactly the load pattern that triggers mobile thermal throttling, and a user saw a several-minute call
     recording still under 50% transcribed after multiple hours, orders of magnitude slower than expected and
     consistent with the phone clocking itself down progressively the longer it ran flat-out. That budget is split
     between real chunk-level parallelism (`whisper_full_parallel()`, splitting one file's audio across native
     threads) and per-chunk thread count, but *only* when the file is long enough that every chunk still gets a
     useful amount of audio (currently ≥30s/chunk, see `computeParallelism()` in `Transcriber.kt`) — very short
     clips fall back to a single `whisper_full()` call. This guards against the original edge case that motivated
     briefly forcing single-processor mode everywhere: a suspected hang on a very short clip, plausible as
     degenerate near-empty chunks. That blanket single-processor fix was reverted because it traded an *unconfirmed*
     hang for a *confirmed*, severe slowdown (CI measured a 4-second clip on the tiny model exceeding a 120-second
     budget under `whisper_full()` alone) — real parallelism is also the whole point for the "dozens/hundreds of
     files in a couple of hours" use case this app targets. The safety net this time is
     [abort_callback](#stopping-a-running-batch) rather than avoiding parallelism altogether. `TranscriptionEndToEndTest`
     (see Testing below) exists to catch a hang like that before it ships again, and `TranscriberParallelismTest`
     covers the chunk-count math on the JVM.

     A likely bigger factor, found by reading ggml's own CMake logic: with `GGML_NATIVE OFF` and no ARM arch set
     explicitly, ggml appends **no** `-march` flags at all for the arm64-v8a build — it silently falls back to the
     compiler's baseline `armv8-a`, without `dotprod`/`fp16`, which whisper.cpp's quantized matmul kernels (the
     dominant cost of inference) lean on heavily. `app/src/main/cpp/CMakeLists.txt` now sets
     `GGML_CPU_ARM_ARCH="armv8.2-a+dotprod+fp16"` — supported by essentially every Android device from ~2018
     onward, and read only inside ggml's ARM-specific CMake branch, so it doesn't touch the x86_64 CI build.
     **Caveat:** a device older than ~2017 (ARMv8.0, no dotprod) would crash with `SIGILL` running this build; the
     upstream-intended fix for that (build several CPU variants and pick the right one at runtime via
     `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`) needs restructuring to shared native libs and a runtime
     backend-loading call, not attempted here. This is also the one piece of this whole performance story that CI
     structurally *can't* verify — the emulator is x86_64, so this flag never executes there; real transcription
     speed on real ARM hardware can only be confirmed by testing on-device.
     The model is loaded **once** and reused across every file in a
     batch — this is also why files aren't transcribed several-at-once: each loaded whisper.cpp context holds the
     model weights in RAM (e.g. ~1.5 GB for Medium), so N files running fully in parallel would mean N full model
     loads, which would OOM on a typical phone for anything above Tiny/Base.
   - In a multi-file batch, the next file's audio is decoded in the background while the current file is being
     transcribed (a one-item lookahead), so decode time is fully hidden for every file but the first — decoding
     via `MediaCodec` is normally much faster than whisper.cpp inference, so this (rather than chunking a single
     file's audio) is where pipelining actually saves wall-clock time.
4. Progress is visible at several levels: a "Loading model…" header while a (possibly large) model file is being
   read off disk, an overall "File *N* of *M*" bar for the batch, a live percentage on the card of whichever file
   is actively being processed (decode progress from the container's reported duration, transcription progress
   from whisper.cpp's own `progress_callback`) — and the transcript itself streams in live, segment by segment, via
   whisper.cpp's `new_segment_callback`, instead of only appearing once the whole file is done. Model loading and
   decode progress are published to the UI's `StateFlow` as they happen (`TranscriptionService.publish()`/
   `decodeSource()`'s `onUpdate` callback) — an earlier version mutated the shared progress fields during those two
   phases without ever calling `publish()`, so the UI just sat on "Pending" with no visible activity until
   transcription itself started, however long model load/decode actually took.
5. Each result appears as its own card (filename, status, live/finished transcript) as soon as it's ready;
   **Copy all** / **Share all** combine every finished transcript into one block of text.

### Stopping a running batch

A **Stop** button appears next to **Transcribe** while a batch is running. It does two things at once:
calls `Transcriber.cancel()`, which sets an atomic flag checked by whisper.cpp's `abort_callback` during inference
(no JNI env upcall needed — see `WhisperHandle` in `whisper_jni.cpp`), interrupting the file that's transcribing
*right now* rather than waiting for it to finish; and sets a stop-requested flag (`TranscriptionControl`) that
stops the batch loop from starting the next file. The interrupted file keeps whatever partial transcript it had
streamed in so far (it's accumulated incrementally via `new_segment_callback` as inference runs, not rebuilt from
whisper.cpp's return value afterwards, which comes back empty on an aborted call) and is marked **Stopped** rather
than *Done* or *Failed*; any files that hadn't started yet are marked **Stopped** too and left untouched on disk.
There's no true pause/resume — whisper.cpp's inference call is synchronous with no mid-call suspension point to
resume from, so once stopped a file has to be re-transcribed from the start if you want the rest of it.

### Browsing saved transcripts

**View saved transcripts** on the main screen opens a flat, most-recent-first list of every `.txt` file under
`Android/data/com.whispertranscriber.app/files/transcripts/**` (both per-file transcripts and each batch's
combined `all_transcripts.txt`), independent of whatever's currently loaded in the results list on the main
screen. Tapping a file shows its full text with **Share**/**Close**.

## Project layout

- `app/src/main/java/com/whispertranscriber/app/`
  - `MainActivity.kt` — UI: starts a batch on `TranscriptionService` and observes `TranscriptionState`/
    `ModelDownloadState` to reflect whatever those foreground services are doing, rather than running any of that
    work itself.
  - `ModelDownloadService.kt` / `ModelDownloadState.kt` — foreground service that owns the model download (so it
    survives backgrounding) plus the in-process `StateFlow` the Activity observes for progress/completion.
  - `TranscriptionService.kt` / `TranscriptionState.kt` — foreground service that owns the whole transcription
    batch (decode-ahead pipelining, whisper.cpp inference, writing finished transcripts to disk) plus the
    `StateFlow` the Activity observes; this is what survives the app being backgrounded overnight.
  - `TranscriptionControl.kt` — lets the UI ask a running batch to stop: an atomic stop-requested flag checked
    between files, plus a reference to whichever `Transcriber` is active so Stop can interrupt the file that's
    transcribing right now instead of only taking effect between files.
  - `ModelManager.kt` — Whisper model download/storage; streams to a temp file, resumes via HTTP `Range` requests
    after a dropped connection, and only promotes the file once its full size is verified.
  - `UrlDownloader.kt` — streams a remote URL to a temp file for the URL mode.
  - `MediaFileUtils.kt` — recognizes media file extensions and recursively scans a picked folder.
  - `AudioDecoder.kt` — audio/video → 16 kHz mono float PCM decoding, with per-file progress from the container's
    duration.
  - `MediaSource.kt` / `TranscriptionResult.kt` / `ResultsAdapter.kt` — the batch queue and its results list.
  - `WhisperLib.kt` / `Transcriber.kt` — Kotlin side of the JNI bridge, including the progress/live-segment
    callbacks and the chunk/thread-count math (`computeParallelism`) for `whisper_full_parallel()`.
  - `TranscriptsActivity.kt` / `TranscriptFilesAdapter.kt` — browses already-written transcript `.txt` files.
- `app/src/main/cpp/`
  - `CMakeLists.txt` — fetches whisper.cpp source via CMake `FetchContent` and builds it for Android (arm64-v8a,
    x86_64) with native CPU-detection disabled, since we're cross-compiling.
  - `whisper_jni.cpp` — the native `WhisperLib` implementation, calling whisper.cpp's public C API
    (`whisper_init_from_file_with_params`, `whisper_full`/`whisper_full_parallel`, `whisper_full_get_segment_text`,
    …) and bridging its `progress_callback`/`new_segment_callback`/`abort_callback` back to Kotlin. The Long
    "context pointer" Kotlin holds actually points at a small `WhisperHandle` wrapping the real
    `whisper_context*` alongside an `std::atomic<bool>` cancel flag, so `WhisperLib.requestCancel()` can interrupt
    an in-flight call from another thread without a JNI env upcall.

## Testing

Two test suites gate every CI build; the APK artifact is only uploaded if both pass.

- **Unit tests** (`app/src/test/`, run via `gradle testDebugUnitTest`) — plain JVM tests, no device
  needed, covering pure logic: media file extension matching (`MediaFileUtilsTest`), the Whisper
  model catalog's integrity — unique ids/filenames, well-formed URLs (`WhisperModelTest`), the PCM
  downmix/resample math (`AudioDecoderTest`), HTTP `Content-Range` header parsing
  (`ContentRangeTest`), the URL-to-file-extension guessing used for URL-mode downloads
  (`UrlDownloaderTest`), and the chunk/thread-count math behind `whisper_full_parallel()`
  (`TranscriberParallelismTest`) — including that total threads never exceed the core budget across
  a spread of durations and core counts. A few originally-private helpers were made `internal` (or
  moved to top-level functions, dropping an Android-only dependency like `android.net.Uri` in
  `UrlDownloader.guessExtension`) specifically so this pure logic is testable without a device.
- **Instrumented tests** (`app/src/androidTest/`, run via `gradle connectedDebugAndroidTest` against
  an emulator in CI):
  - `MainActivitySmokeTest` launches the real `MainActivity` on a real Android runtime and asserts
    it reaches `RESUMED` without crashing, with the UI in the expected initial state (Transcribe
    disabled, File mode selected, every model listed in the spinner, version banner showing the
    build, Stop hidden). This exists because a JVM unit test can't catch manifest/service-registration
    mistakes or runtime crashes — the Android SDK's unit-test stub classes just throw "not
    implemented" if actually invoked, so they'd happily "pass" past bugs that only show up on a real
    device.
  - `TranscriptsActivitySmokeTest` launches `TranscriptsActivity` against an empty transcripts
    directory and asserts the empty-state message shows instead of crashing.
  - `ModelSelectionPersistenceTest` seeds the `MainActivity`-scoped `SharedPreferences` with a
    non-default model id before launch and asserts the spinner restores that selection.
  - `TranscriptionEndToEndTest` actually runs the transcription pipeline: downloads the Tiny model,
    decodes a short bundled test clip (`app/src/androidTest/assets/test_audio.wav`), and calls
    `Transcriber.transcribe()` through the real JNI bridge — the one path the smoke test above
    deliberately skips, and the one that let a previous `whisper_full_parallel()` hang ship
    unnoticed. It wraps the transcribe call in a timeout (300s — CI's shared, 2-core, software-
    rendered emulator is slow enough on its own that this needs slack above what a real device
    would need) specifically so a genuine hang fails the build loudly instead of running forever.

```bash
cd WhisperTranscriber
gradle testDebugUnitTest                 # unit tests, fast, no device
gradle connectedDebugAndroidTest         # instrumented tests, needs a running emulator/device
```

## Building locally

Requires Android Studio (or the command line) with SDK 34, NDK `26.1.10909125`, and CMake `3.22.1` installed.

```bash
cd WhisperTranscriber
./gradlew assembleDebug   # or: gradle assembleDebug if you don't have a wrapper generated locally
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Building via GitHub Actions

Every push that touches this directory triggers `.github/workflows/android-build.yml`, which runs both test suites
above, and — only if they pass — builds a debug APK and publishes it two ways:
- As a workflow artifact named `whisper_debug_<run number>` (containing `whisper_debug_<run number>.apk`) —
  from the run's **Summary** page under **Artifacts**. GitHub always wraps Actions artifacts in a zip on download,
  even for a single file.
- As a GitHub Release asset on the rolling `whisper-debug-latest` tag (updated on every build, `make_latest: true`)
  — a direct link to `whisper_debug_<run number>.apk` under the repo's **Releases**, no zip involved, since Release
  assets download as the raw file. Skipped for `pull_request`-triggered runs (no write token there).

You can also trigger the workflow manually from the **Actions** tab (`workflow_dispatch`). On failure,
`test-reports_<run number>` is uploaded instead with the JUnit/instrumented test HTML reports.

## Notes & limitations

- Recognized extensions for the Folder mode (and a sanity filter, not a hard requirement, for the other modes) are
  listed in `MediaFileUtils.kt`: `mp3, wav, m4a, aac, flac, ogg, oga, opus, amr, 3ga, mp4, m4v, mov, 3gp, 3g2, webm,
  mkv, ts`. Actual decodability still depends on the device's codecs — an unsupported codec fails that one item and
  the batch continues with the rest.
- `usesCleartextTraffic` is enabled so the URL mode can also fetch plain `http://` links, not just `https://`.
- Per-file failures in a batch (bad codec, corrupt file, an unusually long file that can't fit in memory once
  decoded to PCM, …) are caught and reported on that file's card without stopping the rest of the batch — including
  `OutOfMemoryError`, which is an `Error` rather than an `Exception` in Kotlin/Java and used to slip past a
  narrower `catch (e: Exception)`, silently killing the whole batch partway through instead of just failing that
  one file. `android:largeHeap="true"` is also set to give large files more headroom before hitting that ceiling.
- Both model downloads and transcription batches run in foreground services and survive the app being backgrounded.
  A foreground service is *much* less likely to be killed by the OS than a plain background process, but on very
  long batches (many hours) under sustained memory pressure it isn't an absolute guarantee — the per-file disk
  writes exist specifically so that outcome only costs you the files after the interruption, not the whole batch.
  There's also no resume-from-where-it-left-off yet if that does happen; re-running the folder just starts over
  (already-written `.txt` files for finished items are simply overwritten by matching output, so nothing is lost,
  just redone).
- Android 15 (API 35) imposes a rolling execution-time cap on `dataSync`/`mediaProcessing` foreground services, but
  only for apps that target API 35+; this app targets API 34, so it isn't subject to that cap even when running on
  an API 35 device. Worth revisiting if `targetSdk` is ever bumped to 35.
- Native ABIs are limited to `arm64-v8a` and `x86_64` (covers virtually all real devices from the last several years,
  plus the emulator). Add `armeabi-v7a` to `abiFilters` in `app/build.gradle.kts` if you need 32-bit ARM support.
- The CI build fetches whisper.cpp source directly from GitHub at build time (`GIT_TAG master`); pin it to a specific
  release tag in `app/src/main/cpp/CMakeLists.txt` if you need fully reproducible builds.
- Whisper model weights are redistributed by the whisper.cpp project on Hugging Face under the same license as the
  original OpenAI Whisper release. The quantized filenames in `WhisperModel.kt` (e.g. `ggml-medium-q5_0.bin`) follow
  that repo's established naming pattern; if a given file ever gets renamed/removed upstream, that one model just
  fails to download with a clear error — the other model sizes are unaffected.
