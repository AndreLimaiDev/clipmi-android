package id.clipmi.core.php

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Fungsi PHP yang perilakunya harus ditiru persis agar hasil Kotlin identik dengan ClipMI desktop (PHP 8.3).
 */
object Php {
    /** Karakter yang dibuang `trim()` PHP tanpa argumen kedua: spasi, \t, \n, \r, \0, \x0B. */
    private const val TRIM_CHARS = " \t\n\r\u0000\u000B"

    fun trim(s: String): String = s.trim { it in TRIM_CHARS }

    fun ltrim(s: String): String = s.trimStart { it in TRIM_CHARS }

    /**
     * `round($value, $places)` PHP 8.3 (math.c `_php_math_round`, mode PHP_ROUND_HALF_UP): nilai dibulatkan dulu ke
     * 15 digit signifikan ("pre-rounding"), jadi round(2.675, 2) = 2.68 walau 2.675 di double sedikit di bawahnya.
     */
    fun round(value: Double, places: Int): Double {
        if (!value.isFinite() || value == 0.0) return value

        val precisionPlaces = 14 - floor(log10(abs(value))).toInt()
        val f1 = intpow10(abs(places))
        var tmp: Double

        if (precisionPlaces > places && precisionPlaces - 15 < places) {
            var usePrecision = if (precisionPlaces < -(4 * 15)) -(4 * 15) else precisionPlaces
            // Pre-round ke presisi double (hasilnya selalu "sesuatu × 1e14").
            tmp = roundHelper(basic(value, usePrecision))
            usePrecision = maxOf(-(4 * 15), places - usePrecision)
            tmp /= intpow10(abs(usePrecision))
        } else {
            tmp = if (places >= 0) value * f1 else value / f1
            if (abs(tmp) >= 1e15) return value
        }

        tmp = roundHelper(tmp)

        return if (places > 0) tmp / f1 else tmp * f1
    }

    /** php_round_helper PHP_ROUND_HALF_UP: setengah dibulatkan menjauhi nol. */
    private fun roundHelper(value: Double): Double = if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)

    private fun basic(value: Double, places: Int): Double {
        val f1 = intpow10(abs(places))
        return if (places >= 0) value * f1 else value / f1
    }

    private val POWERS = DoubleArray(23) { 10.0.pow(it) }

    private fun intpow10(power: Int): Double = if (power < 0 || power > 22) 10.0.pow(power) else POWERS[power]

    /**
     * `strip_tags()` PHP untuk teks subtitle: "<" yang diikuti spasi bukan tag dan tetap ditulis; tag (termasuk tanda
     * kutip di dalamnya) dan komentar `<!-- -->` dibuang; tag yang tidak ditutup membuang sisa teks.
     */
    fun stripTags(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }
            val next = s.getOrNull(i + 1)
            if (next != null && next.isPhpSpace()) {
                out.append(c)
                i++
                continue
            }
            if (s.startsWith("<!--", i)) {
                val end = s.indexOf("-->", i + 4)
                i = if (end < 0) s.length else end + 3
                continue
            }
            // Di dalam tag: ">" di dalam tanda kutip tidak menutup tag.
            var quote: Char? = null
            i++
            while (i < s.length) {
                val t = s[i]
                if (quote != null) {
                    if (t == quote) quote = null
                } else if (t == '"' || t == '\'') {
                    quote = t
                } else if (t == '>') {
                    i++
                    break
                }
                i++
            }
        }
        return out.toString()
    }

    /** isspace() C (lokal "C"): spasi, \t, \n, \v, \f, \r. */
    private fun Char.isPhpSpace() = this == ' ' || this in '\t'..'\r'

    /** `mb_strlen()`: jumlah code point, bukan unit UTF-16. */
    fun mbStrlen(s: String): Int = s.codePointCount(0, s.length)

    /** `mb_strtolower()` PHP 8.3 (pemetaan huruf kecil Unicode penuh). */
    fun mbStrtolower(s: String): String = s.lowercase(java.util.Locale.ROOT)

    /** `mb_strtoupper()` PHP 8.3 (pemetaan huruf besar Unicode penuh, misalnya ß → SS). */
    fun mbStrtoupper(s: String): String = s.uppercase(java.util.Locale.ROOT)

    /** `(int) round($value)`. */
    fun roundInt(value: Double): Int = round(value, 0).toInt()

    /** `number_format($value, $decimals, '.', '')`: dibulatkan seperti round() PHP, tanpa pemisah ribuan. */
    fun numberFormat(value: Double, decimals: Int): String {
        val rounded = round(value, decimals)
        val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", rounded)
        // PHP 8 tidak menulis "-0" untuk nilai yang dibulatkan menjadi nol.
        return if (text.startsWith("-") && text.trimStart('-').all { it == '0' || it == '.' }) text.substring(1) else text
    }

    /** `substr($s, $start, $length)` untuk ASCII: indeks di luar panjang menghasilkan "" alih-alih galat. */
    fun substr(s: String, start: Int, length: Int? = null): String {
        val from = if (start < 0) maxOf(0, s.length + start) else minOf(start, s.length)
        val to = when {
            length == null -> s.length
            length < 0 -> maxOf(from, s.length + length)
            else -> minOf(s.length, from + length)
        }
        return s.substring(from, to)
    }
}
