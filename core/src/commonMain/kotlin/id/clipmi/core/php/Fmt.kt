package id.clipmi.core.php

import kotlin.math.abs

/**
 * Pengganti `String.format(Locale.ROOT, …)` JVM yang berjalan di semua platform, dengan hasil sama persis:
 * - [fixed] = `%.Nf`: digit dari representasi terpendek [Double.toString] lalu dibulatkan HALF_UP, seperti
 *   `java.util.Formatter` (bukan pembulatan biner printf C).
 * - [pad] = `%0Nd`, [hex2] = `%02X`.
 */
object Fmt {
    fun fixed(value: Double, decimals: Int): String {
        require(decimals >= 0)
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val (digits, pointAt) = decimalDigits(abs(value))
        // Digit sampai `decimals` angka di belakang koma, lalu bulatkan HALF_UP dengan digit berikutnya.
        val keep = pointAt + decimals
        val wanted = StringBuilder()
        for (i in 0 until maxOf(keep, 0)) wanted.append(if (i < digits.length) digits[i] else '0')
        var number = if (wanted.isEmpty()) "0" else wanted.toString()
        val next = if (keep in digits.indices) digits[keep] else '0'
        if (keep >= 0 && next >= '5') number = increment(number)
        if (keep < 0) number = "0"
        // Bilangan bulat dan pecahan dari deretan digit (panjang pecahan = decimals).
        val padded = number.padStart(decimals + 1, '0')
        val intPart = padded.substring(0, padded.length - decimals).trimStart('0').ifEmpty { "0" }
        val fracPart = padded.substring(padded.length - decimals)
        val text = if (decimals == 0) intPart else "$intPart.$fracPart"
        return if (negative) "-$text" else text
    }

    /** `%0{width}d` untuk bilangan bulat (tanda minus di depan, seperti Formatter). */
    fun pad(value: Long, width: Int): String {
        val digits = abs(value).toString()
        val body = if (value < 0) digits.padStart(width - 1, '0') else digits.padStart(width, '0')
        return if (value < 0) "-$body" else body
    }

    fun pad(value: Int, width: Int): String = pad(value.toLong(), width)

    /** `%02X`. */
    fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    /**
     * Digit desimal tanpa titik dan posisi titiknya, dari [Double.toString] (representasi terpendek, sama di JVM dan
     * Kotlin/Native): 12.345 -> ("12345", 2), 1.0E-5 -> ("1", -4).
     */
    private fun decimalDigits(value: Double): Pair<String, Int> {
        val text = value.toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (e >= 0) text.substring(0, e) else text
        val exponent = if (e >= 0) text.substring(e + 1).toInt() else 0
        val dot = mantissa.indexOf('.')
        val intDigits = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fracDigits = if (dot >= 0) mantissa.substring(dot + 1) else ""
        var digits = intDigits + fracDigits
        var point = intDigits.length + exponent
        // Buang nol di depan supaya digit pertama bermakna.
        val lead = digits.indexOfFirst { it != '0' }
        if (lead < 0) return "0" to 1
        digits = digits.substring(lead)
        point -= lead
        return digits.trimEnd('0').ifEmpty { "0" } to point
    }

    private fun increment(number: String): String {
        val chars = number.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '9') {
                chars[i] = '0'
                i--
            } else {
                chars[i] = chars[i] + 1
                return chars.concatToString()
            }
        }
        return "1" + chars.concatToString()
    }
}

/** Code point Unicode tanpa API JVM (`Character.toChars`, `String.codePointAt`). */
object CodePoints {
    fun isValid(cp: Int): Boolean = cp in 0..0x10FFFF

    fun toText(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }

    fun at(s: String, index: Int): Int {
        val high = s[index]
        if (high.isHighSurrogate() && index + 1 < s.length) {
            val low = s[index + 1]
            if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
        return high.code
    }

    fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

    fun count(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += charCount(at(s, i))
            n++
        }
        return n
    }
}
