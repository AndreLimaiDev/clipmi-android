// JNI untuk id.clipmi.android.nativebridge.AssRenderer: Bitmap ARGB_8888 (byte R,G,B,A premultiplied) dikunci lalu
// digambar oleh ass_core.c (kode yang sama dipakai iOS).
#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "ass_core.h"

JNIEXPORT jlong JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeCreate(JNIEnv *env, jclass cls, jint width, jint height,
                                                             jstring fontsDir, jstring defaultFont) {
    (void) cls;
    const char *dir = (*env)->GetStringUTFChars(env, fontsDir, NULL);
    const char *font = defaultFont ? (*env)->GetStringUTFChars(env, defaultFont, NULL) : NULL;
    ClipAss *ctx = clipmi_ass_create(width, height, dir, font);
    (*env)->ReleaseStringUTFChars(env, fontsDir, dir);
    if (font) (*env)->ReleaseStringUTFChars(env, defaultFont, font);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jboolean JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeLoadTrack(JNIEnv *env, jclass cls, jlong handle,
                                                                jbyteArray data) {
    (void) cls;
    ClipAss *ctx = (ClipAss *) (intptr_t) handle;
    jsize size = (*env)->GetArrayLength(env, data);
    char *buffer = malloc((size_t) size + 1);
    if (!buffer) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *) buffer);
    int ok = clipmi_ass_load(ctx, buffer, (size_t) size);
    free(buffer);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Menggambar frame pada timeMs ke bitmap. clear=true mengosongkan bitmap dulu (overlay transparan); false menumpuk
// di atas isi bitmap. Hasil: -1 gagal, 0 tidak berubah (bitmap tidak disentuh), 1/2 berubah (posisi/isi).
JNIEXPORT jint JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeRender(JNIEnv *env, jclass cls, jlong handle, jlong timeMs,
                                                             jobject bitmap, jboolean clear, jboolean force) {
    (void) cls;
    ClipAss *ctx = (ClipAss *) (intptr_t) handle;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -1;
    }
    int result = clipmi_ass_render(ctx, (long long) timeMs, pixels, info.stride, (int) info.width, (int) info.height, clear, force);
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

JNIEXPORT void JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    clipmi_ass_destroy((ClipAss *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeVersion(JNIEnv *env, jclass cls) {
    (void) cls;
    char buffer[32];
    int v = clipmi_ass_version();
    // LIBASS_VERSION ditulis sebagai digit heksadesimal, misalnya 0x01705000 = 0.17.5.
    snprintf(buffer, sizeof buffer, "%x.%x.%x", (v >> 28) & 0xF, (v >> 20) & 0xFF, (v >> 12) & 0xFF);
    return (*env)->NewStringUTF(env, buffer);
}
