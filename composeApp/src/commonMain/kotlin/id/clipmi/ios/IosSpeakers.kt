package id.clipmi.ios

import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.core.render.AnalysisFrame
import id.clipmi.core.render.FaceObservation
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.RenderGraph
import id.clipmi.core.render.SpeakerAnalyzer
import id.clipmi.core.render.SpeakerTrackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.roundToLong

private val trackJson = Json { ignoreUnknownKeys = true }

fun decodeSpeakerTrack(json: String?): SpeakerTrackData? =
    json?.let { runCatching { trackJson.decodeFromString(SpeakerTrackData.serializer(), it) }.getOrNull() }

fun encodeSpeakerTrack(track: SpeakerTrackData): String = trackJson.encodeToString(SpeakerTrackData.serializer(), track)

/**
 * Keyframe crop yang dipakai saat render (sama dengan Android): klip mengikuti pembicara (tanpa posisi crop sendiri) dan
 * jalurnya mencakup rentang klip; null = crop memakai satu posisi tetap.
 */
fun ClipEntity.speakerKeyframes(): List<List<Double>>? {
    if (cropPosition != null) return null
    val track = decodeSpeakerTrack(speakerTrack) ?: return null
    val current = when {
        track.matches(startTime, endTime) -> track
        track.covers(startTime, endTime) -> track.retarget(startTime, endTime)
        else -> return null
    }
    return current.keyframes.takeIf { it.isNotEmpty() }
}

private fun round3(value: Double) = (value * 1000).roundToLong() / 1000.0

/**
 * Deteksi pembicara di iPhone (padanan SpeakerDetector/SpeakerTracks Android): FFmpeg mengambil frame klip (640 px, dua
 * per sampel jalur) dan lembar gambar kecil untuk potongan kamera, Apple Vision mencari wajah dan bukaan mulut, lalu
 * [SpeakerAnalyzer] (core, sama dengan Android) memilih pembicara aktif. Hasil iOS bisa sedikit berbeda dari Android
 * karena detektor wajahnya lain.
 */
class IosSpeakers(private val repo: Repository, private val renderer: IosRenderer) {
    private val lock = Mutex()
    private val engine get() = renderer.engine

    /** Perlu dideteksi: mengikuti pembicara, bingkai memotong video, dan hasil deteksi belum mencakup rentang klip. */
    fun needsDetection(clip: ClipEntity, frame: FrameStyle): Boolean =
        clip.cropPosition == null && frame.usesCropPosition() && decodeSpeakerTrack(clip.speakerTrack)?.covers(clip.startTime, clip.endTime) != true

