package id.clipmi.core.render

import id.clipmi.core.php.Php
import id.clipmi.core.transcript.Word
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** Bagian klip yang dibuang (kata, jeda) dan kata yang hanya disembunyikan dari subtitle (kolom `cuts`). */
data class CutSet(val words: List<Range> = emptyList(), val pauses: List<Range> = emptyList(), val hidden: List<Range> = emptyList()) {
    /** Format JSON desktop; null bila tidak ada potongan sama sekali. */
    fun toJson(): JsonObject? =
        if (words.isEmpty() && pauses.isEmpty() && hidden.isEmpty()) null
        else JsonObject(mapOf("words" to words.json(), "pauses" to pauses.json(), "hidden" to hidden.json()))

    private fun List<Range>.json() = JsonArray(map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })

    companion object {
        fun from(json: JsonObject?): CutSet = CutSet(
            ClipCuts.parse(json?.get("words")),
            ClipCuts.parse(json?.get("pauses")),
            ClipCuts.parse(json?.get("hidden")),
        )
    }
}

/** Bagian klip yang bisa disunting editor: rentang, potongan, dan koreksi subtitle. */
data class EditableClip(
    val startTime: Double,
    val endTime: Double,
    val cuts: CutSet = CutSet(),
    val subtitleLines: SubtitleEdits? = null,
    val subtitleEdited: Boolean = false,
) {
    fun duration(): Double = Php.round(endTime - startTime, 3)
    fun wordCuts() = ClipCuts.normalize(cuts.words, startTime, endTime)
    fun hiddenWords() = ClipCuts.normalize(cuts.hidden, startTime, endTime)
    fun pauseCuts() = ClipCuts.normalize(cuts.pauses, startTime, endTime)
}

/** Satu kata di editor: teks (hasil koreksi), waktu, dibuang dari video, atau hanya disembunyikan dari subtitle. */
data class EditorWord(val text: String, val start: Double, val end: Double, val cut: Boolean, val hidden: Boolean)

/**
 * Satu jeda terdeteksi: [after] = indeks kata terakhir sebelum jeda (-1 = sebelum kata pertama), [cut] = bagian yang
 * dibuang (menyisakan jeda pendek kecuali di awal/akhir klip), [removed] = sudah dibuang.
 */
data class Pause(val after: Int, val start: Double, val end: Double, val length: Double, val cut: Range, val removed: Boolean)

/**
 * Menyunting rentang dan isi klip (padanan `App\Analysis\ClipEditor` desktop): geser/tempel ke batas kata, perpanjang
 * satu segmen, buang/kembalikan kata, sembunyikan kata dari subtitle, ubah teks kata, dan buang jeda. Fungsi murni:
 * menerima keadaan klip dan mengembalikan keadaan baru (atau penolakan).
 *
 * @param segments transkrip project, urut menurut `sequence` (waktu per kata bisa null).
 * @param projectDuration durasi video; null = akhir segmen terakhir.
 */
class ClipEditor(private val segments: List<SegmentWords>, private val style: CaptionStyle, projectDuration: Double?) {
    private val limit: Double? = projectDuration ?: segments.maxOfOrNull { it.end }

    fun lines(clip: EditableClip) = SubtitleBuilder.linesFor(segments, clip.startTime, clip.endTime, style, clip.subtitleEdited, clip.subtitleLines)

    fun nudge(clip: EditableClip, edge: String, delta: Double): EditableClip = if (edge == "start") {
        clip.copy(startTime = Php.round(maxOf(0.0, minOf(clip.startTime + delta, clip.endTime - ClipSpec.MIN_DURATION)), 3))
    } else {
        val end = maxOf(clip.endTime + delta, clip.startTime + ClipSpec.MIN_DURATION)
        clip.copy(endTime = Php.round(if (limit != null) minOf(end, limit) else end, 3))
    }

    /** Mulai ke awal kata terdekat, selesai ke akhir kata terdekat, supaya klip tidak terpotong di tengah kata. */
    fun snapToWords(clip: EditableClip): EditableClip {
        val words = segments.filter { it.end >= clip.startTime - 10 && it.start <= clip.endTime + 10 }.flatMap { it.words.orEmpty() }
        if (words.isEmpty()) return clip
        val start = words.map { it.start }.minBy { abs(it - clip.startTime) }
        val end = words.map { it.end }.filter { it >= start + ClipSpec.MIN_DURATION }.minByOrNull { abs(it - clip.endTime) }
        return clip.copy(startTime = Php.round(start, 3), endTime = Php.round(end ?: clip.endTime, 3))
    }

