// JNI untuk id.clipmi.android.nativebridge.TextMeasure; perhitungannya di text_measure_core.c (dipakai juga iOS).
#include <jni.h>
#include <stdlib.h>

#include "text_measure_core.h"

JNIEXPORT jdouble JNICALL
Java_id_clipmi_android_nativebridge_TextMeasure_nativeWidth(JNIEnv *env, jclass cls, jstring jpath, jdouble ptsize, jbyteArray jtext) {
    (void) cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    jsize len = (*env)->GetArrayLength(env, jtext);
    unsigned char *text = malloc(len + 1);
    (*env)->GetByteArrayRegion(env, jtext, 0, len, (jbyte *) text);
    text[len] = 0;
    double width = clipmi_text_width(path, ptsize, text, (size_t) len);
    free(text);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return width;
}
