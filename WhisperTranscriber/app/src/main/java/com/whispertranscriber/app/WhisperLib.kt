package com.whispertranscriber.app

/** Invoked from native code (see whisper_jni.cpp) with whisper.cpp's inference progress, 0-100. */
fun interface WhisperProgressListener {
    fun onProgress(percent: Int)
}

/** Invoked from native code each time whisper.cpp finalizes one or more new transcript segments. */
fun interface WhisperSegmentListener {
    fun onSegment(text: String)
}

/** Thin JNI bridge to the native whisper.cpp library (see src/main/cpp/whisper_jni.cpp). */
object WhisperLib {

    init {
        System.loadLibrary("whisper_jni")
    }

    @JvmStatic
    external fun initContext(modelPath: String): Long

    @JvmStatic
    external fun freeContext(contextPtr: Long)

    /** Interrupts an in-flight [transcribe] call on this context as soon as whisper.cpp next checks. */
    @JvmStatic
    external fun requestCancel(contextPtr: Long)

    @JvmStatic
    external fun transcribe(
        contextPtr: Long,
        numThreads: Int,
        numProcessors: Int,
        audioData: FloatArray,
        language: String,
        progressListener: WhisperProgressListener?,
        segmentListener: WhisperSegmentListener?
    ): String
}
