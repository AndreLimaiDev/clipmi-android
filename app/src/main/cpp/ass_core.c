// libass ke buffer RGBA untuk pratinjau dan overlay render. Pengaturan libass mengikuti filter `ass` FFmpeg yang dipakai
// desktop (vf_subtitles.c): fonts dir, ukuran frame = ukuran video, tanpa pixel aspect khusus, shaping bawaan libass.
#include "ass_core.h"

#include <ass/ass.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

struct ClipAss {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int width;
    int height;
};

static void message_cb(int level, const char *fmt, va_list args, void *data) {
    (void) data;
    if (level > 4) return; // 0-4 = error/warning/info penting; sisanya debug libass.
#ifdef __ANDROID__
    __android_log_vprint(level <= 1 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "ClipMiAss", fmt, args);
#else
    (void) fmt;
    (void) args;
#endif
}

ClipAss *clipmi_ass_create(int width, int height, const char *fonts_dir, const char *default_font) {
    ClipAss *ctx = calloc(1, sizeof(ClipAss));
    if (!ctx) return NULL;

    ctx->library = ass_library_init();
    if (!ctx->library) { free(ctx); return NULL; }
    ass_set_message_cb(ctx->library, message_cb, NULL);
    ass_set_extract_fonts(ctx->library, 1);
    ass_set_fonts_dir(ctx->library, fonts_dir);

    ctx->renderer = ass_renderer_init(ctx->library);
    if (!ctx->renderer) { ass_library_done(ctx->library); free(ctx); return NULL; }
    ctx->width = width;
    ctx->height = height;
    ass_set_frame_size(ctx->renderer, width, height);
    ass_set_storage_size(ctx->renderer, width, height);

    // Tanpa penyedia font sistem: semua font berasal dari fonts dir (font yang dibundel, sama dengan desktop);
    // default_font dipakai untuk glyph yang tidak ada di font gaya.
    ass_set_fonts(ctx->renderer, default_font, "sans-serif", ASS_FONTPROVIDER_NONE, NULL, 1);
    return ctx;
}

int clipmi_ass_load(ClipAss *ctx, const char *data, size_t size) {
    if (ctx->track) { ass_free_track(ctx->track); ctx->track = NULL; }
    char *buffer = malloc(size + 1);
    if (!buffer) return 0;
    memcpy(buffer, data, size);
    buffer[size] = 0;
    ctx->track = ass_read_memory(ctx->library, buffer, size, NULL);
    free(buffer);
    return ctx->track ? 1 : 0;
}

// Komposit ASS_Image ke buffer RGBA premultiplied (urutan byte R,G,B,A) dengan operator "over". Warna ASS_Image:
// 0xRRGGBBAA, AA = transparansi (0 = pekat).
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

int clipmi_ass_render(ClipAss *ctx, long long time_ms, uint8_t *pixels, uint32_t stride, int width, int height, int clear, int force) {
    if (!ctx || !ctx->track) return -1;
    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->renderer, ctx->track, time_ms, &changed);
    if (!changed && !force) return 0;
    if (clear) memset(pixels, 0, (size_t) stride * (size_t) height);
    blend(img, pixels, stride, width, height);
    return changed ? changed : 2;
}

void clipmi_ass_destroy(ClipAss *ctx) {
    if (!ctx) return;
    if (ctx->track) ass_free_track(ctx->track);
    ass_renderer_done(ctx->renderer);
    ass_library_done(ctx->library);
    free(ctx);
}

int clipmi_ass_version(void) {
    return ass_library_version();
}
