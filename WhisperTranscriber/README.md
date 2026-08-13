# Whisper Transcriber (Android)

An Android app that transcribes audio and video files on-device using [OpenAI Whisper](https://github.com/openai/whisper)
via [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Whisper models are **not** bundled with the app — you pick a
model size and download it on demand from Hugging Face the first time you need it.

## How it works

1. **Pick a model size** — Tiny, Base, Small, Medium and Large v3 Turbo, each also offered as a **quantized**
   variant (q5_1/q5_0) that's roughly a third of the full-precision file size and noticeably faster to run on a
   phone CPU, at a small accuracy cost — worth trying first if a full-precision model feels too slow. Tap
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
     API. The model is loaded **once** and reused across every file in a batch — this is also why files aren't
     transcribed several-at-once: each loaded whisper.cpp context holds the model weights in RAM (e.g. ~1.5 GB for
     Medium), so N files running fully in parallel would mean N full model loads, which would OOM on a typical
     phone for anything above Tiny/Base. Instead, on devices with 4+ cores, inference uses whisper.cpp's
     `whisper_full_parallel()` (2 processors, splitting *one* file's audio across native threads that share the
     same loaded model) instead of single-threaded `whisper_full()` — a real wall-clock speedup without the memory
     cost of extra model copies. Progress/segment callbacks resolve their own JNIEnv per-thread (attaching if
     needed) via a cached `JavaVM`, since the extra native threads whisper_full_parallel() spawns can't reuse a
     JNIEnv captured on a different thread — so live progress and streamed text work in both modes.
   - In a multi-file batch, the next file's audio is decoded in the background while the current file is being
     transcribed (a one-item lookahead), so decode time is fully hidden for every file but the first — decoding
     via `MediaCodec` is normally much faster than whisper.cpp inference, so this (rather than chunking a single
     file's audio) is where pipelining actually saves wall-clock time.
4. Progress is visible at several levels: an overall "File *N* of *M*" bar for the batch, a live percentage on the
   card of whichever file is actively being processed (decode progress from the container's reported duration,
   transcription progress from whisper.cpp's own `progress_callback`) — and the transcript itself streams in live,
   segment by segment, via whisper.cpp's `new_segment_callback`, instead of only appearing once the whole file is
   done.
5. Each result appears as its own card (filename, status, live/finished transcript) as soon as it's ready;
   **Copy all** / **Share all** combine every finished transcript into one block of text.

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
  - `ModelManager.kt` — Whisper model download/storage; streams to a temp file, resumes via HTTP `Range` requests
    after a dropped connection, and only promotes the file once its full size is verified.
  - `UrlDownloader.kt` — streams a remote URL to a temp file for the URL mode.
  - `MediaFileUtils.kt` — recognizes media file extensions and recursively scans a picked folder.
  - `AudioDecoder.kt` — audio/video → 16 kHz mono float PCM decoding, with per-file progress from the container's
    duration.
  - `MediaSource.kt` / `TranscriptionResult.kt` / `ResultsAdapter.kt` — the batch queue and its results list.
  - `WhisperLib.kt` / `Transcriber.kt` — Kotlin side of the JNI bridge, including the progress and live-segment
    callbacks.
- `app/src/main/cpp/`
  - `CMakeLists.txt` — fetches whisper.cpp source via CMake `FetchContent` and builds it for Android (arm64-v8a,
    x86_64) with native CPU-detection disabled, since we're cross-compiling.
  - `whisper_jni.cpp` — the native `WhisperLib` implementation, calling whisper.cpp's public C API
    (`whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_get_segment_text`, …) and bridging its
    `progress_callback`/`new_segment_callback` back into Kotlin listener interfaces.

## Testing

Two test suites gate every CI build; the APK artifact is only uploaded if both pass.

- **Unit tests** (`app/src/test/`, run via `gradle testDebugUnitTest`) — plain JVM tests, no device
  needed, covering pure logic: media file extension matching (`MediaFileUtilsTest`), the Whisper
  model catalog's integrity — unique ids/filenames, well-formed URLs (`WhisperModelTest`), the PCM
  downmix/resample math (`AudioDecoderTest`), HTTP `Content-Range` header parsing
  (`ContentRangeTest`), and the URL-to-file-extension guessing used for URL-mode downloads
  (`UrlDownloaderTest`). A few originally-private helpers were made `internal` (or moved to
  top-level functions, dropping an Android-only dependency like `android.net.Uri` in
  `UrlDownloader.guessExtension`) specifically so this pure logic is testable without a device.
- **Instrumented test** (`app/src/androidTest/`, run via `gradle connectedDebugAndroidTest` against
  an emulator in CI) — `MainActivitySmokeTest` launches the real `MainActivity` on a real Android
  runtime and asserts it reaches `RESUMED` without crashing, with the UI in the expected initial
  state (Transcribe disabled, File mode selected, every model listed in the spinner). This exists
  because a JVM unit test can't catch manifest/service-registration mistakes or runtime crashes —
  the Android SDK's unit-test stub classes just throw "not implemented" if actually invoked, so
  they'd happily "pass" past bugs that only show up on a real device. It deliberately does **not**
  exercise the native whisper.cpp path (that needs a real model file and audio input, out of scope
  for a lightweight launch smoke test) — that path is currently verified manually.

```bash
cd WhisperTranscriber
gradle testDebugUnitTest                 # unit tests, fast, no device
gradle connectedDebugAndroidTest         # instrumented test, needs a running emulator/device
```

## Building locally

Requires Android Studio (or the command line) with SDK 34, NDK `26.1.10909125`, and CMake `3.22.1` installed.

```bash
cd WhisperTranscriber
./gradlew assembleDebug   # or: gradle assembleDebug if you don't have a wrapper generated locally
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Building via GitHub Actions

Every push that touches this directory triggers `.github/workflows/android-build.yml`, which builds a debug APK and
uploads it as a workflow artifact named `whisper-transcriber-debug-apk`. Download it from the run's **Summary** page
under **Artifacts**. You can also trigger it manually from the **Actions** tab (`workflow_dispatch`).

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
