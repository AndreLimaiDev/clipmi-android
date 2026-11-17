// Jembatan JNI ke libass: menggambar file ASS ClipMI ke Bitmap ARGB_8888 (premultiplied) untuk pratinjau dan
// overlay render. Pengaturan libass mengikuti filter `ass` FFmpeg yang dipakai desktop (vf_subtitles.c):
// fonts dir, ukuran frame = ukuran video, tanpa pixel aspect khusus, shaping bawaan libass.
#include <android/bitmap.h>
#include <android/log.h>
#include <ass/ass.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "ClipMiAss"

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int width;
    int height;
} AssContext;

static void message_cb(int level, const char *fmt, va_list args, void *data) {
    (void) data;
    if (level > 4) return; // 0–4 = error/warning/info penting; sisanya debug libass.
    __android_log_vprint(level <= 1 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, fmt, args);
}

JNIEXPORT jlong JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeCreate(JNIEnv *env, jclass cls, jint width, jint height,
                                                             jstring fontsDir, jstring defaultFont) {
    (void) cls;
    AssContext *ctx = calloc(1, sizeof(AssContext));
    if (!ctx) return 0;

    ctx->library = ass_library_init();
    if (!ctx->library) { free(ctx); return 0; }
    ass_set_message_cb(ctx->library, message_cb, NULL);
    ass_set_extract_fonts(ctx->library, 1);

    const char *dir = (*env)->GetStringUTFChars(env, fontsDir, NULL);
    ass_set_fonts_dir(ctx->library, dir);
    (*env)->ReleaseStringUTFChars(env, fontsDir, dir);

    ctx->renderer = ass_renderer_init(ctx->library);
    if (!ctx->renderer) { ass_library_done(ctx->library); free(ctx); return 0; }
    ctx->width = width;
    ctx->height = height;
    ass_set_frame_size(ctx->renderer, width, height);
    ass_set_storage_size(ctx->renderer, width, height);

    // Tanpa penyedia font sistem: semua font berasal dari fonts dir (font yang dibundel, sama dengan desktop);
    // defaultFont dipakai untuk glyph yang tidak ada di font gaya.
    const char *font = defaultFont ? (*env)->GetStringUTFChars(env, defaultFont, NULL) : NULL;
    ass_set_fonts(ctx->renderer, font, "sans-serif", ASS_FONTPROVIDER_NONE, NULL, 1);
    if (font) (*env)->ReleaseStringUTFChars(env, defaultFont, font);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jboolean JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeLoadTrack(JNIEnv *env, jclass cls, jlong handle,
                                                                jbyteArray data) {
    (void) cls;
    AssContext *ctx = (AssContext *) (intptr_t) handle;
    if (ctx->track) { ass_free_track(ctx->track); ctx->track = NULL; }

    jsize size = (*env)->GetArrayLength(env, data);
    char *buffer = malloc((size_t) size + 1);
    if (!buffer) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *) buffer);
    buffer[size] = 0;
    ctx->track = ass_read_memory(ctx->library, buffer, (size_t) size, NULL);
    free(buffer);
    return ctx->track ? JNI_TRUE : JNI_FALSE;
}

// Komposit ASS_Image ke buffer RGBA premultiplied (urutan byte R,G,B,A = ANDROID_BITMAP_FORMAT_RGBA_8888)
// dengan operator "over". Warna ASS_Image: 0xRRGGBBAA, AA = transparansi (0 = pekat).
static void blend(const ASS_Image *img, uint8_t *pixels, uint32_t stride, int width, int height) {
    for (; img; img = img->next) {
        if (img->w <= 0 || img->h <= 0) continue;
        const uint32_t r = (img->color >> 24) & 0xFF;
        const uint32_t g = (img->color >> 16) & 0xFF;
        const uint32_t b = (img->color >> 8) & 0xFF;
        const uint32_t opacity = 255 - (img->color & 0xFF);
        if (opacity == 0) continue;

        for (int y = 0; y < img->h; y++) {
            const int dy = img->dst_y + y;
            if (dy < 0 || dy >= height) continue;
            const uint8_t *src = img->bitmap + (size_t) y * (size_t) img->stride;
            uint8_t *row = pixels + (size_t) dy * stride;
            for (int x = 0; x < img->w; x++) {
                const int dx = img->dst_x + x;
                if (dx < 0 || dx >= width || src[x] == 0) continue;
                // k = cakupan * opasitas, dibulatkan ke 0..255.
                const uint32_t k = (src[x] * opacity + 127) / 255;
                const uint32_t inv = 255 - k;
                uint8_t *p = row + (size_t) dx * 4;
                p[0] = (uint8_t) ((r * k + p[0] * inv + 127) / 255);
                p[1] = (uint8_t) ((g * k + p[1] * inv + 127) / 255);
                p[2] = (uint8_t) ((b * k + p[2] * inv + 127) / 255);
                p[3] = (uint8_t) ((255 * k + p[3] * inv + 127) / 255);
            }
        }
    }
}

// Menggambar frame pada timeMs ke bitmap. clear=true mengosongkan bitmap dulu (overlay transparan); false menumpuk
// di atas isi bitmap (misalnya latar hitam untuk dibandingkan dengan FFmpeg desktop).
// Hasil: -1 gagal, 0 tidak berubah dari panggilan sebelumnya (bitmap tidak disentuh), 1/2 berubah (posisi/isi).
JNIEXPORT jint JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeRender(JNIEnv *env, jclass cls, jlong handle, jlong timeMs,
                                                             jobject bitmap, jboolean clear, jboolean force) {
    (void) cls;
    AssContext *ctx = (AssContext *) (intptr_t) handle;
    if (!ctx->track) return -1;

    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->renderer, ctx->track, (long long) timeMs, &changed);
    if (!changed && !force) return 0;

    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -1;
    }
    if (clear) memset(pixels, 0, (size_t) info.stride * info.height);
    blend(img, pixels, info.stride, (int) info.width, (int) info.height);
    AndroidBitmap_unlockPixels(env, bitmap);
    return changed ? changed : 2;
}

JNIEXPORT void JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    AssContext *ctx = (AssContext *) (intptr_t) handle;
    if (!ctx) return;
    if (ctx->track) ass_free_track(ctx->track);
    ass_renderer_done(ctx->renderer);
    ass_library_done(ctx->library);
    free(ctx);
}

JNIEXPORT jstring JNICALL
Java_id_clipmi_android_nativebridge_AssRenderer_nativeVersion(JNIEnv *env, jclass cls) {
    (void) cls;
    char buffer[32];
    int v = ass_library_version();
    // LIBASS_VERSION ditulis sebagai digit heksadesimal, misalnya 0x01705000 = 0.17.5.
    snprintf(buffer, sizeof buffer, "%x.%x.%x", (v >> 28) & 0xF, (v >> 20) & 0xFF, (v >> 12) & 0xFF);
    return (*env)->NewStringUTF(env, buffer);
}
