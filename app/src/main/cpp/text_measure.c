// Lebar teks seperti imagettfbbox() GD (dipakai TextMeasure desktop untuk kotak hook bersudut membulat): FreeType,
// 96 dpi, glyph dimuat FT_LOAD_DEFAULT (ber-hinting), kerning bawaan, kotak tiap glyph di-gridfit lalu digabung, dan
// pena maju sebesar horiAdvance. Semua nilai sudah kelipatan piksel, jadi lebar = (xMax - xMin) / 64.

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static FT_Library library = NULL;
static FT_Face face = NULL;
static char *face_path = NULL;

/** Satu code point dari UTF-8; *i maju. Urutan tidak sah dibaca sebagai satu byte. */
static unsigned long next_codepoint(const unsigned char *s, size_t len, size_t *i) {
    unsigned char c = s[*i];
    int extra = c < 0x80 ? 0 : (c & 0xE0) == 0xC0 ? 1 : (c & 0xF0) == 0xE0 ? 2 : (c & 0xF8) == 0xF0 ? 3 : -1;
    if (extra <= 0 || *i + extra >= len) { (*i)++; return c; }
    unsigned long cp = c & (0x3F >> extra);
    for (int k = 1; k <= extra; k++) {
        if ((s[*i + k] & 0xC0) != 0x80) { (*i)++; return c; }
        cp = (cp << 6) | (s[*i + k] & 0x3F);
    }
    *i += extra + 1;
    return cp;
}

JNIEXPORT jdouble JNICALL
Java_id_clipmi_android_nativebridge_TextMeasure_nativeWidth(JNIEnv *env, jclass cls, jstring jpath, jdouble ptsize, jbyteArray jtext) {
    (void) cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    jsize len = (*env)->GetArrayLength(env, jtext);
    unsigned char *text = malloc(len + 1);
    (*env)->GetByteArrayRegion(env, jtext, 0, len, (jbyte *) text);
    text[len] = 0;
    double width = -1;

    pthread_mutex_lock(&lock);
    if (!library && FT_Init_FreeType(&library)) goto done;
    if (!face_path || strcmp(face_path, path) != 0) {
        if (face) FT_Done_Face(face);
        face = NULL;
        free(face_path);
        face_path = NULL;
        if (FT_New_Face(library, path, 0, &face)) goto done;
        face_path = strdup(path);
    }
    if (FT_Set_Char_Size(face, 0, (FT_F26Dot6) (ptsize * 64), 96, 96)) goto done;

    int kerning = FT_HAS_KERNING(face);
    FT_Pos pen = 0, xmin = 0, xmax = 0;
    FT_UInt previous = 0;
    int first = 1;
    for (size_t i = 0; i < (size_t) len;) {
        unsigned long cp = next_codepoint(text, len, &i);
        FT_UInt glyph = FT_Get_Char_Index(face, cp);
        if (kerning && previous && glyph) {
            FT_Vector delta;
            if (!FT_Get_Kerning(face, previous, glyph, FT_KERNING_DEFAULT, &delta)) pen += delta.x;
        }
        if (FT_Load_Glyph(face, glyph, FT_LOAD_DEFAULT)) continue;
        FT_Glyph image;
        if (FT_Get_Glyph(face->glyph, &image)) continue;
        FT_BBox box;
        FT_Glyph_Get_CBox(image, FT_GLYPH_BBOX_GRIDFIT, &box);
        FT_Done_Glyph(image);
        if (first || box.xMin + pen < xmin) xmin = box.xMin + pen;
        if (first || box.xMax + pen > xmax) xmax = box.xMax + pen;
        first = 0;
        pen += face->glyph->metrics.horiAdvance;
        previous = glyph;
    }
    width = first ? 0 : (double) (xmax - xmin) / 64.0;

done:
    pthread_mutex_unlock(&lock);
    free(text);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return width;
}
