package id.clipmi.core.transcript

/**
 * Sunting teks satu segmen transkrip dengan tetap menjaga waktu kata (dipakai subtitle). Kata di awal dan akhir yang
 * tidak berubah memakai waktu aslinya; hanya bagian tengah yang berubah dibagi ulang proporsional panjang kata
 * ([WordTimer.distribute]) di rentang waktu kata lama yang digantikannya.
 */
object SegmentEditor {
    /** Segmen dengan teks baru, atau null bila teksnya kosong. Teks yang sama mengembalikan segmen apa adanya. */
    fun edit(segment: Segment, text: String): Segment? {
        val tokens = WordTimer.split(text)
        if (tokens.isEmpty()) return null
        val old = segment.words.ifEmpty { WordTimer.distribute(segment.text, segment.startTime, segment.endTime) }
        if (tokens == old.map { it.text }) return segment

        // Awalan dan akhiran yang sama (akhiran tidak boleh menimpa awalan).
        var prefix = 0
        while (prefix < tokens.size && prefix < old.size && tokens[prefix] == old[prefix].text) prefix++
        var suffix = 0
        while (suffix < tokens.size - prefix && suffix < old.size - prefix && tokens[tokens.size - 1 - suffix] == old[old.size - 1 - suffix].text) suffix++
        // Sisipan murni tanpa celah waktu (misalnya kata baru di ujung): kata tetangga ikut dibagi ulang supaya kata
        // baru tetap punya durasi di subtitle.
        if (old.size - prefix - suffix == 0 && tokens.size > old.size) {
            val gapStart = old.getOrNull(prefix - 1)?.end ?: segment.startTime
            val gapEnd = old.getOrNull(old.size - suffix)?.start ?: segment.endTime
            if (gapEnd <= gapStart) {
                if (prefix > 0) prefix-- else if (suffix > 0) suffix--
            }
        }

        val middle = tokens.subList(prefix, tokens.size - suffix)
        val replaced = old.subList(prefix, old.size - suffix)
        val result = mutableListOf<Word>()
        result += old.subList(0, prefix)
        var estimated = segment.wordsEstimated
        if (middle.isNotEmpty()) {
            if (middle.size == replaced.size) {
                // Jumlah kata sama: tiap kata memakai waktu kata yang digantikannya.
                middle.forEachIndexed { i, word -> result += Word(word, replaced[i].start, replaced[i].end) }
            } else {
                // Rentang kata yang diganti; bila tidak ada (sisipan), celah antara kata sebelum dan sesudahnya.
                val start = replaced.firstOrNull()?.start ?: old.getOrNull(prefix - 1)?.end ?: segment.startTime
                val end = replaced.lastOrNull()?.end ?: old.getOrNull(old.size - suffix)?.start ?: segment.endTime
                result += if (end > start) {
                    WordTimer.distribute(middle.joinToString(" "), start, end)
                } else {
                    // Sisipan tanpa celah: kata sisipan menempel di waktu tetangganya (durasi nol).
                    middle.map { Word(it, start, start) }
                }
                estimated = true
            }
        }
        result += old.subList(old.size - suffix, old.size)
        return segment.copy(text = result.joinToString(" ") { it.text }, words = result, wordsEstimated = estimated)
    }
}
