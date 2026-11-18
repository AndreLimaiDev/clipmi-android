package id.clipmi.core.render

import id.clipmi.core.php.Php
import id.clipmi.core.transcript.Word

/**
 * Subtitle contoh untuk pratinjau halaman Gaya Subtitle (padanan `App\Render\CaptionPreview` desktop): kalimat pendek
 * dengan waktu per kata, ditulis AssWriter yang sama dengan render, lalu digambar libass. Jadi huruf, ukuran, letak,
 * sorotan karaoke, kata kunci, animasi, dan kotak latar sama dengan video hasil.
 */
object CaptionPreview {
    val WORDS = listOf("Rasulullah", "bersabda,", "orang", "yang", "sabar", "itu", "dicintai", "Allah.")

    /** Contoh kata kunci, diwarnai bila gaya mengaktifkan warna kata kunci. */
    val KEYWORDS = listOf("sabar")

    private const val WORD_SECONDS = 0.45
    private const val LEAD_SECONDS = 0.3
    private const val TAIL_SECONDS = 0.8

    data class Preview(val ass: String, val duration: Double, val width: Int, val height: Int)

    fun build(ass: AssWriter, style: CaptionStyle): Preview {
        val lines = mutableListOf<SubtitleLine>()
        var time = LEAD_SECONDS
        for (chunk in WORDS.chunked(maxOf(1, style.wordsPerLine ?: 4))) {
            val words = mutableListOf<Word>()
            val start = time
            for (word in chunk) {
                words += Word(word, Php.round(time, 3), Php.round(time + WORD_SECONDS, 3))
                time += WORD_SECONDS
            }
            lines += SubtitleLine(Php.round(start, 3), Php.round(time, 3), chunk.joinToString(" "), words)
        }
        val duration = Php.round(time + TAIL_SECONDS, 3)
        return Preview(ass.build(lines, duration, null, style, keywords = KEYWORDS), duration, AssWriter.WIDTH, AssWriter.HEIGHT)
    }
}
