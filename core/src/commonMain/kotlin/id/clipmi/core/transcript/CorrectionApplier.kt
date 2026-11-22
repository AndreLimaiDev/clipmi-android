package id.clipmi.core.transcript

import id.clipmi.core.php.Php

/**
 * Menerapkan kamus koreksi ke data per kata sebuah segmen (padanan `App\Transcript\CorrectionApplier`). Pencocokan
 * memakai kata utuh dan tidak peka huruf besar/kecil; tanda baca yang menempel di kata asli dipertahankan.
 *
 * @param corrections pasangan teks salah → teks benar, urut seperti di database (urutan menentukan aturan yang
 *   dipilih bila panjangnya sama).
 */
class CorrectionApplier(corrections: List<Pair<String, String>>) {
    private class Rule(val wrong: List<String>, val correct: List<String>)

    /** Aturan dikelompokkan per kata pertama, frasa terpanjang lebih dulu. */
    private val rulesByFirstWord: Map<String, List<Rule>>

    init {
        val rules = LinkedHashMap<String, MutableList<Rule>>()
        // Seperti array PHP: kunci yang sama ditimpa nilai terakhir, posisinya tetap posisi pertama.
        val unique = LinkedHashMap<String, String>()
        for ((wrong, correct) in corrections) unique[wrong] = correct

        for ((wrong, correct) in unique) {
            val wrongTokens = split(wrong).map(::normalize).filter { it.isNotEmpty() }
            val correctTokens = split(correct)
            if (wrongTokens.isEmpty() || correctTokens.isEmpty()) continue
            rules.getOrPut(wrongTokens[0]) { mutableListOf() } += Rule(wrongTokens, correctTokens)
        }
        rulesByFirstWord = rules.mapValues { (_, list) -> list.sortedByDescending { it.wrong.size } }
    }

    val isEmpty: Boolean get() = rulesByFirstWord.isEmpty()

    /** Segmen hasil koreksi dan jumlah koreksi yang benar-benar mengubah teks. */
    fun applyToSegment(segment: Segment): Pair<Segment, Int> {
        val words = segment.words
        val normalized = words.map { normalize(it.text) }
        var estimated = segment.wordsEstimated
        val result = mutableListOf<Word>()
        var count = 0
        var i = 0

        while (i < words.size) {
            val rule = matchAt(normalized, i)
            if (rule == null) {
                result += words[i]
                i++
                continue
            }

            val length = rule.wrong.size
            val span = words.subList(i, i + length)
            val replacement = withOriginalPunctuation(rule.correct, span)

            if (replacement != span.map { it.text }) count++

            if (replacement.size == length) {
                // Jumlah kata sama: waktu tiap kata tetap memakai waktu aslinya.
                replacement.forEachIndexed { k, text -> result += Word(text, span[k].start, span[k].end) }
            } else {
                // Jumlah kata berubah: rentang waktu kata lama dibagi ulang ke kata baru.
                result += WordTimer.distribute(replacement.joinToString(" "), span[0].start, span[length - 1].end)
                estimated = true
            }
            i += length
        }

        if (count == 0) return segment to 0
        return segment.copy(text = result.joinToString(" ") { it.text }, words = result, wordsEstimated = estimated) to count
    }

    private fun matchAt(normalized: List<String>, index: Int): Rule? =
        rulesByFirstWord[normalized[index]]?.firstOrNull { rule ->
            index + rule.wrong.size <= normalized.size && normalized.subList(index, index + rule.wrong.size) == rule.wrong
        }

    /** Tanda baca di depan kata pertama dan di belakang kata terakhir asli dipindahkan ke teks pengganti. */
    private fun withOriginalPunctuation(correct: List<String>, span: List<Word>): List<String> {
        val prefix = affixes(span.first().text).first
        val suffix = affixes(span.last().text).third
        return correct.toMutableList().also {
            it[0] = prefix + it[0]
            it[it.size - 1] = it[it.size - 1] + suffix
        }
    }

    companion object {
        private val AFFIXES = Regex("""^([^\p{L}\p{N}]*)(.*?)([^\p{L}\p{N}]*)$""", RegexOption.DOT_MATCHES_ALL)

        /** Awalan tanda baca, inti kata, akhiran tanda baca. */
        private fun affixes(word: String): Triple<String, String, String> {
            val m = AFFIXES.find(word)!!
            return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }

        fun normalize(word: String): String = Php.mbStrtolower(affixes(word).second)

        private fun split(text: String): List<String> = WordTimer.split(text)
    }
}
