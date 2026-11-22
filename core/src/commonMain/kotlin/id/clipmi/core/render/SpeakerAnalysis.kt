package id.clipmi.core.render

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Wajah di satu frame analisis: titik tengah dan lebar (pecahan lebar frame), bukaan mulut bila diukur. */
data class FaceObservation(val centerX: Double, val width: Double, val opening: Double? = null)

/** Frame analisis pada detik [time] sejak awal rentang yang dianalisis. */
data class AnalysisFrame(val time: Double, val faces: List<FaceObservation>)

/**
 * Memilih pembicara aktif dari wajah per frame (ClipMI Android; desktop memakai wajah terbesar). Di setiap adegan
 * (di antara potongan kamera) wajah diikuti dari frame ke frame; bila ada beberapa wajah sebesar pembicara, yang
 * mulutnya paling banyak bergerak dalam ±1 detik dianggap berbicara. Pergantian pembicara dijadikan potongan (crop
 * pindah langsung seperti potongan kamera), ditempatkan di batas kalimat transkrip terdekat bila ada.
 */
object SpeakerAnalyzer {
    /** Frame per detik untuk mencari potongan kamera (gambar abu-abu 32×18). */
    const val CUT_FPS = 15.0

    /** Frame analisis wajah per sampel jalur. */
    const val FRAMES_PER_SAMPLE = 2

    /** Wajah lebih kecil dari pecahan ini terhadap wajah terbesar di frame tidak dianggap calon pembicara (jamaah). */
    const val CANDIDATE_SIZE = 0.5

    /** Simpangan baku bukaan mulut minimal untuk dianggap berbicara. */
    const val SPEAKING = 0.035

    /** Pembicara baru harus jauh lebih aktif daripada pembicara saat ini. */
    const val SWITCH_RATIO = 1.5

    /** Lama minimal satu pembicara sebelum crop boleh pindah lagi (detik). */
    const val MIN_HOLD = 1.5

    /** Jarak minimal antarpotongan (detik). */
    const val MIN_SHOT = 0.8

    /** Pergantian pembicara digeser ke batas kalimat dalam jarak ini (detik). */
    const val SNAP = 1.0

    /** Jarak sampel jalur: dua kali lebih rapat daripada desktop, paling rapat 0,5 detik. */
    fun sampleInterval(duration: Double): Double = maxOf(0.5, SpeakerTrack.interval(duration) / 2)

    fun frameStep(duration: Double): Double = sampleInterval(duration) / FRAMES_PER_SAMPLE

    fun sampleCount(duration: Double, interval: Double): Int = maxOf(1, ceil(duration / interval - 0.001).toInt())

    /**
     * Potongan kamera dari kecerahan frame kecil berurutan ([fps] per detik): selisih rata-rata yang melonjak jauh di
     * atas selisih sekitarnya.
     *
     * @param luma kecerahan 0–255 tiap piksel, satu larik per frame.
     * @return detik potongan sejak frame pertama.
     */
    fun cameraCuts(luma: List<IntArray>, fps: Double = CUT_FPS): List<Double> {
        if (luma.size < 3) return emptyList()
        // Jendela pembanding dalam detik yang sama untuk semua fps (7 dan 3 frame pada 15 fps).
        val wide = maxOf(1, (7 * fps / CUT_FPS).roundToLong().toInt())
        val near = maxOf(1, (3 * fps / CUT_FPS).roundToLong().toInt())
        val diffs = DoubleArray(luma.size)
        for (k in 1 until luma.size) {
            val a = luma[k - 1]
            val b = luma[k]
            var sum = 0L
            for (i in 0 until minOf(a.size, b.size)) sum += abs(a[i] - b[i])
            diffs[k] = sum / (255.0 * maxOf(1, minOf(a.size, b.size)))
        }
        val found = ArrayList<Pair<Double, Double>>()
        for (k in 1 until luma.size) {
            val around = (maxOf(1, k - wide)..minOf(luma.size - 1, k + wide)).filter { it != k }.map { diffs[it] }
            val local = if (around.isEmpty()) 0.0 else median(around)
            val peak = (maxOf(1, k - near)..minOf(luma.size - 1, k + near)).all { diffs[it] <= diffs[k] }
            if (peak && diffs[k] > maxOf(0.1, 4 * local)) found += (k - 0.5) / fps to diffs[k]
        }
        return spaced(found)
    }