    /** Segmen yang masuk bila klip diperpanjang ke depan ("before") atau ke belakang ("after"). */
    fun neighbour(clip: EditableClip, side: String): SegmentWords? = if (side == "before") {
        segments.filter { it.start < clip.startTime - 0.05 }.maxByOrNull { it.start }
    } else {
        segments.filter { it.end > clip.endTime + 0.05 }.minByOrNull { it.end }
    }

    fun extend(clip: EditableClip, side: String): EditableClip {
        val segment = neighbour(clip, side) ?: return clip
        return if (side == "before") clip.copy(startTime = Php.round(segment.start, 3)) else clip.copy(endTime = Php.round(segment.end, 3))
    }

    /** Mulai dan selesai baru dari pegangan timeline, dibatasi panjang video dan durasi minimal. */
    fun setRange(clip: EditableClip, start: Double, end: Double): EditableClip {
        val s = maxOf(0.0, start)
        val e = if (limit != null) minOf(end, limit) else end
        return if (e - s >= ClipSpec.MIN_DURATION) clip.copy(startTime = Php.round(s, 3), endTime = Php.round(e, 3)) else clip
    }

    /** Membuang kata di rentang [from, to]; null bila sisa klip kurang dari durasi minimal. */
    fun cut(clip: EditableClip, from: Double, to: Double): EditableClip? =
        saveCuts(clip, ClipCuts.add(clip.wordCuts(), from, to, clip.startTime, clip.endTime), clip.pauseCuts())

    fun restore(clip: EditableClip, from: Double, to: Double): EditableClip? =
        saveCuts(clip, ClipCuts.subtract(clip.wordCuts(), from, to, clip.startTime, clip.endTime), clip.pauseCuts())

    /** Menyembunyikan kata dari subtitle saja; suaranya tetap ada (misalnya "eee"). */
    fun hide(clip: EditableClip, from: Double, to: Double): EditableClip? =
        saveCuts(clip, clip.wordCuts(), clip.pauseCuts(), ClipCuts.add(clip.hiddenWords(), from, to, clip.startTime, clip.endTime))

    fun show(clip: EditableClip, from: Double, to: Double): EditableClip? =
        saveCuts(clip, clip.wordCuts(), clip.pauseCuts(), ClipCuts.subtract(clip.hiddenWords(), from, to, clip.startTime, clip.endTime))

    sealed interface EditResult {
        data class Done(val clip: EditableClip) : EditResult
        data class Rejected(val message: String) : EditResult
    }

    /**
     * Mengganti teks kata di [from, to] lewat koreksi subtitle. Jumlah kata yang sama mempertahankan waktu tiap kata;
     * jumlah yang berbeda hanya bisa dalam satu baris.
     */
    fun editWords(clip: EditableClip, from: Double, to: Double, raw: String): EditResult {
        val text = Php.trim(SPACES.replace(raw, " "))
        if (text.isEmpty()) return EditResult.Rejected("Teks tidak boleh kosong. Untuk menghapus kata, pakai Buang atau Sembunyikan.")

        val lines = lines(clip)
        // Indeks baris dimasukkan urut naik, jadi LinkedHashMap sudah terurut (sortedMapOf hanya ada di JVM).
        val chosen = linkedMapOf<Int, MutableList<Int>>()
        lines.forEachIndexed { index, line ->
            line.words.orEmpty().forEachIndexed { position, word ->
                val middle = (word.start + word.end) / 2
                if (middle >= from && middle <= to) chosen.getOrPut(index) { mutableListOf() } += position
            }
        }
        val tokens = text.split(' ').toMutableList()
        if (chosen.isEmpty()) return EditResult.Rejected("Kata yang dipilih tidak ditemukan di subtitle.")
        if (chosen.size > 1 && tokens.size != chosen.values.sumOf { it.size }) {
            return EditResult.Rejected("Untuk mengubah jumlah kata, pilih kata dalam satu baris subtitle saja.")
        }

        val edits = mutableListOf<Pair<Int, String>>()
        for ((index, positions) in chosen) {
            val words = lines[index].words!!.map { it.text }.toMutableList()
            val replacement = if (chosen.size > 1) List(positions.size) { tokens.removeAt(0) } else tokens
            val at = positions[0]
            repeat(minOf(positions.size, words.size - at)) { words.removeAt(at) }
            words.addAll(at, replacement)
            edits += index to words.joinToString(" ")
        }

        val saved = SubtitleBuilder.applyEdits(lines, edits, clip.startTime, clip.endTime)
        return EditResult.Done(clip.copy(subtitleLines = saved, subtitleEdited = true))
    }

