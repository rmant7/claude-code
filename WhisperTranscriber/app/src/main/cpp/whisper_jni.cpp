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

namespace {

// whisper_full() runs synchronously on the calling thread, so the JNIEnv captured when the JNI
// call entered is still valid for the whole duration of inference — no thread attach/detach needed.
struct ProgressCallbackContext {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgressMethod;
};

void onWhisperProgress(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, int progress, void *userData) {
    auto *context = static_cast<ProgressCallbackContext *>(userData);
    if (context != nullptr && context->listener != nullptr) {
        context->env->CallVoidMethod(context->listener, context->onProgressMethod, static_cast<jint>(progress));
    }
}

// Same single-thread/single-call lifetime reasoning as ProgressCallbackContext above.
struct SegmentCallbackContext {
    JNIEnv *env;
    jobject listener;
    jmethodID onSegmentMethod;
};

void onWhisperNewSegment(struct whisper_context *ctx, struct whisper_state * /*state*/, int n_new, void *userData) {
    auto *context = static_cast<SegmentCallbackContext *>(userData);
    if (context == nullptr || context->listener == nullptr || n_new <= 0) {
        return;
    }
    const int totalSegments = whisper_full_n_segments(ctx);
    for (int i = totalSegments - n_new; i < totalSegments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) {
            continue;
        }
        jstring jtext = context->env->NewStringUTF(text);
        context->env->CallVoidMethod(context->listener, context->onSegmentMethod, jtext);
        context->env->DeleteLocalRef(jtext);
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertranscriber_app_WhisperLib_transcribe(JNIEnv *env, jclass /*clazz*/, jlong contextPtr,
                                                       jint numThreads, jfloatArray audioData,
                                                       jstring language, jobject progressListener,
                                                       jobject segmentListener) {
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

    ProgressCallbackContext progressContext{};
    if (progressListener != nullptr) {
        jclass listenerClass = env->GetObjectClass(progressListener);
        progressContext.env = env;
        progressContext.listener = progressListener;
        progressContext.onProgressMethod = env->GetMethodID(listenerClass, "onProgress", "(I)V");
        params.progress_callback = onWhisperProgress;
        params.progress_callback_user_data = &progressContext;
    }

    SegmentCallbackContext segmentContext{};
    if (segmentListener != nullptr) {
        jclass listenerClass = env->GetObjectClass(segmentListener);
        segmentContext.env = env;
        segmentContext.listener = segmentListener;
        segmentContext.onSegmentMethod = env->GetMethodID(listenerClass, "onSegment", "(Ljava/lang/String;)V");
        params.new_segment_callback = onWhisperNewSegment;
        params.new_segment_callback_user_data = &segmentContext;
    }

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
