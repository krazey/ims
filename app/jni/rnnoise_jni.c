#include <jni.h>

#include <math.h>
#include <stdint.h>
#include <string.h>

#include <rnnoise.h>

#define RNNOISE_MAX_FRAME_SAMPLES 480
#define PCM_SAMPLE_BYTES 2

JNIEXPORT jfloat JNICALL
Java_me_phh_ims_Rnnoise_processFrameInPlace(
        JNIEnv *env,
        jobject thiz,
        jlong state_handle,
        jbyteArray pcm,
        jint offset_bytes) {
    (void)thiz;

    DenoiseState *state =
            (DenoiseState *)(intptr_t)state_handle;
    if (state == NULL || pcm == NULL || offset_bytes < 0) {
        return -1.0f;
    }

    const int frame_samples = rnnoise_get_frame_size();
    if (frame_samples <= 0 ||
            frame_samples > RNNOISE_MAX_FRAME_SAMPLES) {
        return -1.0f;
    }

    const int frame_bytes = frame_samples * PCM_SAMPLE_BYTES;
    const jsize pcm_size = (*env)->GetArrayLength(env, pcm);
    if (offset_bytes > pcm_size - frame_bytes) {
        return -1.0f;
    }

    jbyte pcm_bytes[RNNOISE_MAX_FRAME_SAMPLES * PCM_SAMPLE_BYTES];
    int16_t pcm_samples[RNNOISE_MAX_FRAME_SAMPLES];
    float input[RNNOISE_MAX_FRAME_SAMPLES];
    float output[RNNOISE_MAX_FRAME_SAMPLES];

    (*env)->GetByteArrayRegion(
            env,
            pcm,
            offset_bytes,
            frame_bytes,
            pcm_bytes);
    if ((*env)->ExceptionCheck(env)) {
        return -1.0f;
    }

    memcpy(pcm_samples, pcm_bytes, frame_bytes);
    for (int i = 0; i < frame_samples; i++) {
        input[i] = (float)pcm_samples[i];
    }

    const float voice_probability =
            rnnoise_process_frame(state, output, input);

    for (int i = 0; i < frame_samples; i++) {
        float sample = output[i];
        if (sample > INT16_MAX) {
            sample = INT16_MAX;
        } else if (sample < INT16_MIN) {
            sample = INT16_MIN;
        }
        pcm_samples[i] = (int16_t)lrintf(sample);
    }

    memcpy(pcm_bytes, pcm_samples, frame_bytes);
    (*env)->SetByteArrayRegion(
            env,
            pcm,
            offset_bytes,
            frame_bytes,
            pcm_bytes);
    if ((*env)->ExceptionCheck(env)) {
        return -1.0f;
    }

    return voice_probability;
}

JNIEXPORT jlong JNICALL
Java_me_phh_ims_Rnnoise_init(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    DenoiseState *state = rnnoise_create(NULL);
    return (jlong)(intptr_t)state;
}

JNIEXPORT void JNICALL
Java_me_phh_ims_Rnnoise_destroy(
        JNIEnv *env,
        jobject thiz,
        jlong state_handle) {
    (void)env;
    (void)thiz;

    DenoiseState *state =
            (DenoiseState *)(intptr_t)state_handle;
    if (state != NULL) {
        rnnoise_destroy(state);
    }
}

JNIEXPORT jint JNICALL
Java_me_phh_ims_Rnnoise_getFrameSize(
        JNIEnv *env,
        jobject thiz) {
    (void)env;
    (void)thiz;

    return rnnoise_get_frame_size();
}
