package id.clipmi.core.render

import id.clipmi.core.php.Php
import id.clipmi.core.transcript.Word
import id.clipmi.core.transcript.WordTimer
import kotlinx.serialization.Serializable

/**
 * Koreksi subtitle klip (kolom `subtitle_lines`, format sama dengan desktop): [from]/[to] = rentang klip saat
 * dikoreksi, supaya baris yang sengaja dihapus tidak muncul lagi dari transkrip.
 */
@Serializable
data class SubtitleEdits(val from: Double, val to: Double, val lines: List<SubtitleLine>)

/** Waktu per kata satu segmen transkrip (kolom `words`; null bila segmen tanpa waktu per kata). */
data class SegmentWords(val start: Double, val end: Double, val words: List<Word>?)

/**
 * Menyusun baris subtitle klip dari waktu per kata, dan menyimpan koreksi pengguna (padanan `App\Render\SubtitleBuilder`
 * desktop). Waktu baris absolut di video sumber.
 */
object SubtitleBuilder {
    /** Jeda bicara (detik) yang memulai baris baru. */
    private const val PAUSE = 0.6

    /** Celah antar-baris yang lebih pendek dari ini ditutup, supaya subtitle tidak berkedip. */
    private const val BRIDGE_GAP = 0.3

    private val SENTENCE_END = Regex("[.!?…][\"'”’)]*$")

    /** Segmen harus urut menurut `sequence`, seperti relasi desktop. */
    fun linesFor(segments: List<SegmentWords>, start: Double, end: Double, style: CaptionStyle, edited: Boolean, edits: SubtitleEdits?): List<SubtitleLine> {
        if (!edited || edits == null) return fromTranscript(segments, start, end, style)

        // Baris koreksi dipertahankan selama masih di dalam rentang klip; hanya bagian klip di luar rentang saat
        // dikoreksi (karena timestamp digeser keluar) yang diisi dari transkrip.
        val kept = edits.lines.filter { middle(it) >= start && middle(it) <= end }
        return bridge(
            fromTranscript(segments, start, minOf(edits.from, end), style) +
                kept +
                fromTranscript(segments, maxOf(edits.to, start), end, style),
        )
    }

    /**
     * Koreksi teks: indeks baris (dari [linesFor]) → teks baru, urut seperti dimasukkan. Baris yang dikosongkan
     * dihapus. Hasilnya disimpan ke klip sebagai koreksi baru (dengan rentang klip saat ini).
     */
    fun applyEdits(current: List<SubtitleLine>, texts: List<Pair<Int, String>>, start: Double, end: Double): SubtitleEdits {
        // Indeks urut naik: LinkedHashMap menjaga urutan seperti sortedMapOf (yang hanya ada di JVM).
        val lines = current.withIndex().associateTo(linkedMapOf()) { it.index to it.value }
        for ((index, raw) in texts) {
            val line = lines[index] ?: continue
            val text = Php.trim(WordTimer.WHITESPACE.replace(raw, " "))
            if (text.isEmpty()) lines.remove(index) else if (text != line.text) lines[index] = editLine(line, text)
        }
        return SubtitleEdits(start, end, lines.values.toList())
    }

    /**
     * Jika jumlah kata sama, waktu tiap kata dipertahankan (hanya ejaan/tanda baca yang berubah); jika berubah,
     * rentang baris dibagi ulang ke kata-kata baru.
     */
    fun editLine(line: SubtitleLine, text: String): SubtitleLine {
        val tokens = text.split(' ')
        val old = line.words.orEmpty()
        val words = if (tokens.size == old.size) old.zip(tokens) { word, token -> word.copy(text = token) } else WordTimer.distribute(text, line.start, line.end)
        return line.copy(text = text, words = words)
    }

    private fun fromTranscript(segments: List<SegmentWords>, from: Double, to: Double, style: CaptionStyle): List<SubtitleLine> {
        if (to - from < 0.05) return emptyList()
        val words = segments
            .filter { it.end > from && it.start < to }
            .flatMap { it.words.orEmpty() }
            // Kata dihitung masuk jika titik tengahnya ada di dalam rentang.
            .filter { (it.start + it.end) / 2 >= from && (it.start + it.end) / 2 < to }
        return bridge(group(words, style))
    }

    /** Jumlah kata dan karakter per tampilan mengikuti gaya subtitle klip. */
    private fun group(words: List<Word>, style: CaptionStyle): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        var current = mutableListOf<Word>()
        val maxChars = style.maxCharsPerLine()
        val perLine = style.wordsPerLine ?: 0

        for (word in words) {
            if (current.isNotEmpty()) {
                val last = current.last()
                val chars = Php.mbStrlen((current.map { it.text } + word.text).joinToString(" "))
                if (current.size >= perLine || chars > maxChars || word.start - last.end >= PAUSE || SENTENCE_END.containsMatchIn(last.text)) {
                    lines += line(current)
                    current = mutableListOf()
                }
            }
            current += word
        }
        if (current.isNotEmpty()) lines += line(current)
        return lines
    }

    private fun bridge(lines: List<SubtitleLine>): List<SubtitleLine> {
        val result = lines.toMutableList()
        for (i in lines.indices) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1) ?: continue
            if (next.start > line.end && next.start - line.end < BRIDGE_GAP) result[i] = result[i].copy(end = next.start)
        }
        return result
    }

    private fun line(words: List<Word>) = SubtitleLine(
        start = Php.round(words.first().start, 3),
        end = Php.round(words.last().end, 3),
        text = words.joinToString(" ") { it.text },
        words = words.toList(),
    )

    private fun middle(line: SubtitleLine) = (line.start + line.end) / 2
}
