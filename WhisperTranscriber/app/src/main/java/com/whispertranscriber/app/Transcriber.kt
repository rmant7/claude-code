package com.whispertranscriber.app

import java.io.Closeable

/**
 * (numProcessors, threadsPerProcessor) for splitting [durationSeconds] of audio across
 * whisper_full_parallel(), never using more than [coreBudget] threads in total. Only splits into
 * more than one processor when each chunk would still get at least [minChunkSeconds] of audio, to
 * avoid degenerate near-empty chunks on very short clips.
 */
internal fun computeParallelism(durationSeconds: Float, coreBudget: Int, minChunkSeconds: Int): Pair<Int, Int> {
    val maxUsefulProcessors = (durationSeconds / minChunkSeconds).toInt().coerceAtLeast(1)
    val numProcessors = coreBudget.coerceAtMost(maxUsefulProcessors)
    val threadsPerProcessor = (coreBudget / numProcessors).coerceAtLeast(1)
    return numProcessors to threadsPerProcessor
}

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

        // Pinning every core at 100% for the many minutes a longer file needs is exactly the kind
        // of sustained load that triggers mobile thermal throttling — a user saw a 0.5 MB call
        // recording (several minutes of audio) still under 50% after hours, consistent with the
        // phone clocking itself down progressively the longer it runs flat-out. This is the total
        // CPU budget for this one transcribe() call, shared between however many processors below
        // end up splitting the audio.
        val coreBudget = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        // whisper_full_parallel() (splitting one file's audio into N chunks, each transcribed on
        // its own thread) gives real wall-clock parallelism, which is the whole point for a batch
        // of dozens/hundreds of files — but forcing it unconditionally once caused a suspected hang
        // on a very short clip (a plausible edge case: splitting a few seconds of audio into
        // degenerate near-empty chunks). Only splitting when every chunk would still get a
        // reasonable amount of audio avoids that specific edge case while still parallelizing the
        // long files that actually dominate a multi-hour batch. (Forcing numProcessors=1
        // unconditionally as a defensive fix for that hang was tried, but it made ordinary
        // transcription dramatically slower — confirmed by CI, where even a 4-second clip on the
        // tiny model exceeded a 120s budget — so it traded an unconfirmed hang for a confirmed,
        // severe slowdown. abort_callback wiring below is the safety net for this instead.)
        val durationSeconds = samples.size / SAMPLE_RATE_HZ.toFloat()
        val (numProcessors, threadsPerProcessor) = computeParallelism(durationSeconds, coreBudget, MIN_CHUNK_SECONDS)

        val progressListener = onProgress?.let { callback -> WhisperProgressListener { percent -> callback(percent) } }
        val segmentListener = onSegment?.let { callback -> WhisperSegmentListener { text -> callback(text) } }
        return WhisperLib.transcribe(
            contextPtr, threadsPerProcessor, numProcessors, samples, language, progressListener, segmentListener
        )
    }

    /** Interrupts an in-flight [transcribe] call as soon as whisper.cpp next checks (not instant). */
    fun cancel() {
        if (contextPtr != 0L) {
            WhisperLib.requestCancel(contextPtr)
        }
    }

    override fun close() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MIN_CHUNK_SECONDS = 30
    }
}