    /** Potongan yang terlalu rapat digabung: yang selisihnya terbesar dipakai. */
    private fun spaced(cuts: List<Pair<Double, Double>>): List<Double> {
        val result = ArrayList<Pair<Double, Double>>()
        for (cut in cuts.sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && cut.first - last.first < MIN_SHOT) {
                if (cut.second > last.second) result[result.size - 1] = cut
            } else {
                result += cut
            }
        }
        return result.map { it.first }
    }

    data class Result(
        /** Posisi pembicara per sampel (detik k × interval); null = tidak terdeteksi. */
        val samples: List<Double?>,
        /** Potongan kamera dan pergantian pembicara, urut. */
        val cuts: List<Double>,
        /** Bagian dari [cuts] yang berupa pergantian pembicara. */
        val switches: List<Double>,
        /** Frame yang memuat wajah. */
        val faceFrames: Int,
    )

    private class Track(val id: Int, var x: Double, var width: Double, var frame: Int)

    private data class Seen(val frame: Int, val time: Double, val track: Int, val face: FaceObservation)

    private data class Run(var tracks: MutableSet<Int>, val first: Int, var last: Int)

    /**
     * @param frames frame analisis urut waktu, jarak [frameStep].
     * @param boundaries batas kalimat transkrip (detik sejak awal rentang).
     */
    fun analyze(frames: List<AnalysisFrame>, interval: Double, duration: Double, cameraCuts: List<Double>, boundaries: List<Double> = emptyList()): Result {
        val bounds = listOf(0.0) + cameraCuts.filter { it > 0 && it < duration }.sorted() + duration
        val segments = ArrayList<Triple<Double, Double, Set<Int>>>()
        val seen = ArrayList<Seen>()
        val switches = ArrayList<Double>()
        var nextTrack = 0

        for (s in 0 until bounds.size - 1) {
            val from = bounds[s]
            val to = bounds[s + 1]
            val last = s == bounds.size - 2
            val shot = frames.filter { it.time >= from && (it.time < to || (last && it.time <= duration)) }

            // Wajah calon pembicara diikuti antarframe: dekat dengan posisi terakhirnya dan belum lama hilang.
            val tracks = ArrayList<Track>()
            val inShot = ArrayList<Seen>()
            shot.forEachIndexed { fi, frame ->
                val largest = frame.faces.maxOfOrNull { it.width } ?: return@forEachIndexed
                val used = HashSet<Int>()
                for (face in frame.faces.filter { it.width >= largest * CANDIDATE_SIZE }.sortedByDescending { it.width }) {
                    val match = tracks
                        .filter { it.id !in used && fi - it.frame <= 4 && abs(it.x - face.centerX) < 0.6 * maxOf(it.width, face.width) }
                        .minByOrNull { abs(it.x - face.centerX) }
                    val track = match ?: Track(nextTrack++, face.centerX, face.width, fi).also { tracks += it }
                    track.x = face.centerX
                    track.width = face.width
                    track.frame = fi
                    used += track.id
                    inShot += Seen(fi, frame.time, track.id, face)
                }
            }
            seen += inShot

            // Pembicara per frame.
            val chosen = arrayOfNulls<Int>(shot.size)
            var current: Int? = null
            var since = Double.NEGATIVE_INFINITY
            shot.forEachIndexed { fi, frame ->
                val present = inShot.filter { it.frame == fi }
                if (present.isEmpty()) return@forEachIndexed
                fun activity(track: Int): Double {
                    val openings = inShot.filter { it.track == track && abs(it.time - frame.time) <= 1.0 }.mapNotNull { it.face.opening }
                    return if (openings.size >= 3) deviation(openings) else 0.0
                }
                val pick = if (present.size == 1) {
                    present[0].track
                } else {
                    val scores = present.associate { it.track to activity(it.track) }
                    val best = scores.maxBy { it.value }.key
                    val largest = present.maxBy { it.face.width }.track
                    val now = current
                    when {
                        now == null || now !in scores -> if (scores.getValue(best) >= SPEAKING) best else largest
                        best != now && scores.getValue(best) >= SPEAKING && scores.getValue(best) > SWITCH_RATIO * scores.getValue(now) &&
                            frame.time - since >= MIN_HOLD -> best
                        else -> now
                    }
                }
                if (pick != current) since = frame.time
                current = pick
                chosen[fi] = pick
            }

            // Pilihan sesaat (kurang dari MIN_HOLD) ikut pembicara sebelumnya.
            val runs = ArrayList<Run>()
            chosen.forEachIndexed { fi, track ->
                if (track == null) return@forEachIndexed
                val run = runs.lastOrNull()
                if (run != null && track in run.tracks) run.last = fi else runs += Run(mutableSetOf(track), fi, fi)
            }
            val minFrames = if (shot.size >= 2) maxOf(1, (MIN_HOLD / maxOf(1e-6, shot[1].time - shot[0].time)).roundToInt()) else 1
            var i = 1
            while (i < runs.size) {
                if (runs[i].last - runs[i].first + 1 < minFrames && i < runs.size) {
                    runs[i - 1].last = runs[i].last
                    runs.removeAt(i)
                    if (i < runs.size && runs[i].tracks == runs[i - 1].tracks) {
                        runs[i - 1].last = runs[i].last
                        runs.removeAt(i)
                    }
                } else {
                    i++
                }
            }
            // Wajah yang sama bisa tercatat dengan nomor lain (sempat hilang): pergantian hanya bila kedua wajah pernah
            // tampil bersamaan.
            i = 1
            while (i < runs.size) {
                val a = runs[i - 1].tracks
                val b = runs[i].tracks
                val framesA = inShot.filter { it.track in a }.map { it.frame }.toSet()
                val together = inShot.any { it.track in b && it.frame in framesA }
                if (!together) {
                    runs[i - 1].tracks.addAll(b)
                    runs[i - 1].last = runs[i].last
                    runs.removeAt(i)
                } else {
                    i++
                }
            }

            // Rentang per pembicara; pergantian di batas kalimat terdekat bila ada.
            var start = from
            runs.forEachIndexed { r, run ->
                if (r == runs.size - 1) {
                    segments += Triple(start, to, run.tracks)
                    return@forEachIndexed
                }
                val middle = (shot[run.last].time + shot[runs[r + 1].first].time) / 2
                val snapped = boundaries
                    .filter { abs(it - middle) <= SNAP && it > start + MIN_SHOT && it < to - MIN_SHOT }
                    .minByOrNull { abs(it - middle) }
                val at = snapped ?: middle
                if (at - start >= MIN_SHOT && to - at >= MIN_SHOT) {
                    segments += Triple(start, at, run.tracks)
                    switches += at
                    start = at
                } else {
                    runs[r + 1].tracks.addAll(run.tracks)
                }
            }
            if (runs.isEmpty()) segments += Triple(from, to, emptySet())
        }

        val count = sampleCount(duration, interval)
        val samples = (0 until count).map { k ->
            val t = k * interval
            val tracks = segments.firstOrNull { t >= it.first && t < it.second }?.third ?: segments.lastOrNull()?.third.orEmpty()
            val near = seen.filter { it.track in tracks && abs(it.time - t) <= interval / 2 + 1e-6 }
            if (near.isEmpty()) null else near.sumOf { it.face.centerX } / near.size
        }
        return Result(samples, (bounds.drop(1).dropLast(1) + switches).sorted(), switches, frames.count { it.faces.isNotEmpty() })
    }

    private fun deviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/**
 * Isi kolom `clips.speaker_track`. [start], [end], dan [keyframes] sama dengan desktop (jalur untuk rentang klip saat
 * ini); [analysis] menyimpan hasil deteksi (waktu project) supaya klip yang dipendekkan tidak perlu dideteksi ulang,
 * dan [manual] posisi crop yang dipilih pengguna per adegan.
 */
