package id.clipmi.core.php

/**
 * `html_entity_decode($s, ENT_QUOTES | ENT_HTML5, 'UTF-8')` PHP 8.3. Entitas bernama wajib diakhiri ";" (mode HTML5),
 * tabel nama di resources/id/clipmi/core/html5-entities.tsv (disaring dari PHP desktop). Entitas angka hanya didekode
 * bila code point-nya diizinkan HTML5 (bukan kontrol C0/C1, surrogate, atau noncharacter).
 */
object HtmlEntities {
    private val named: Map<String, String> by lazy {
        val stream = HtmlEntities::class.java.getResourceAsStream("/id/clipmi/core/html5-entities.tsv")
            ?: error("html5-entities.tsv tidak ada")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
                val (name, cps) = line.split('\t')
                name to cps.split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }
            }
        }
    }

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val decoded = entityAt(s, i)
                if (decoded != null) {
                    out.append(decoded.first)
                    i = decoded.second
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Entitas di posisi [start] ("&"): teks hasil dan indeks sesudah ";", atau null bila tidak sah. */
    private fun entityAt(s: String, start: Int): Pair<String, Int>? {
        var i = start + 1
        if (i >= s.length) return null
        if (s[i] == '#') {
            i++
            val hex = i < s.length && (s[i] == 'x' || s[i] == 'X')
            if (hex) i++
            val digitsStart = i
            while (i < s.length && (if (hex) s[i].isHexDigitAscii() else s[i] in '0'..'9')) i++
            if (i == digitsStart || i >= s.length || s[i] != ';') return null
            val code = s.substring(digitsStart, i).toBigInteger(if (hex) 16 else 10)
            if (code > 0x10FFFF.toBigInteger()) return null
            val cp = code.toInt()
            if (!allowedHtml5(cp) || cp == 0x0D) return null
            return String(Character.toChars(cp)) to i + 1
        }
        val nameStart = i
        while (i < s.length && s[i].isLetterOrDigitAscii()) i++
        if (i == nameStart || i >= s.length || s[i] != ';') return null
        val value = named[s.substring(nameStart, i)] ?: return null
        return value to i + 1
    }

    /** unicode_cp_is_allowed() PHP untuk ENT_HTML5. */
    private fun allowedHtml5(cp: Int): Boolean =
        cp in 0x20..0x7E ||
            (cp in 0x09..0x0D && cp != 0x0B) ||
            cp in 0xA0..0xD7FF ||
            (cp in 0xE000..0x10FFFF && (cp and 0xFFFF) < 0xFFFE && (cp < 0xFDD0 || cp > 0xFDEF))

    private fun Char.isHexDigitAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isLetterOrDigitAscii() = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'
}
