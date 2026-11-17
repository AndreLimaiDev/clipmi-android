package id.clipmi.core.php

import id.clipmi.core.transcript.WordTimer

/** Helper string Laravel (Illuminate\Support\Str) yang dipakai kode desktop. */
object Str {
    /**
     * `Str::limit($value, $limit, $end)` Laravel 13 (tanpa preserveWords): lebar dihitung mb_strwidth (karakter lebar
     * Asia Timur = 2), dipotong mb_strimwidth, lalu rtrim, lalu ditambah [end].
     */
    fun limit(value: String, limit: Int = 100, end: String = "..."): String {
        if (width(value) <= limit) return value
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val w = charWidth(cp)
            if (used + w > limit) break
            out.appendCodePoint(cp)
            used += w
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd { it in " \t\n\r\u0000\u000B" } + end
    }

    /** `Str::squish()`: trim Unicode lalu spasi berderet jadi satu. */
    fun squish(value: String): String = SQUISH.replace(trim(value), " ")

    /** `Str::trim()` tanpa charlist: spasi Unicode, ZWSP, BOM, LRM, dan karakter trim() PHP di kedua ujung. */
    fun trim(value: String): String = TRIM.replace(value, "")

    private const val TRIM_CLASS = "[${WordTimer.SPACE_CLASS}\\uFEFF\\u200B\\u200E \\n\\r\\t\\u000B\\u0000]"
    private val TRIM = Regex("^$TRIM_CLASS+|$TRIM_CLASS+$")
    private val SQUISH = Regex("(?:[${WordTimer.SPACE_CLASS}]|\\u3164|\\u1160)+")

    private fun width(value: String): Int {
        var total = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            total += charWidth(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    /** Lebar mb_strwidth: 2 untuk karakter lebar/penuh Asia Timur, selain itu 1. */
    private fun charWidth(cp: Int): Int = if (
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
        cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x2FFFD ||
        cp in 0x30000..0x3FFFD
    ) 2 else 1
}