@Serializable
data class SpeakerTrackData(
    val start: Double,
    val end: Double,
    val keyframes: List<List<Double>> = emptyList(),
    val analysis: Analysis? = null,
    val manual: List<ManualPosition> = emptyList(),
) {
    /**
     * Hasil deteksi: sampel ke-k di detik [start] + k × [interval] (waktu project); [cuts] juga waktu project (potongan
     * kamera dan pergantian pembicara). [switches] = bagian dari [cuts] yang merupakan pergantian pembicara di gambar
     * kamera yang sama (crop bergeser halus, bukan loncat); kosong untuk hasil deteksi lama.
     */
    @Serializable
    data class Analysis(
        val start: Double,
        val end: Double,
        val interval: Double,
        val samples: List<Double?>,
        val cuts: List<Double>,
        val switches: List<Double> = emptyList(),
    )

    /** Posisi tengah crop (0–1) untuk adegan yang memuat detik [at] (waktu project). */
    @Serializable
    data class ManualPosition(val at: Double, val x: Double)

    /** Adegan di rentang klip (detik sejak awal klip) dan posisi manualnya bila ada. */
    data class Shot(val index: Int, val from: Double, val to: Double, val manual: Double?)

    val duration: Double get() = end - start

    fun matches(start: Double, end: Double): Boolean = abs(this.start - start) < 0.001 && abs(this.end - end) < 0.001

    /** Hasil deteksi mencakup rentang ini, jadi jalurnya bisa dibuat tanpa deteksi ulang. */
    fun covers(start: Double, end: Double): Boolean {
        val a = analysis ?: return false
        return start >= a.start - 0.01 && end <= a.end + 0.01
    }

    /** Potongan di rentang klip (detik sejak awal klip). */
    fun cuts(): List<Double> = analysis?.cuts.orEmpty().map { it - start }.filter { it > 0 && it < duration }

    /** Pergantian pembicara di rentang klip (detik sejak awal klip). */
    fun switches(): List<Double> = analysis?.switches.orEmpty().map { it - start }.filter { it > 0 && it < duration }

    /** Adegan dengan pembagian yang sama seperti [SpeakerTrack.build]. */
    fun shots(): List<Shot> {
        val interval = analysis?.interval ?: return listOf(Shot(0, 0.0, duration, null))
        val inner = cuts().filter { it > interval / 2 && it < duration - interval / 2 }.sorted()
        val bounds = listOf(0.0) + inner + duration
        return (0 until bounds.size - 1).map { s ->
            val from = bounds[s]
            val to = bounds[s + 1]
            Shot(s, from, to, manual.lastOrNull { it.at - start >= from && it.at - start < to }?.x)
        }
    }

    fun shotAt(time: Double): Shot? = shots().let { list -> list.firstOrNull { time >= it.from && time < it.to } ?: list.lastOrNull() }

    /** Jalur untuk rentang klip lain (dalam cakupan [analysis]). */
    fun retarget(start: Double, end: Double): SpeakerTrackData = copy(start = start, end = end).rebuilt()

    /** Posisi manual adegan [shot]; null = kembali ke otomatis. */
    fun withManual(shot: Shot, x: Double?): SpeakerTrackData {
        val kept = manual.filterNot { it.at - start >= shot.from && it.at - start < shot.to }
        val added = x?.let { listOf(ManualPosition(start + (shot.from + shot.to) / 2, (it.coerceIn(0.0, 1.0) * 1000).roundToLong() / 1000.0)) }.orEmpty()
        return copy(manual = (kept + added).sortedBy { it.at }).rebuilt()
    }

    /** Keyframe dari hasil deteksi: sampel adegan yang diatur manual diganti posisi manualnya. */
    fun rebuilt(): SpeakerTrackData {
        val a = analysis ?: return this
        if (a.samples.isEmpty()) return copy(keyframes = emptyList())
        val count = SpeakerAnalyzer.sampleCount(duration, a.interval)
        val shots = shots()
        val samples = (0 until count).map { j ->
            val time = j * a.interval
            val index = ((start + time - a.start) / a.interval).roundToInt().coerceIn(0, a.samples.size - 1)
            val shot = shots.firstOrNull { time >= it.from && time < it.to } ?: shots.last()
            shot.manual ?: a.samples[index]
        }
        return copy(keyframes = SpeakerTrack.build(samples, a.interval, duration, cuts(), switches()))
    }
}
