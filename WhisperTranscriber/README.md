# Whisper Transcriber (Android)

An Android app that transcribes audio and video files on-device using [OpenAI Whisper](https://github.com/openai/whisper)
via [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Whisper models are **not** bundled with the app — you pick a
model size and download it on demand from Hugging Face the first time you need it.

## How it works

1. **Pick a model size** — Tiny (~75 MB), Base (~142 MB), Small (~466 MB), Medium (~1.5 GB) or Large v3 Turbo
   (~1.6 GB) — and tap **Download model**. The download runs in `ModelDownloadService`, a **foreground service**
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
3. Tap **Transcribe**. For every queued item the app:
   - Extracts and decodes the audio track using Android's built-in `MediaExtractor`/`MediaCodec` — this works for
     plain audio containers (mp3, wav, m4a/aac, flac, ogg/opus, amr, …) as well as video containers (mp4, mov, 3gp,
     webm, mkv, ts, …), since only the audio track is selected and video frames are never touched.
   - Downmixes to mono and resamples to 16 kHz, the format whisper.cpp expects.
   - Runs inference on-device through a small JNI bridge (`app/src/main/cpp/whisper_jni.cpp`) around whisper.cpp's C
     API. The model is loaded once and reused across every file in a batch.
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
  - `MainActivity.kt` — UI and orchestration across the File / URL / Folder modes; pipelines decode-ahead with
    transcription for multi-file batches.
  - `ModelDownloadService.kt` / `ModelDownloadState.kt` — foreground service that owns the model download (so it
    survives backgrounding) plus the in-process `StateFlow` the Activity observes for progress/completion.
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
- Model downloads run in a foreground service and survive backgrounding. Transcription batches do not (yet) — they
  still run on a coroutine tied to the Activity's lifecycle, so a batch is interrupted if the app process is killed
  while backgrounded. Worth moving to a foreground service too if long unattended batches become a common case.
- Native ABIs are limited to `arm64-v8a` and `x86_64` (covers virtually all real devices from the last several years,
  plus the emulator). Add `armeabi-v7a` to `abiFilters` in `app/build.gradle.kts` if you need 32-bit ARM support.
- The CI build fetches whisper.cpp source directly from GitHub at build time (`GIT_TAG master`); pin it to a specific
  release tag in `app/src/main/cpp/CMakeLists.txt` if you need fully reproducible builds.
- Whisper model weights are redistributed by the whisper.cpp project on Hugging Face under the same license as the
  original OpenAI Whisper release.
