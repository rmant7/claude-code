package com.whispertranscriber.app

/** Invoked from native code (see whisper_jni.cpp) with whisper.cpp's inference progress, 0-100. */
fun interface WhisperProgressListener {
    fun onProgress(percent: Int)
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

    @JvmStatic
    external fun transcribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        progressListener: WhisperProgressListener?
    ): String
}
