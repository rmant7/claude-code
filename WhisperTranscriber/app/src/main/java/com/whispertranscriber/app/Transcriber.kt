package com.whispertranscriber.app

import java.io.Closeable

/** Owns a native whisper.cpp context loaded from a model file on disk. */
class Transcriber(modelPath: String) : Closeable {

    private var contextPtr: Long = WhisperLib.initContext(modelPath)

    init {
        check(contextPtr != 0L) { "Failed to load Whisper model from $modelPath" }
    }

    fun transcribe(samples: FloatArray, language: String = "auto", onProgress: ((Int) -> Unit)? = null): String {
        check(contextPtr != 0L) { "Transcriber already closed" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val listener = onProgress?.let { callback -> WhisperProgressListener { percent -> callback(percent) } }
        return WhisperLib.transcribe(contextPtr, threads, samples, language, listener)
    }

    override fun close() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
        }
    }
}
