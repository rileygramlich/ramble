// The keyboard's only native code: load a whisper model and turn 16 kHz mono
// float audio into text. Everything else lives in Java.
#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_rileygramlich_yap_Whisper_load(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_dev_rileygramlich_yap_Whisper_free(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

JNIEXPORT jstring JNICALL
Java_dev_rileygramlich_yap_Whisper_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio,
                                              jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    const char *initial = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    if (initial && initial[0]) params.initial_prompt = initial;

    jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (initial) env->ReleaseStringUTFChars(prompt, initial);
    if (rc != 0) return env->NewStringUTF("");

    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx); i++) text += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(text.c_str());
}

}
