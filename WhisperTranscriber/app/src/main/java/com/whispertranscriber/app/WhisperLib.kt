package com.whispertranscriber.app

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
    external fun transcribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String): String
}
