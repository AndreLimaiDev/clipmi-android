package id.clipmi.android.nativebridge

import java.io.File

/** Lebar teks dengan FreeType persis seperti `imagettfbbox()` GD desktop (lihat cpp/text_measure.c). */
object TextMeasure {
    init {
        System.loadLibrary("clipmi_native")
    }

    /** @return lebar dalam piksel pada [points] poin (96 dpi), atau null bila font tidak terbaca. */
    fun width(font: File, points: Double, text: String): Double? =
        nativeWidth(font.absolutePath, points, text.toByteArray(Charsets.UTF_8)).takeIf { it >= 0 }

    @JvmStatic private external fun nativeWidth(path: String, points: Double, text: ByteArray): Double
}
