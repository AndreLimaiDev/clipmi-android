package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Jalur crop "ikuti penceramah", padanan `App\Render\SpeakerTrack` desktop: posisi penceramah per frame sampel (0–1
 * lebar video) menjadi keyframe `[detik sejak awal klip, posisi tengah crop]`. Crop ditahan selama penceramah masih
 * di dalam bingkai, bergeser halus bila ia menjauh, dan pindah langsung tepat di potongan kamera.
 */
object SpeakerTrack {
    /** Batas jumlah frame sampel per klip; klip panjang diambil lebih jarang. */
    const val MAX_SAMPLES = 120

    /** Pergeseran yang masih dibiarkan, dalam pecahan lebar video. */
    const val TOLERANCE = 0.06

    /** Lama crop bergeser ke posisi baru (detik). */
    const val GLIDE = 0.6

    /** Jumlah sampel berturut-turut yang harus menyimpang sebelum crop ikut pindah. */
    private const val PERSIST = 2

    /** Jarak antar-frame sampel desktop (detik): satu per detik, lebih jarang untuk klip di atas 2 menit. */
    fun interval(duration: Double): Double = maxOf(1.0, ceil(duration / MAX_SAMPLES * 10) / 10)

    /**
     * @param samples posisi penceramah per frame sampel (frame ke-i di detik i × [interval]); null = tidak terdeteksi.
     * @param cuts detik potongan kamera sejak awal klip.
     * @return keyframe; kosong bila penceramah tidak terdeteksi sama sekali.
     */
    fun build(samples: List<Double?>, interval: Double, duration: Double, cuts: List<Double> = emptyList()): List<List<Double>> {
        val inner = cuts.filter { it > interval / 2 && it < duration - interval / 2 }.sorted()
        val bounds = listOf(0.0) + inner + duration

        // Sampel dibagi per adegan (di antara dua potongan kamera).
        val shots = (0 until bounds.size - 1).map { s ->
            val values = samples.mapIndexedNotNull { i, value ->
                val time = i * interval
                if (time >= bounds[s] && (time < bounds[s + 1] || (s == bounds.size - 2 && time <= duration))) {
                    time to value?.let { it.coerceIn(0.0, 1.0) }
                } else {
                    null
                }
            }
            Shot(bounds[s], bounds[s + 1], smoothed(filled(values)))
        }

        // Adegan tanpa deteksi sama sekali memakai posisi adegan sebelumnya (atau sesudahnya untuk adegan awal).
        val known = shots.filter { it.values.isNotEmpty() }
        if (known.isEmpty()) return emptyList()

        val keyframes = mutableListOf<List<Double>>()
        var hold: Double? = null
        for (shot in shots) {
            // Posisi adegan sebelumnya ditahan sampai tepat di potongan kamera, lalu langsung pindah.
            hold?.let { keyframes += listOf(shot.from, it) }
            if (shot.values.isEmpty()) {
                val h = hold ?: known[0].values[0].second
                hold = h
                keyframes += listOf(shot.from, h)
                continue
            }
            val (last, points) = segment(shot.from, shot.to, shot.values)
            hold = last
            keyframes += points
        }
        return compact(keyframes, duration)
    }

    /** Posisi tengah crop pada detik tertentu sejak awal klip. */
    fun positionAt(keyframes: List<List<Double>>, time: Double): Double {
        var position = keyframes[0][1]
        for ((i, point) in keyframes.withIndex()) {
            val at = point[0]
            val value = point[1]
            if (time < at) {
                val previous = keyframes.getOrNull(i - 1) ?: point
                val from = previous[0]
                val start = previous[1]
                return if (at > from) start + (value - start) * (time - from) / (at - from) else value
            }
            position = value
        }
        return position
    }

    /** Ekspresi FFmpeg posisi tengah crop (0–1) terhadap waktu klip `t`, untuk opsi x filter crop. */
    fun expression(keyframes: List<List<Double>>): String {
        fun number(value: Double): String = Php.numberFormat(value, 3).trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
        var expression = number(keyframes.last()[1])
        for (i in keyframes.size - 2 downTo 0) {
            val (from, start) = keyframes[i]
            val (to, end) = keyframes[i + 1]
            // Dua keyframe di detik yang sama = potongan kamera; tidak ada rentang yang perlu ditulis.
            if (to <= from) continue
            val between = if (abs(end - start) < 0.0005) number(start)
            else number(start) + "+(" + number(end - start) + ")*(t-" + number(from) + ")/" + number(to - from)
            expression = "if(lt(t,${number(to)}),$between,$expression)"
        }
        return expression
    }

    private data class Shot(val from: Double, val to: Double, val values: List<Pair<Double, Double>>)

    /** Sampel kosong diisi dengan sampel terdekat di adegan yang sama. */
    private fun filled(values: List<Pair<Double, Double?>>): List<Pair<Double, Double>> {
        val detected = values.filter { it.second != null }
        if (detected.isEmpty()) return emptyList()
        return values.map { value ->
            if (value.second != null) return@map value.first to value.second!!
            var nearest: Pair<Double, Double?>? = null
            for (candidate in detected) {
                if (nearest == null || abs(candidate.first - value.first) < abs(nearest.first - value.first)) nearest = candidate
            }
            value.first to nearest!!.second!!
        }
    }

    /** Median tiga sampel bertetangga, supaya satu deteksi yang meleset tidak menggeser crop. */
    private fun smoothed(values: List<Pair<Double, Double>>): List<Pair<Double, Double>> = values.mapIndexed { i, value ->
        val from = maxOf(0, i - 1)
        val length = if (i == 0) 2 else 3
        value.first to median(values.subList(from, minOf(values.size, from + length)).map { it.second })
    }

    /** Keyframe satu adegan: posisi ditahan, lalu bergeser halus bila penceramah menjauh dari tengah crop. */
    private fun segment(from: Double, to: Double, values: List<Pair<Double, Double>>): Pair<Double, List<List<Double>>> {
        var hold = median(values.take(3).map { it.second })
        val points = mutableListOf(listOf(from, hold))
        var streak = 0
        for ((i, pair) in values.withIndex()) {
            streak = if (abs(pair.second - hold) > TOLERANCE) streak + 1 else 0
            if (streak < PERSIST) continue
            val first = i - PERSIST + 1
            val target = median(values.subList(first, minOf(values.size, first + 3)).map { it.second })
            val start = maxOf(points.last()[0], values[first].first - GLIDE / 2)
            val end = minOf(to, start + GLIDE)
            points += listOf(start, hold)
            points += listOf(end, target)
            hold = target
            streak = 0
        }
        return hold to points
    }

    /** Membuang keyframe yang tidak mengubah apa pun dan membulatkan. */
    private fun compact(keyframes: List<List<Double>>, duration: Double): List<List<Double>> {
        val rounded = keyframes.map { listOf(Php.round(minOf(it[0], duration), 2), Php.round(it[1], 3)) }
        val result = mutableListOf<List<Double>>()
        for ((i, point) in rounded.withIndex()) {
            val previous = result.lastOrNull()
            val next = rounded.getOrNull(i + 1)
            if (previous != null && previous[1] == point[1] && (next == null || next[1] == point[1])) continue
            result += point
        }
        return result
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
