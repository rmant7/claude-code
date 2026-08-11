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

        // Splitting work across whisper.cpp's own whisper_full_parallel() (multiple native
        // threads, each decoding a slice of the audio) gets real wall-clock speedups on
        // multi-core phones — plain whisper_full() only parallelizes the matmuls inside a
        // single pass, not across audio chunks. Below 4 cores the per-chunk overhead isn't
        // worth it, so we fall back to a single processor.
        val totalThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val numProcessors = if (totalThreads >= 4) 2 else 1
        val threadsPerProcessor = (totalThreads / numProcessors).coerceAtLeast(1)

        val progressListener = onProgress?.let { callback -> WhisperProgressListener { percent -> callback(percent) } }
        val segmentListener = onSegment?.let { callback -> WhisperSegmentListener { text -> callback(text) } }
        return WhisperLib.transcribe(
            contextPtr, threadsPerProcessor, numProcessors, samples, language, progressListener, segmentListener
        )
    }

    override fun close() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
        }
    }
}
