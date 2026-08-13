#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The Long "context pointer" handed to Kotlin actually points at this handle rather than a bare
// whisper_context*, so a Stop button can flip cancelRequested from the calling thread while
// whisper_full()/whisper_full_parallel() is still running on a worker thread — checked cheaply
// (no JNI upcall needed) by the abort_callback below.
struct WhisperHandle {
    struct whisper_context *ctx;
    std::atomic<bool> cancelRequested{false};
};

} // namespace

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
    auto *handle = new WhisperHandle{ctx};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertranscriber_app_WhisperLib_freeContext(JNIEnv *env, jclass /*clazz*/, jlong contextPtr) {
    if (contextPtr == 0) return;
    auto *handle = reinterpret_cast<WhisperHandle *>(contextPtr);
    whisper_free(handle->ctx);
    delete handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertranscriber_app_WhisperLib_requestCancel(JNIEnv *env, jclass /*clazz*/, jlong contextPtr) {
    if (contextPtr == 0) return;
    auto *handle = reinterpret_cast<WhisperHandle *>(contextPtr);
    handle->cancelRequested.store(true);
}

namespace {

// whisper_full_parallel() runs extra chunks on additional native threads that it spawns and
// joins internally, so a JNIEnv captured on the calling thread is only valid there — callbacks
// resolve (attaching if needed) a JNIEnv for whatever thread they're actually invoked on via this
// cached JavaVM, which is safe to use from any thread.
JNIEnv *resolveEnvForCurrentThread(JavaVM *vm) {
    if (vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

// listener is a global ref: JNI local refs (including method parameters) aren't valid outside the
// thread/call frame that received them, so a plain local ref would be unsafe to use from the
// parallel worker threads too.
struct ProgressCallbackContext {
    JavaVM *vm;
    jobject listener;
    jmethodID onProgressMethod;
};

void onWhisperProgress(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, int progress, void *userData) {
    auto *context = static_cast<ProgressCallbackContext *>(userData);
    if (context == nullptr || context->listener == nullptr) return;
    JNIEnv *env = resolveEnvForCurrentThread(context->vm);
    if (env == nullptr) return;
    env->CallVoidMethod(context->listener, context->onProgressMethod, static_cast<jint>(progress));
}

struct SegmentCallbackContext {
    JavaVM *vm;
    jobject listener;
    jmethodID onSegmentMethod;
};

void onWhisperNewSegment(struct whisper_context *ctx, struct whisper_state *state, int n_new, void *userData) {
    auto *context = static_cast<SegmentCallbackContext *>(userData);
    if (context == nullptr || context->listener == nullptr || n_new <= 0) {
        return;
    }
    JNIEnv *env = resolveEnvForCurrentThread(context->vm);
    if (env == nullptr) return;

    // Under whisper_full_parallel() each worker chunk accumulates its segments in its own
    // whisper_state, not the context's default state, so the state-aware accessors must be used
    // here to see that chunk's own segments; state is only null for the plain whisper_full() path.
    const int totalSegments = state != nullptr
                                   ? whisper_full_n_segments_from_state(state)
                                   : whisper_full_n_segments(ctx);
    for (int i = totalSegments - n_new; i < totalSegments; ++i) {
        const char *text = state != nullptr
                                ? whisper_full_get_segment_text_from_state(state, i)
                                : whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) {
            continue;
        }
        jstring jtext = env->NewStringUTF(text);
        env->CallVoidMethod(context->listener, context->onSegmentMethod, jtext);
        env->DeleteLocalRef(jtext);
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertranscriber_app_WhisperLib_transcribe(JNIEnv *env, jclass /*clazz*/, jlong contextPtr,
                                                       jint numThreads, jint numProcessors,
                                                       jfloatArray audioData, jstring language,
                                                       jobject progressListener,
                                                       jobject segmentListener) {
    if (contextPtr == 0) {
        return env->NewStringUTF("");
    }
    auto *handle = reinterpret_cast<WhisperHandle *>(contextPtr);
    struct whisper_context *ctx = handle->ctx;
    // The same handle (and its cancel flag) is reused across every file in a batch, so a Stop
    // request from a previous file must not carry over and immediately abort the next one.
    handle->cancelRequested.store(false);

    jsize numSamples = env->GetArrayLength(audioData);
    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = numThreads > 0 ? numThreads : 4;
    params.no_context = true;
    // Checked periodically by whisper.cpp during inference so a Stop button actually interrupts a
    // running transcription instead of only taking effect once the whole file finishes.
    params.abort_callback = [](void *userData) {
        return static_cast<std::atomic<bool> *>(userData)->load();
    };
    params.abort_callback_user_data = &handle->cancelRequested;

    jobject progressListenerGlobal = nullptr;
    ProgressCallbackContext progressContext{};
    if (progressListener != nullptr) {
        progressListenerGlobal = env->NewGlobalRef(progressListener);
        jclass listenerClass = env->GetObjectClass(progressListener);
        progressContext.vm = vm;
        progressContext.listener = progressListenerGlobal;
        progressContext.onProgressMethod = env->GetMethodID(listenerClass, "onProgress", "(I)V");
        params.progress_callback = onWhisperProgress;
        params.progress_callback_user_data = &progressContext;
    }

    jobject segmentListenerGlobal = nullptr;
    SegmentCallbackContext segmentContext{};
    if (segmentListener != nullptr) {
        segmentListenerGlobal = env->NewGlobalRef(segmentListener);
        jclass listenerClass = env->GetObjectClass(segmentListener);
        segmentContext.vm = vm;
        segmentContext.listener = segmentListenerGlobal;
        segmentContext.onSegmentMethod = env->GetMethodID(listenerClass, "onSegment", "(Ljava/lang/String;)V");
        params.new_segment_callback = onWhisperNewSegment;
        params.new_segment_callback_user_data = &segmentContext;
    }

    const int processors = numProcessors > 1 ? numProcessors : 1;
    int result = processors > 1
                      ? whisper_full_parallel(ctx, params, samples, numSamples, processors)
                      : whisper_full(ctx, params, samples, numSamples);

    if (progressListenerGlobal != nullptr) {
        env->DeleteGlobalRef(progressListenerGlobal);
    }
    if (segmentListenerGlobal != nullptr) {
        env->DeleteGlobalRef(segmentListenerGlobal);
    }

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
