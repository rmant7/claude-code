#include <jni.h>
#include <android/log.h>
#include <string>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispertranscriber_app_WhisperLib_initContext(JNIEnv *env, jclass /*clazz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from model file");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertranscriber_app_WhisperLib_freeContext(JNIEnv *env, jclass /*clazz*/, jlong contextPtr) {
    if (contextPtr == 0) return;
    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertranscriber_app_WhisperLib_transcribe(JNIEnv *env, jclass /*clazz*/, jlong contextPtr,
                                                       jint numThreads, jfloatArray audioData,
                                                       jstring language) {
    if (contextPtr == 0) {
        return env->NewStringUTF("");
    }
    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);

    jsize numSamples = env->GetArrayLength(audioData);
    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = numThreads > 0 ? numThreads : 4;
    params.no_context = true;

    int result = whisper_full(ctx, params, samples, numSamples);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("");
    }

    std::string output;
    const int numSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < numSegments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            output += text;
        }
    }

    return env->NewStringUTF(output.c_str());
}
