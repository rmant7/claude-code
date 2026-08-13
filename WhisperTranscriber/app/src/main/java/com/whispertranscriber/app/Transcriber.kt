package com.whispertranscriber.app

import java.io.Closeable

/** Owns a native whisper.cpp context loaded from a model file on disk. */
class Transcriber(modelPath: String) : Closeable {

    private var contextPtr: Long = WhisperLib.initContext(modelPath)

    init {
        check(contextPtr != 0L) { "Failed to load Whisper model from $modelPath" }
    }

    fun transcribe(
        samples: FloatArray,
        language: String = "auto",
        onProgress: ((Int) -> Unit)? = null,
        onSegment: ((String) -> Unit)? = null
    ): String {
        check(contextPtr != 0L) { "Transcriber already closed" }

        // whisper_full_parallel() (splitting one file's audio across native threads) was tried
        // here for a wall-clock speedup on multi-core phones, but it was never actually verified
        // on real hardware — only that it compiled — and a user hit a transcription that ran for
        // 15+ minutes on a 0.5 MB file with no progress, which is consistent with a hang in that
        // untested path (a plausible edge case: splitting very short audio into chunks). Forcing
        // a single processor falls back to the well-tested, simple whisper_full() unconditionally
        // until whisper_full_parallel can be verified not to do that.
        val numProcessors = 1

        // Pinning every core at 100% for the many minutes a longer file needs is exactly the kind
        // of sustained load that triggers mobile thermal throttling — the same user saw a 0.5 MB
        // call recording (a few minutes of audio) still under 50% after hours, consistent with the
        // phone clocking itself down progressively the longer it runs flat-out. Capping to a more
        // moderate thread count trades a bit of best-case throughput for not falling off that
        // throttling cliff on long-running batches, which matters far more for the "hundreds of
        // files over a few hours" use case than shaving seconds off one short file.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        val progressListener = onProgress?.let { callback -> WhisperProgressListener { percent -> callback(percent) } }
        val segmentListener = onSegment?.let { callback -> WhisperSegmentListener { text -> callback(text) } }
        return WhisperLib.transcribe(
            contextPtr, threads, numProcessors, samples, language, progressListener, segmentListener
        )
    }

    override fun close() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
        }
    }
}
