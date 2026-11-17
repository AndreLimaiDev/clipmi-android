package id.clipmi.core.transcript

import id.clipmi.core.php.HtmlEntities
import id.clipmi.core.php.Php

/** Padanan `App\Transcript\Timecode`. */
object Timecode {
    /** Cocok untuk 01:02:03,456 (SRT), 01:02:03.456 dan 02:03.456 (VTT); tanpa grup penangkap. */
    const val PATTERN = """(?:\d+:)?\d{1,2}:\d{1,2}(?:[.,]\d{1,3})?"""

    private val FULL = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$""")

    fun toSeconds(value: String): Double {
        val m = FULL.find(Php.trim(value)) ?: throw TranscriptParseException("Timestamp tidak valid: $value")
        val (h, min, s, ms) = m.destructured
        val millis = if (ms.isEmpty()) 0 else ms.padEnd(3, '0').toInt()
        return Php.round(
            (h.ifEmpty { "0" }.toLong() * 3600 + min.toInt() * 60 + s.toInt()).toDouble() + millis / 1000.0,
            3,
        )
    }

    /** Format tampilan: "02:03", atau "1:02:03" jika lebih dari satu jam. */
    fun format(seconds: Double): String {
        val total = kotlin.math.floor(seconds).toLong()
        val hours = total / 3600
        val minutes = total % 3600 / 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, total % 60) else "%02d:%02d".format(minutes, total % 60)
    }
}

/** Padanan `App\Transcript\WordTimer`. */
object WordTimer {
    /**
     * `\s` PCRE2 dengan /u (PHP menyalakan PCRE2_UCP): tab, \n, \v, \f, \r, dan kategori Unicode Z. Ditulis eksplisit
     * karena regex Android (ICU) tidak mengenal flag (?U), sedangkan `\s` JVM tanpa flag hanya ASCII.
     */
    internal const val SPACE_CLASS = """\t\n\u000B\f\r\p{Z}"""

    /** `preg_split('/\s+/u', ...)`. */
    internal val WHITESPACE = Regex("[$SPACE_CLASS]+")

    fun split(text: String): List<String> = Php.trim(text).split(WHITESPACE).filter { it.isNotEmpty() }

    /** Memecah teks menjadi kata dan membagi rentang waktu proporsional terhadap panjang kata. */
    fun distribute(text: String, start: Double, end: Double): List<Word> {
        val tokens = split(text)
        if (tokens.isEmpty()) return emptyList()

        val duration = maxOf(0.0, end - start)
        val weights = tokens.map { maxOf(1, Php.mbStrlen(it)) }
        val total = weights.sum()

        var cumulative = 0
        var cursor = start
        return tokens.mapIndexed { i, token ->
            cumulative += weights[i]
            // Dari bobot kumulatif agar kata terakhir selalu berakhir tepat di $end.
            val wordEnd = start + duration * cumulative / total
            Word(token, Php.round(cursor, 3), Php.round(wordEnd, 3)).also { cursor = wordEnd }
        }
    }
}

/** Padanan `App\Transcript\TextCleaner`. */
object TextCleaner {
    private val ANNOTATION = Regex("""\[[^\]]*]|♪""")
    /** `[\s\x{00A0}\x{200B}\x{FEFF}]+` dengan /u. */
    private val UNICODE_SPACES = Regex("[${WordTimer.SPACE_CLASS}\\u200B\\uFEFF]+")

    /** Membuang tag format, anotasi non-ucapan seperti [Musik], entitas HTML, dan spasi berlebih. */
    fun clean(text: String): String {
        // Tag dibuang sebelum entitas didekode, supaya "&lt;" di teks tetap menjadi "<" biasa.
        var result = decodeEntities(Php.stripTags(text))
        result = ANNOTATION.replace(result, " ")
        return Php.trim(UNICODE_SPACES.replace(result, " "))
    }

    /**
     * Versi [clean] untuk data per kata. Anotasi dalam kurung siku bisa terpecah ke beberapa kata, misalnya
     * "[Tepuk" "tangan]", jadi dilacak lintas kata.
     */
    fun cleanWords(words: List<Word>): List<Word> {
        val result = mutableListOf<Word>()
        var insideBracket = false
        for (word in words) {
            if (insideBracket || Php.ltrim(word.text).startsWith("[")) {
                insideBracket = !word.text.contains(']')
                continue
            }
            // Satu "kata" bisa berisi spasi setelah dibersihkan (misalnya dari &nbsp;), jadi dipecah ulang.
            result += WordTimer.distribute(clean(word.text), word.start, word.end)
        }
        return result
    }

    private fun decodeEntities(text: String): String {
        // Subtitle YouTube kadang ter-encode dua kali, misalnya "&amp;#39;".
        var current = text
        repeat(2) {
            val decoded = HtmlEntities.decode(current)
            if (decoded == current) return current
            current = decoded
        }
        return current
    }
}
