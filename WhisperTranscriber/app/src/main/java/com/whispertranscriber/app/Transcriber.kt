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
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val numProcessors = 1

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