    /** Kata di rentang klip (dari baris subtitle, jadi ejaan koreksi ikut tampil) beserta status buang/sembunyi. */
    fun words(clip: EditableClip): List<EditorWord> {
        val cuts = clip.wordCuts()
        val hidden = clip.hiddenWords()
        return lines(clip).flatMap { it.words.orEmpty() }.map { word ->
            val middle = (word.start + word.end) / 2
            EditorWord(word.text, Php.round(word.start, 3), Php.round(word.end, 3), ClipCuts.covers(cuts, middle), ClipCuts.covers(hidden, middle))
        }
    }

    /** Jeda dari suara klip ([silences], hasil [ClipSilence.parse]). */
    fun pauses(clip: EditableClip, silences: List<Range>): List<Pause> {
        val starts = words(clip).map { it.start }
        val cuts = clip.pauseCuts()
        val half = ClipCuts.PAUSE_KEEP / 2
        return silences.mapNotNull { (start, end) ->
            val from = if (start <= clip.startTime + 0.05) clip.startTime else start + half
            val to = if (end >= clip.endTime - 0.05) clip.endTime else end - half
            val middle = (start + end) / 2
            if (to - from < 0.05) return@mapNotNull null
            Pause(
                after = starts.count { it < middle } - 1,
                start = start,
                end = end,
                length = Php.round(end - start, 1),
                cut = Php.round(from, 3) to Php.round(to, 3),
                removed = ClipCuts.covers(cuts, (from + to) / 2),
            )
        }
    }

    /** Membuang satu jeda, atau mengembalikannya bila sudah dibuang. */
    fun togglePause(clip: EditableClip, pause: Pause): EditableClip? {
        val (from, to) = pause.cut
        val pauses = if (pause.removed) ClipCuts.subtract(clip.pauseCuts(), from, to, clip.startTime, clip.endTime)
        else ClipCuts.add(clip.pauseCuts(), from, to, clip.startTime, clip.endTime)
        return saveCuts(clip, clip.wordCuts(), pauses)
    }

    /** Membuang semua jeda sepanjang [min] detik atau lebih, dengan sisa jeda pendek. */
    fun cleanPauses(clip: EditableClip, silences: List<Range>, min: Double): EditableClip? {
        var pauses = clip.pauseCuts()
        for (pause in pauses(clip, silences)) {
            if (pause.length >= min) pauses = ClipCuts.add(pauses, pause.cut.first, pause.cut.second, clip.startTime, clip.endTime)
        }
        return saveCuts(clip, clip.wordCuts(), pauses)
    }

    private fun saveCuts(clip: EditableClip, words: List<Range>, pauses: List<Range>, hidden: List<Range>? = null): EditableClip? {
        val keepHidden = hidden ?: clip.hiddenWords()
        val all = ClipCuts.normalize(words + pauses, clip.startTime, clip.endTime)
        if (clip.duration() - ClipCuts.removed(all) < ClipSpec.MIN_DURATION) return null
        return clip.copy(cuts = CutSet(words, pauses, keepHidden))
    }

    companion object {
        /** Langkah geser mulai/selesai (detik). */
        val STEPS = listOf(-1.0, -0.5, -0.1, 0.1, 0.5, 1.0)

        private val SPACES = Regex("""[\t\n\x0B\f\r\p{Z}]+""")
    }
}

/** Jeda dari log `silencedetect` FFmpeg (padanan `App\Analysis\ClipSilence` desktop). */
object ClipSilence {
    /** Jeda terpendek yang dicatat (detik). */
    const val MIN = 0.3

    /** Ambang senyap = rata-rata volume klip dikurangi ini (dB), dibatasi −50 sampai −15 dB. */
    const val BELOW_MEAN = 12.0

    private val EVENT = Regex("""silence_(start|end): (-?[\d.]+)""")

    fun noiseThreshold(meanVolume: Double): Double = maxOf(-50.0, minOf(-15.0, meanVolume - BELOW_MEAN))

    fun parse(log: String, start: Double, end: Double): List<Range> {
        val pauses = mutableListOf<Range>()
        var open: Double? = null
        for (match in EVENT.findAll(log)) {
            val time = minOf(end, maxOf(start, start + match.groupValues[2].toDouble()))
            if (match.groupValues[1] == "start") {
                open = time
            } else if (open != null) {
                pauses += Php.round(open, 3) to Php.round(time, 3)
                open = null
            }
        }
        if (open != null) pauses += Php.round(open, 3) to Php.round(end, 3)
        return pauses.filter { MIN - 0.01 <= it.second - it.first }
    }
}

@Suppress("unused")
private fun Word.middle() = (start + end) / 2
