package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Satu rentang [from, to] dalam detik video sumber. */
typealias Range = Pair<Double, Double>

/**
 * Bagian yang dibuang dari tengah klip (kata salah ucap, jeda panjang), sebagai rentang dalam detik video sumber
 * (padanan `App\Analysis\ClipCuts` desktop). Waktu absolut dipakai supaya potongan tetap di tempatnya walau
 * mulai/selesai klip digeser. Hasil klip = rentang klip dikurangi potongan ini.
 */
object ClipCuts {
    /** Potongan yang berjarak lebih dekat dari ini digabung. */
    private const val JOIN = 0.05

    /** Potongan yang lebih pendek dari ini diabaikan. */
    private const val MIN_LENGTH = 0.02

    /** Sisa jeda yang dipertahankan saat jeda dibuang, supaya kalimat tidak terdengar menempel. */
    const val PAUSE_KEEP = 0.2

    /** Dari JSON tersimpan: pasangan yang bukan dua angka dilewati, seperti desktop. */
    fun parse(cuts: JsonElement?): List<Range> = (cuts as? JsonArray).orEmpty().mapNotNull { cut ->
        val pair = cut as? JsonArray ?: return@mapNotNull null
        if (pair.size < 2) return@mapNotNull null
        val a = (pair[0] as? JsonPrimitive)?.numeric() ?: return@mapNotNull null
        val b = (pair[1] as? JsonPrimitive)?.numeric() ?: return@mapNotNull null
        a to b
    }

    /** Dipotong ke dalam rentang klip, diurutkan, dan yang bersinggungan digabung; dibulatkan 3 desimal. */
    fun normalize(cuts: List<Range>, start: Double, end: Double): List<Range> {
        val ranges = cuts.mapNotNull { (a, b) ->
            val from = maxOf(start, minOf(a, b))
            val to = minOf(end, maxOf(a, b))
            if (to - from >= MIN_LENGTH) from to to else null
        }.sortedBy { it.first }

        val merged = mutableListOf<DoubleArray>()
        for ((from, to) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && from - last[1] < JOIN) last[1] = maxOf(last[1], to) else merged += doubleArrayOf(from, to)
        }
        return merged.map { Php.round(it[0], 3) to Php.round(it[1], 3) }
    }

    fun add(cuts: List<Range>, from: Double, to: Double, start: Double, end: Double) = normalize(cuts + (from to to), start, end)

    /** Mengembalikan bagian [from, to]; potongan yang hanya sebagian kena dipecah. */
    fun subtract(cuts: List<Range>, from: Double, to: Double, start: Double, end: Double): List<Range> {
        val kept = mutableListOf<Range>()
        for ((a, b) in cuts) {
            if (b <= from || a >= to) {
                kept += a to b
                continue
            }
            if (a < from) kept += a to from
            if (b > to) kept += to to b
        }
        return normalize(kept, start, end)
    }

    /** Bagian klip yang tetap ada, berurutan. */
    fun kept(cuts: List<Range>, start: Double, end: Double): List<Range> {
        val kept = mutableListOf<Range>()
        var cursor = start
        for ((from, to) in cuts) {
            if (from > cursor) kept += cursor to from
            cursor = maxOf(cursor, to)
        }
        if (end > cursor) kept += cursor to end
        return kept
    }

    fun removed(cuts: List<Range>): Double = cuts.fold(0.0) { sum, (from, to) -> sum + (to - from) }

    /** Detik di video hasil untuk detik [time] di video sumber; waktu di dalam potongan jatuh ke awal potongan. */
    fun map(cuts: List<Range>, start: Double, time: Double): Double {
        var offset = time - start
        for ((from, to) in cuts) {
            if (time <= from) break
            offset -= minOf(time, to) - from
        }
        return maxOf(0.0, offset)
    }

    fun covers(cuts: List<Range>, time: Double): Boolean = cuts.any { (from, to) -> time >= from && time < to }

    /** Ekspresi filter select/aselect FFmpeg: bagian yang tetap ada, dalam detik sejak awal klip. */
    fun selectExpression(kept: List<Range>, start: Double): String =
        kept.joinToString("+") { (from, to) -> "between(t,${Php.numberFormat(maxOf(0.0, from - start), 3)},${Php.numberFormat(maxOf(0.0, to - start), 3)})" }
}

/** `is_numeric` untuk nilai JSON: angka, atau teks angka. */
internal fun JsonPrimitive.numeric(): Double? = if (isString) content.trim().toDoubleOrNull()?.takeIf { content.isNotBlank() } else doubleOrNull