    /**
     * Mendeteksi pembicara di rentang [start]–[end]; posisi manual yang masih di dalam rentang tetap dipakai. Jalurnya
     * disimpan ke klip.
     */
    suspend fun detect(
        clip: ClipEntity, project: ProjectEntity, start: Double = clip.startTime, end: Double = clip.endTime,
        manual: List<SpeakerTrackData.ManualPosition> = decodeSpeakerTrack(clip.speakerTrack)?.manual.orEmpty(),
        onProgress: (Float) -> Unit = {},
    ): SpeakerTrackData {
        val source = project.videoPath?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 }
            ?: throw RenderException("Video untuk deteksi pembicara tidak ditemukan.")
        val boundaries = repo.db.segments().forProject(project.id)
            .filter { it.endTime >= start - 1 && it.startTime <= end + 1 }
            .flatMap { listOf(it.startTime, it.endTime) }
            .distinct()
        val analysis = analyze(source, start, end, boundaries, onProgress)
        val track = SpeakerTrackData(start, end, analysis = analysis, manual = manual.filter { it.at in start..end }).rebuilt()
        save(clip.id, track)
        return track
    }

    suspend fun save(clipId: Long, track: SpeakerTrackData?) = repo.db.clips().setSpeakerTrack(clipId, track?.let(::encodeSpeakerTrack))

    /** Sebelum render: deteksi bila perlu, atau jalur disesuaikan ke rentang klip. @return klip dengan jalur terbaru. */
    suspend fun ensure(clip: ClipEntity, frame: FrameStyle, onProgress: (Float) -> Unit): ClipEntity {
        if (clip.cropPosition != null || !frame.usesCropPosition()) return clip
        val project = repo.db.projects().find(clip.projectId) ?: return clip
        val stored = decodeSpeakerTrack(clip.speakerTrack)
        val track = when {
            stored != null && stored.matches(clip.startTime, clip.endTime) -> return clip
            stored != null && stored.covers(clip.startTime, clip.endTime) -> stored.retarget(clip.startTime, clip.endTime).also { save(clip.id, it) }
            else -> detect(clip, project, onProgress = onProgress)
        }
        return clip.copy(speakerTrack = encodeSpeakerTrack(track))
    }

    private suspend fun analyze(source: String, start: Double, end: Double, boundaries: List<Double>, onProgress: (Float) -> Unit): SpeakerTrackData.Analysis =
        lock.withLock {
            val duration = end - start
            val interval = SpeakerAnalyzer.sampleInterval(duration)
            val step = SpeakerAnalyzer.frameStep(duration)
            val dir = repo.absolute("speaker")
            removeItem(dir)
            createDirectory(dir)
            try {
                val columns = 50
                // Potongan kamera dicari di setiap frame (paling banyak 30 per detik); klip sangat panjang cukup 15.
                val native = renderer.probe(source)?.fps?.takeIf { it in 5.0..240.0 } ?: 30.0
                val cutFps = if (duration > 300) SpeakerAnalyzer.CUT_FPS else native / ceil(native / 30.5)
                val cells = ceil(duration * cutFps).toInt() + 2
                val rows = maxOf(1, ceil(cells / columns.toDouble()).toInt())
                val (code, tail) = renderer.runFfmpeg(
                    listOf(
                        "-y", "-ss", RenderGraph.seconds(start), "-t", RenderGraph.seconds(duration), "-i", source,
                        "-filter_complex",
                        "[0:v]split=2[a][b];[a]fps=${fixed(1 / step)},scale=640:640:force_original_aspect_ratio=decrease[f];" +
                            "[b]fps=${fixed(cutFps)},scale=32:18,tile=${columns}x$rows[s]",
                        "-map", "[f]", "-q:v", "5", "f%05d.jpg",
                        "-map", "[s]", "-frames:v", "1", "-q:v", "2", "scene.jpg",
                    ),
                    dir, duration,
                ) { onProgress((it * 0.25).toFloat()) }
                if (code == 255) throw RenderException("Deteksi pembicara dibatalkan.")
                if (code != 0) throw RenderException("FFmpeg gagal ($code). ${tail.lines().takeLast(3).joinToString(" ").take(300)}")

                val cuts = sceneCuts("$dir/scene.jpg", "$dir/scene.gray", columns, rows, duration, cutFps)
                val files = listDirectory(dir).filter { it.startsWith("f") && it.endsWith(".jpg") }.sorted()
                val frames = ArrayList<AnalysisFrame>(files.size)
                files.forEachIndexed { i, name ->
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val faces = withContext(Dispatchers.Default) { parseFaces(engine.detectFaces("$dir/$name")) }
                    val largest = faces.maxOfOrNull { it.width } ?: 0.0
                    val candidates = faces.count { it.width >= largest * SpeakerAnalyzer.CANDIDATE_SIZE }
                    // Bukaan mulut hanya dipakai bila ada lebih dari satu calon pembicara (sama dengan Android).
                    frames += AnalysisFrame(
                        i * step,
                        faces.map { face -> if (candidates >= 2 && face.width >= largest * SpeakerAnalyzer.CANDIDATE_SIZE) face else face.copy(opening = null) },
                    )
                    onProgress(0.25f + 0.75f * (i + 1) / files.size)
                }
                val relative = boundaries.map { it - start }.filter { it > 0 && it < duration }
                val result = SpeakerAnalyzer.analyze(frames, interval, duration, cuts, relative)
                SpeakerTrackData.Analysis(
                    start, end, interval,
                    result.samples.map { v -> v?.let(::round3) },
                    result.cuts.map { round3(start + it) },
                    result.switches.map { round3(start + it) },
                )
            } finally {
                removeItem(dir)
            }
        }

    private fun parseFaces(json: String): List<FaceObservation> = runCatching {
        trackJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val o = element.jsonObject
            val cx = o["cx"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val w = o["w"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            FaceObservation(cx, w, o["open"]?.jsonPrimitive?.doubleOrNull)
        }
    }.getOrDefault(emptyList())

    /** Potongan kamera dari lembar frame kecil [fps] per detik (sel 32×18, [columns] per baris). */
    private fun sceneCuts(sheet: String, gray: String, columns: Int, rows: Int, duration: Double, fps: Double): List<Double> {
        val size = engine.grayPixels(sheet, gray) ?: return emptyList()
        val width = size.substringBefore('x').toIntOrNull() ?: return emptyList()
        val height = size.substringAfter('x').toIntOrNull() ?: return emptyList()
        val bytes = readBytes(gray) ?: return emptyList()
        val cellW = width / columns
        val cellH = height / rows
        if (cellW <= 0 || cellH <= 0 || bytes.size < width * height) return emptyList()
        val count = minOf(columns * rows, (duration * fps).toInt())
        val luma = ArrayList<IntArray>(count)
        for (k in 0 until count) {
            val x0 = (k % columns) * cellW
            val y0 = (k / columns) * cellH
            luma += IntArray(cellW * cellH) { i -> bytes[(y0 + i / cellW) * width + x0 + i % cellW].toInt() and 0xFF }
        }
        // Sel kosong (hitam) di ujung lembar bukan frame video.
        while (luma.isNotEmpty() && luma.last().all { it < 4 }) luma.removeAt(luma.size - 1)
        return SpeakerAnalyzer.cameraCuts(luma, fps).filter { it < duration - 0.5 }
    }

    /** Angka dengan 4 desimal dan titik (argumen fps FFmpeg). */
    private fun fixed(value: Double): String {
        val scaled = (value * 10000).roundToLong()
        val whole = scaled / 10000
        val fraction = (scaled % 10000).toString().padStart(4, '0')
        return "$whole.$fraction"
    }
}
