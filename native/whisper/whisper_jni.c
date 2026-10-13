// The JNI side of com.construct.messenger.transcription.WhisperNative: open a model, transcribe
// 16 kHz mono PCM, close. Audio and text stay in this process (TODO 116).
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "Whisper"
#define JNI_FN(name) Java_com_construct_messenger_transcription_WhisperNative_##name

JNIEXPORT jlong JNICALL JNI_FN(open)(JNIEnv *env, jobject thiz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, params);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (ctx == NULL) __android_log_print(ANDROID_LOG_ERROR, TAG, "the model did not load");
    return (jlong) ctx;
}

JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) whisper_free((struct whisper_context *) handle);
}

// [language]: an ISO 639-1 code, or "auto". Returns the text with the language it was read in
// prefixed and a newline ("ru\nпривет"), or null when the model failed.
JNIEXPORT jstring JNICALL JNI_FN(transcribe)(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray pcm, jstring language, jboolean translate, jint threads) {
    struct whisper_context *ctx = (struct whisper_context *) handle;
    if (ctx == NULL) return NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    params.language = lang;
    params.detect_language = false;
    params.translate = translate;
    params.n_threads = threads;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    jsize n = (*env)->GetArrayLength(env, pcm);
    jfloat *samples = (*env)->GetFloatArrayElements(env, pcm, NULL);
    int rc = whisper_full(ctx, params, samples, n);
    (*env)->ReleaseFloatArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return NULL;
    }

    const char *detected = whisper_lang_str(whisper_full_lang_id(ctx));
    size_t cap = 16;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) cap += strlen(whisper_full_get_segment_text(ctx, i));
    char *out = malloc(cap);
    if (out == NULL) return NULL;
    size_t len = 0;
    if (detected != NULL) {
        size_t dl = strlen(detected);
        if (dl < 8) { memcpy(out, detected, dl); len = dl; }
    }
    out[len++] = '\n';
    for (int i = 0; i < segments; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        size_t sl = strlen(seg);
        memcpy(out + len, seg, sl);
        len += sl;
    }
    out[len] = '\0';
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
