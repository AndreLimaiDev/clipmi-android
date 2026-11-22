// Lebar teks seperti imagettfbbox() GD (TextMeasure desktop), dipakai Android (JNI, text_measure.c) dan iOS.
#ifndef CLIPMI_TEXT_MEASURE_CORE_H
#define CLIPMI_TEXT_MEASURE_CORE_H

#include <stddef.h>

/** Lebar piksel `text` (UTF-8, `len` byte) dengan font `path` pada `ptsize` poin (96 dpi); -1 bila font tidak terbaca. */
double clipmi_text_width(const char *path, double ptsize, const unsigned char *text, size_t len);

#endif
