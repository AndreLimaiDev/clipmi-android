package id.clipmi.core.transcript

import id.clipmi.core.php.Php

/** Mengubah cue mentah dari parser menjadi segmen kalimat (padanan `App\Transcript\TranscriptTidier`). */
class TranscriptTidier(
    private val maxSegmentSeconds: Double = 15.0,
    private val pauseSeconds: Double = 1.0,
) {
    fun tidy(input: List<Cue>): List<Segment> {
        var cues = input.map(::clean).filter { it.text.isNotEmpty() }.sortedBy { it.start }
        cues = removeRepeatedText(cues)
        cues = normalizeTimestamps(cues)
        cues = cues.map(::ensureWords)
        return mergeIntoSentences(cues)
    }

    private fun clean(cue: Cue): Cue {
        if (!cue.words.isNullOrEmpty()) {
            val words = TextCleaner.cleanWords(cue.words)
            // Bila anotasi seperti [Musik] di awal cue dibuang, cue dimulai dari kata pertama yang tersisa.
            val start = words.firstOrNull()?.start ?: cue.start
            return Cue(start, cue.end, words.joinToString(" ") { it.text }, words.ifEmpty { null }, cue.wordsEstimated)
        }
        return Cue(cue.start, cue.end, TextCleaner.clean(cue.text), null, cue.wordsEstimated)
    }

    /**
     * Subtitle bergulir mengulang teks dari cue sebelumnya. Cue yang seluruhnya pengulangan dibuang, dan pengulangan
     * di awal cue dipotong. Pengulangan satu kata saja dibiarkan karena bisa jadi memang diucapkan dua kali.
     */
    private fun removeRepeatedText(cues: List<Cue>): List<Cue> {
        val kept = mutableListOf<Cue>()
        var recent = listOf<String>()

        for (original in cues) {
            var cue = original
            var tokens = cue.tokens().map(::normalizeToken)
            val overlap = overlap(recent, tokens)

            // Cue 10 milidetik di VTT otomatis YouTube hanya menampilkan ulang baris sebelumnya.
            if (overlap == tokens.size && (overlap >= 2 || cue.duration < 0.05)) continue

            if (overlap >= 3) {
                cue = dropLeadingWords(cue, overlap)
                tokens = tokens.drop(overlap)
            }

            kept += cue
            recent = (recent + tokens).takeLast(REPEAT_WINDOW)
        }
        return kept
    }

    /** Panjang terbesar k di mana k kata terakhir [previous] sama dengan k kata pertama [current]. */
    private fun overlap(previous: List<String>, current: List<String>): Int {
        for (k in minOf(previous.size, current.size) downTo 1) {
            if (previous.subList(previous.size - k, previous.size) == current.subList(0, k)) return k
        }
        return 0
    }

    private fun normalizeToken(token: String): String = NON_ALNUM.replace(Php.mbStrtolower(token), "")

    private fun dropLeadingWords(cue: Cue, count: Int): Cue {
        val text = cue.tokens().drop(count).joinToString(" ")
        if (!cue.words.isNullOrEmpty()) {
            val words = cue.words.drop(count)
            return Cue(words[0].start, cue.end, text, words, cue.wordsEstimated)
        }
        // Tanpa waktu per kata, awal cue tetap dipakai: kata baru mulai diucapkan saat cue muncul.
        return Cue(cue.start, cue.end, text, null, cue.wordsEstimated)
    }

    /**
     * Akhir cue yang menabrak awal cue berikutnya dipotong agar tepat bertemu. Cue tanpa durasi diberi durasi
     * perkiraan dari jumlah katanya.
     */
    private fun normalizeTimestamps(cues: List<Cue>): List<Cue> = cues.mapIndexed { i, cue ->
        val nextStart = cues.getOrNull(i + 1)?.start
        var end = cue.end
        if (nextStart != null && end > nextStart) end = nextStart
        if (end <= cue.start) {
            val estimated = cue.start + maxOf(0.5, cue.tokens().size * 0.4)
            end = if (nextStart != null && nextStart > cue.start) minOf(estimated, nextStart) else estimated
        }
        end = Php.round(end, 3)
        Cue(cue.start, end, cue.text, clampWords(cue.words, cue.start, end), cue.wordsEstimated)
    }

    private fun clampWords(words: List<Word>?, start: Double, end: Double): List<Word>? = words?.map { word ->
        val wordStart = minOf(maxOf(word.start, start), end)
        Word(word.text, Php.round(wordStart, 3), Php.round(minOf(maxOf(word.end, wordStart), end), 3))
    }

    private fun ensureWords(cue: Cue): Cue {
        if (!cue.words.isNullOrEmpty()) return cue
        // Estimasi per cue lebih akurat daripada per segmen gabungan, karena batas cue adalah waktu asli.
        return Cue(cue.start, cue.end, cue.text, WordTimer.distribute(cue.text, cue.start, cue.end), true)
    }

    private fun mergeIntoSentences(cues: List<Cue>): List<Segment> {
        val groups = mutableListOf<List<Cue>>()
        var current = mutableListOf<Cue>()
        for (cue in cues) {
            if (current.isNotEmpty() && shouldBreak(current, cue)) {
                groups += current
                current = mutableListOf()
            }
            current += cue
        }
        if (current.isNotEmpty()) groups += current
        return groups.mapIndexed { index, group -> toSegment(group, index + 1) }
    }

    /** Subtitle otomatis tidak bertanda baca: selain akhir kalimat, segmen juga dipotong saat jeda atau terlalu panjang. */
    private fun shouldBreak(current: List<Cue>, next: Cue): Boolean {
        val last = current.last()
        return SENTENCE_END.containsMatchIn(last.text) ||
            next.start - last.end >= pauseSeconds ||
            next.end - current[0].start > maxSegmentSeconds
    }

    private fun toSegment(group: List<Cue>, sequence: Int) = Segment(
        sequence = sequence,
        startTime = Php.round(group[0].start, 3),
        endTime = Php.round(group.last().end, 3),
        text = group.joinToString(" ") { it.text },
        words = group.flatMap { it.words.orEmpty() },
        wordsEstimated = group.any { it.wordsEstimated },
    )

    companion object {
        private val SENTENCE_END = Regex("""[.!?…]["'”’)\]]*$""")
        private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

        /** Jumlah kata terakhir yang dibandingkan saat mencari teks berulang. */
        private const val REPEAT_WINDOW = 50
    }
}
