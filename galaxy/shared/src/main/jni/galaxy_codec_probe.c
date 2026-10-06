#include <jni.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdlib.h>
#include <time.h>

/* 固定样例的 NDK 同步路径；所有驱动调用仅在可单独结束的调试进程执行。 */
static int64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}
static void wait_until(int64_t target) {
    int64_t remaining = target - now_ns();
    if (remaining > 0) {
        struct timespec t = { remaining / 1000000000LL, remaining % 1000000000LL };
        nanosleep(&t, NULL);
    }
}
static int phase(JNIEnv *env, jobject listener, jmethodID method, jint stage) {
    (*env)->CallVoidMethod(env, listener, method, stage);
    return !(*env)->ExceptionCheck(env);
}
JNIEXPORT jlongArray JNICALL
Java_com_shilapi_xcertplay_media_GalaxyNativeCodecProbe_decode(JNIEnv *env, jobject self,
        jstring name, jstring mime, jobject surface, jobjectArray csd, jobjectArray packets,
        jlongArray timestamps, jobject listener) {
    (void)self;
    jlong result[6] = {0, 0, 0, 0, 0, 2};
    AMediaCodec *codec = NULL;
    AMediaFormat *format = NULL;
    ANativeWindow *window = NULL;
    const char *codec_name = NULL, *codec_mime = NULL;
    jlong pts[120];
    jsize count = (*env)->GetArrayLength(env, packets);
    int started = 0, input_eos = 0;
    int64_t origin = 0, deadline = 0;
    jclass cls = (*env)->GetObjectClass(env, listener);
    jmethodID stage = (*env)->GetMethodID(env, cls, "stage", "(I)V");
    jmethodID decoder = (*env)->GetMethodID(env, cls, "decoder", "(Ljava/lang/String;)V");
    if (!stage || !decoder || count < 1 || count > 120 || (*env)->GetArrayLength(env, timestamps) != count) {
        result[0] = -10001; goto cleanup;
    }
    (*env)->GetLongArrayRegion(env, timestamps, 0, count, pts);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    for (int i = 0; i < count; i++) if (pts[i] < 0 || pts[i] > 3000000 || (i && pts[i] < pts[i-1])) {
        result[0] = -10002; goto cleanup;
    }
    codec_name = (*env)->GetStringUTFChars(env, name, NULL);
    codec_mime = (*env)->GetStringUTFChars(env, mime, NULL);
    if (!codec_name || !codec_mime || !(result[5] = 2, phase(env, listener, stage, 2))) goto cleanup;
    codec = AMediaCodec_createCodecByName(codec_name);
    if (!codec) { result[0] = -10003; goto cleanup; }
    char *actual_name = NULL;
    if (AMediaCodec_getName(codec, &actual_name) == AMEDIA_OK && actual_name) {
        jstring actual = (*env)->NewStringUTF(env, actual_name);
        (*env)->CallVoidMethod(env, listener, decoder, actual);
        (*env)->DeleteLocalRef(env, actual);
        AMediaCodec_releaseName(codec, actual_name);
        if ((*env)->ExceptionCheck(env)) goto cleanup;
    } else { result[0] = -10004; goto cleanup; }
    if (!(result[5] = 3, phase(env, listener, stage, 3))) goto cleanup;
    window = ANativeWindow_fromSurface(env, surface);
    if (!window) { result[0] = -10005; goto cleanup; }
    format = AMediaFormat_new();
    if (!format) { result[0] = -10006; goto cleanup; }
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, codec_mime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 640);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 360);
    jsize configs = (*env)->GetArrayLength(env, csd);
    if (configs < 1 || configs > 3) { result[0] = -10007; goto cleanup; }
    const char *keys[] = { "csd-0", "csd-1", "csd-2" };
    for (int i = 0; i < configs; i++) {
        jbyteArray b = (jbyteArray)(*env)->GetObjectArrayElement(env, csd, i);
        jsize size = (*env)->GetArrayLength(env, b);
        if (size < 1 || size > 65536) { (*env)->DeleteLocalRef(env, b); result[0] = -10008; goto cleanup; }
        unsigned char *data = malloc((size_t)size);
        if (!data) { (*env)->DeleteLocalRef(env, b); result[0] = -10009; goto cleanup; }
        (*env)->GetByteArrayRegion(env, b, 0, size, (jbyte *)data);
        AMediaFormat_setBuffer(format, keys[i], data, (size_t)size);
        free(data); (*env)->DeleteLocalRef(env, b);
        if ((*env)->ExceptionCheck(env)) goto cleanup;
    }
    result[0] = AMediaCodec_configure(codec, format, window, NULL, 0);
    if (result[0] != AMEDIA_OK) goto cleanup;
    if (!(result[5] = 4, phase(env, listener, stage, 4))) goto cleanup;
    result[0] = AMediaCodec_start(codec);
    if (result[0] != AMEDIA_OK) goto cleanup;
    started = 1; origin = now_ns(); deadline = origin + 4000000000LL;
    if (!(result[5] = 5, phase(env, listener, stage, 5))) goto cleanup;
    while (now_ns() < deadline && !result[3]) {
        if (!input_eos) {
            ssize_t index = AMediaCodec_dequeueInputBuffer(codec, 10000);
            if (index >= 0) {
                size_t capacity = 0;
                uint8_t *buffer = AMediaCodec_getInputBuffer(codec, (size_t)index, &capacity);
                size_t size = 0;
                uint32_t flags = 0;
                int64_t timestamp = 0;
                if (result[1] < count) {
                    jbyteArray b = (jbyteArray)(*env)->GetObjectArrayElement(env, packets, (jsize)result[1]);
                    jsize length = (*env)->GetArrayLength(env, b);
                    if (!buffer || length < 1 || (size_t)length > capacity) {
                        (*env)->DeleteLocalRef(env, b); result[0] = -10010; goto cleanup;
                    }
                    (*env)->GetByteArrayRegion(env, b, 0, length, (jbyte *)buffer);
                    (*env)->DeleteLocalRef(env, b);
                    if ((*env)->ExceptionCheck(env)) goto cleanup;
                    size = (size_t)length; timestamp = pts[result[1]];
                } else { flags = AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM; input_eos = 1; }
                result[0] = AMediaCodec_queueInputBuffer(codec, (size_t)index, 0, size, (uint64_t)timestamp, flags);
                if (result[0] != AMEDIA_OK) goto cleanup;
                if (!flags) result[1]++;
                else if (!(result[5] = 6, phase(env, listener, stage, 6))) goto cleanup;
            } else if (index != AMEDIACODEC_INFO_TRY_AGAIN_LATER) { result[0] = index; goto cleanup; }
        }
        AMediaCodecBufferInfo info;
        ssize_t index = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
        if (index >= 0) {
            int frame = !(info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) &&
                (info.size > 0 || !(info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM));
            result[0] = frame ? AMediaCodec_releaseOutputBufferAtTime(codec, (size_t)index,
                origin + info.presentationTimeUs * 1000) : AMediaCodec_releaseOutputBuffer(codec, (size_t)index, false);
            if (result[0] != AMEDIA_OK) goto cleanup;
            if (frame) result[2]++;
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) result[3] = 1;
        } else if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *changed = AMediaCodec_getOutputFormat(codec);
            if (changed) AMediaFormat_delete(changed);
        } else if (index != AMEDIACODEC_INFO_TRY_AGAIN_LATER && index != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            result[0] = index; goto cleanup;
        }
    }
    if (!result[3]) result[0] = -10011;
    if (result[3]) wait_until(origin + pts[count - 1] * 1000 + 200000000LL);
cleanup:
    /* release 也可能进入驱动；父进程的看门狗仍可结束本测试进程。 */
    if (stage && !(*env)->ExceptionCheck(env)) phase(env, listener, stage, 7);
    media_status_t stopped = started ? AMediaCodec_stop(codec) : AMEDIA_OK;
    media_status_t deleted = codec ? AMediaCodec_delete(codec) : AMEDIA_OK;
    result[4] = stopped == AMEDIA_OK && deleted == AMEDIA_OK;
    if (!result[0] && stopped != AMEDIA_OK) { result[0] = stopped; result[5] = 7; }
    if (!result[0] && deleted != AMEDIA_OK) { result[0] = deleted; result[5] = 7; }
    if (format) AMediaFormat_delete(format);
    if (window) ANativeWindow_release(window);
    if (codec_name) (*env)->ReleaseStringUTFChars(env, name, codec_name);
    if (codec_mime) (*env)->ReleaseStringUTFChars(env, mime, codec_mime);
    if ((*env)->ExceptionCheck(env)) return NULL;
    jlongArray out = (*env)->NewLongArray(env, 6);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 6, result);
    return out;
}
