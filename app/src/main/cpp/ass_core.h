// libass untuk pratinjau ClipMI: file ASS (AssWriter) digambar ke buffer RGBA premultiplied. Dipakai Android (JNI,
// ass_jni.c) dan iOS (Swift), supaya subtitle pratinjau di kedua platform digambar dengan kode yang sama.
#ifndef CLIPMI_ASS_CORE_H
#define CLIPMI_ASS_CORE_H

#include <stddef.h>
#include <stdint.h>

typedef struct ClipAss ClipAss;

/** Renderer seukuran width x height; font hanya dari fonts_dir (default_font boleh NULL). NULL bila gagal. */
ClipAss *clipmi_ass_create(int width, int height, const char *fonts_dir, const char *default_font);

/** Memuat (mengganti) trek ASS dari teks UTF-8. 1 = berhasil. */
int clipmi_ass_load(ClipAss *ctx, const char *data, size_t size);

/**
 * Menggambar frame pada time_ms ke pixels (RGBA premultiplied, urutan byte R,G,B,A). clear = kosongkan dulu; force =
 * gambar walau tidak berubah. Hasil: -1 gagal, 0 tidak berubah (pixels tidak disentuh), 1/2 berubah.
 */
int clipmi_ass_render(ClipAss *ctx, long long time_ms, uint8_t *pixels, uint32_t stride, int width, int height, int clear, int force);

void clipmi_ass_destroy(ClipAss *ctx);

/** Versi libass sebagai angka LIBASS_VERSION. */
int clipmi_ass_version(void);

#endif
