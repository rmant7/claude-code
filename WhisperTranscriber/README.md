# Whisper Transcriber (Android)

An Android app that transcribes AMR audio files on-device using [OpenAI Whisper](https://github.com/openai/whisper) via
[whisper.cpp](https://github.com/ggerganov/whisper.cpp). Whisper models are **not** bundled with the app — you pick a
model size and download it on demand from Hugging Face the first time you need it.

## How it works

1. **Pick a model size** — Tiny (~75 MB), Base (~142 MB), Small (~466 MB), Medium (~1.5 GB) or Large v3 Turbo
   (~1.6 GB) — and tap **Download model**. The file is fetched from
   `https://huggingface.co/ggerganov/whisper.cpp` via Android's `DownloadManager` and stored in the app's private
   external storage (`Android/data/com.whispertranscriber.app/files/models`), so no storage permission is required.
2. **Pick an `.amr` file** using the system file picker.
3. Tap **Transcribe**. The app:
   - Decodes the AMR-NB/AMR-WB audio to PCM using Android's built-in `MediaExtractor`/`MediaCodec` (no extra codec
     library needed — AMR decoding is a stock Android media capability).
   - Downmixes to mono and resamples to 16 kHz, the format whisper.cpp expects.
   - Runs inference on-device through a small JNI bridge (`app/src/main/cpp/whisper_jni.cpp`) around whisper.cpp's C
     API.
4. The transcript is shown on screen and can be copied or shared.

## Project layout

- `app/src/main/java/com/whispertranscriber/app/`
  - `MainActivity.kt` — UI and orchestration.
  - `ModelManager.kt` — model download/storage via `DownloadManager`.
  - `AudioDecoder.kt` — AMR → 16 kHz mono float PCM decoding.
  - `WhisperLib.kt` / `Transcriber.kt` — Kotlin side of the JNI bridge.
- `app/src/main/cpp/`
  - `CMakeLists.txt` — fetches whisper.cpp source via CMake `FetchContent` and builds it for Android (arm64-v8a,
    x86_64) with native CPU-detection disabled, since we're cross-compiling.
  - `whisper_jni.cpp` — the native `WhisperLib` implementation, calling whisper.cpp's public C API
    (`whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_get_segment_text`, …).

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

- Native ABIs are limited to `arm64-v8a` and `x86_64` (covers virtually all real devices from the last several years,
  plus the emulator). Add `armeabi-v7a` to `abiFilters` in `app/build.gradle.kts` if you need 32-bit ARM support.
- The CI build fetches whisper.cpp source directly from GitHub at build time (`GIT_TAG master`); pin it to a specific
  release tag in `app/src/main/cpp/CMakeLists.txt` if you need fully reproducible builds.
- Whisper model weights are redistributed by the whisper.cpp project on Hugging Face under the same license as the
  original OpenAI Whisper release.
