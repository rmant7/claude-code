package com.whispertranscriber.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises the actual transcription pipeline end to end — model download, audio decode, and the
 * native whisper.cpp JNI call — on a real device/emulator. The launch-only smoke test in
 * [MainActivitySmokeTest] can't catch bugs in this path at all (it never touches WhisperLib), and
 * that gap let a real hang ship: whisper_full_parallel() was never verified on real hardware and a
 * user hit a transcription that ran 15+ minutes on a 0.5 MB file with no progress. This test uses
 * a hard timeout specifically so a hang like that fails the build instead of shipping again.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionEndToEndTest {

    @Test
    fun transcribesABundledClipWithoutHangingOrCrashing() {
        // The app-under-test's context (for ModelManager/AudioDecoder, so this matches production
        // behavior exactly) is NOT the same as the test APK's own context. Bundled androidTest
        // assets live in the *test* APK, so reading "test_audio.wav" needs the instrumentation's
        // own context — using targetContext here throws FileNotFoundException, since the app's
        // APK never contains this file at all.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val modelManager = ModelManager(targetContext)
        val model = WhisperModel.ALL.first { it.id == "tiny" }

        if (!modelManager.isDownloaded(model)) {
            modelManager.downloadModel(model) { /* no-op progress */ }
        }
        assertTrue("tiny model should be downloaded before transcribing", modelManager.isDownloaded(model))

        val audioFile = copyTestAssetToCache(testContext, targetContext, "test_audio.wav")
        val samples = AudioDecoder.decodeFromPath(audioFile.absolutePath)
        assertTrue("decoded audio should not be empty", samples.isNotEmpty())

        val executor = Executors.newSingleThreadExecutor()
        try {
            val transcriber = Transcriber(modelManager.modelFile(model).absolutePath)
            try {
                val future = executor.submit<String> { transcriber.transcribe(samples) }
                // A generous but finite timeout: this must fail loudly if inference hangs, rather
                // than hanging the CI job (and, previously, a real user's phone) indefinitely.
                val transcript = future.get(TRANSCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertTrue("transcribe() should return a (possibly empty) string, not throw", transcript != null)
            } finally {
                transcriber.close()
            }
        } finally {
            executor.shutdownNow()
            modelManager.deleteModel(model)
        }
    }

    // The test APK's own cache dir (from assetContext) isn't guaranteed to exist on every device
    // image — it hit a bare FileNotFoundException on the CI emulator — so the asset bytes are read
    // from the test APK but written into the app-under-test's cache dir, which ModelManager already
    // uses elsewhere in this same test and is therefore known to exist.
    private fun copyTestAssetToCache(
        assetContext: android.content.Context,
        outputContext: android.content.Context,
        assetName: String
    ): File {
        val outFile = File(outputContext.cacheDir, assetName)
        assetContext.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private companion object {
        const val TRANSCRIBE_TIMEOUT_SECONDS = 120L
    }
}
