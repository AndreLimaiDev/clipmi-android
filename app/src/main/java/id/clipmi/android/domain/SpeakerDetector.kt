package id.clipmi.android.domain

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import id.clipmi.android.data.AppDatabase
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
import java.io.File
import java.util.Locale
import kotlin.math.ceil

private val trackJson = Json { ignoreUnknownKeys = true }

fun decodeSpeakerTrack(json: String?): SpeakerTrackData? =
    json?.let { runCatching { trackJson.decodeFromString(SpeakerTrackData.serializer(), it) }.getOrNull() }

fun encodeSpeakerTrack(track: SpeakerTrackData): String = trackJson.encodeToString(SpeakerTrackData.serializer(), track)

/**
 * Keyframe crop yang dipakai saat render (padanan `Clip::speakerKeyframes` desktop): klip mengikuti pembicara (tanpa
 * posisi crop sendiri) dan jalurnya dibuat untuk rentang klip ini; null = crop memakai satu posisi tetap.
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

/**
 * Deteksi pembicara di HP: FFmpeg mengambil frame klip (640 px, 2 per sampel jalur) dan gambar kecil 15 fps untuk
 * potongan kamera, model wajah LiteRT mencari wajah dan mengukur bukaan mulut, lalu [SpeakerAnalyzer] memilih
 * pembicara aktif. Satu deteksi dalam satu waktu.
 */
class SpeakerDetector(private val context: Context, private val ffmpeg: Ffmpeg) {
    private val lock = Mutex()

    /**
     * @param start detik awal di video [source].
     * @param boundaries batas kalimat transkrip (waktu video).
     * @param onProgress pecahan 0–1.
     */
    suspend fun detect(source: File, start: Double, end: Double, boundaries: List<Double>, onProgress: (Float) -> Unit = {}): SpeakerTrackData.Analysis =
        lock.withLock {
            val duration = end - start
            val interval = SpeakerAnalyzer.sampleInterval(duration)
            val step = SpeakerAnalyzer.frameStep(duration)
            val dir = File(context.cacheDir, "speaker-${System.nanoTime()}").apply { mkdirs() }
            val began = System.currentTimeMillis()
            try {
                val columns = 50
                val cells = ceil(duration * SpeakerAnalyzer.CUT_FPS).toInt() + 2
                val rows = maxOf(1, ceil(cells / columns.toDouble()).toInt())
                val fps = String.format(Locale.ROOT, "%.4f", 1 / step)
                ffmpeg.run(
                    listOf(
                        "-y", "-ss", RenderGraph.seconds(start), "-t", RenderGraph.seconds(duration), "-i", source.absolutePath,
                        "-filter_complex",
                        "[0:v]split=2[a][b];[a]fps=$fps,scale=640:640:force_original_aspect_ratio=decrease[f];" +
                            "[b]fps=${SpeakerAnalyzer.CUT_FPS.toInt()},scale=32:18,tile=${columns}x$rows[s]",
                        "-map", "[f]", "-q:v", "5", "f%05d.jpg",
                        "-map", "[s]", "-frames:v", "1", "-q:v", "2", "scene.jpg",
                    ),
                    durationSeconds = duration,
                    directory = dir,
                ) { onProgress(it / 100f * 0.25f) }

                withContext(Dispatchers.Default) {
                    val cuts = sceneCuts(File(dir, "scene.jpg"), columns, rows, duration)
                    val files = dir.listFiles { f -> f.name.startsWith("f") && f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
                    val frames = ArrayList<AnalysisFrame>(files.size)
                    var measured = 0
                    FaceModels(context).use { models ->
                        files.forEachIndexed { i, file ->
                            ensureActive()
                            val bitmap = BitmapFactory.decodeFile(file.path) ?: return@forEachIndexed
                            val width = bitmap.width.toDouble()
                            val faces = models.faces(bitmap)
                            val largest = faces.maxOfOrNull { it.width } ?: 0f
                            val candidates = faces.filter { it.width >= largest * SpeakerAnalyzer.CANDIDATE_SIZE }
                            // Bukaan mulut hanya perlu bila ada lebih dari satu calon pembicara.
                            val observed = faces.map { face ->
                                val opening = if (candidates.size >= 2 && face in candidates) models.mouthOpening(bitmap, face).also { measured++ } else null
                                FaceObservation(face.centerX / width, face.width / width, opening)
                            }
                            bitmap.recycle()
                            frames += AnalysisFrame(i * step, observed)
                            onProgress(0.25f + 0.75f * (i + 1) / files.size)
                        }
                    }
                    val relative = boundaries.map { it - start }.filter { it > 0 && it < duration }
                    val result = SpeakerAnalyzer.analyze(frames, interval, duration, cuts, relative)
                    Log.i(
                        "ClipMiSpeaker",
                        "Deteksi ${"%.1f".format(Locale.ROOT, duration)} dtk: ${files.size} frame, ${result.faceFrames} berwajah, " +
                            "$measured ukur mulut, ${cuts.size} potongan kamera, ${result.switches.size} ganti pembicara, " +
                            "${System.currentTimeMillis() - began} ms",
                    )
                    SpeakerTrackData.Analysis(start, end, interval, result.samples.map { v -> v?.let { Math.round(it * 1000) / 1000.0 } }, result.cuts.map { Math.round((start + it) * 1000) / 1000.0 })
                }
            } finally {
                dir.deleteRecursively()
            }
        }

    /** Potongan kamera dari lembar frame kecil 15 fps (sel 32×18, [columns] per baris). */
    private fun sceneCuts(sheet: File, columns: Int, rows: Int, duration: Double): List<Double> {
        val bitmap = BitmapFactory.decodeFile(sheet.path) ?: return emptyList()
        val cellW = bitmap.width / columns
        val cellH = bitmap.height / rows
        if (cellW <= 0 || cellH <= 0) return emptyList()
        val pixels = IntArray(cellW * cellH)
        val count = minOf(columns * rows, (duration * SpeakerAnalyzer.CUT_FPS).toInt())
        val luma = ArrayList<IntArray>(count)
        for (k in 0 until count) {
            bitmap.getPixels(pixels, 0, cellW, (k % columns) * cellW, (k / columns) * cellH, cellW, cellH)
            luma += IntArray(pixels.size) { i ->
                val p = pixels[i]
                ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            }
        }
        bitmap.recycle()
        // Sel kosong (hitam) di ujung lembar bukan frame video.
        while (luma.isNotEmpty() && luma.last().all { it < 4 }) luma.removeAt(luma.size - 1)
        return SpeakerAnalyzer.cameraCuts(luma).filter { it < duration - 0.5 }
    }
}

/**
 * Jalur "ikuti pembicara" per klip: kapan perlu dideteksi, deteksi dan penyimpanannya, serta posisi manual per adegan.
 */
class SpeakerTracks(private val database: AppDatabase, private val workspace: Workspace, private val detector: SpeakerDetector) {
    /**
     * Perlu dideteksi (padanan `Clip::needsSpeakerTrack` desktop): klip mengikuti pembicara, gaya bingkainya memotong
     * video, dan hasil deteksinya belum mencakup rentang klip.
     */
    fun needsDetection(clip: ClipEntity, frame: FrameStyle): Boolean =
        clip.cropPosition == null && frame.usesCropPosition() && decodeSpeakerTrack(clip.speakerTrack)?.covers(clip.startTime, clip.endTime) != true

    /** Video yang dianalisis: video pratinjau (lebih ringan) atau video sumber; posisinya pecahan lebar, jadi sama. */
    fun source(project: ProjectEntity): File? =
        listOf(project.previewPath, project.videoPath).firstNotNullOfOrNull { path -> workspace.file(path)?.takeIf { it.isFile } }

    /**
     * Mendeteksi pembicara di rentang [start]–[end]; posisi manual sebelumnya yang masih di dalam rentang tetap dipakai.
     * @return jalur yang sudah disimpan ke klip.
     */
    suspend fun detect(
        clip: ClipEntity, project: ProjectEntity, start: Double = clip.startTime, end: Double = clip.endTime,
        manual: List<SpeakerTrackData.ManualPosition> = decodeSpeakerTrack(clip.speakerTrack)?.manual.orEmpty(), onProgress: (Float) -> Unit = {},
    ): SpeakerTrackData {
        val source = source(project) ?: throw RenderException("Video untuk deteksi pembicara tidak ditemukan.")
        val boundaries = database.segments().forProject(project.id)
            .filter { it.endTime >= start - 1 && it.startTime <= end + 1 }
            .flatMap { listOf(it.startTime, it.endTime) }
            .distinct()
        val analysis = detector.detect(source, start, end, boundaries, onProgress)
        val previous = manual.filter { it.at in start..end }
        val track = SpeakerTrackData(start, end, analysis = analysis, manual = previous).rebuilt()
        save(clip.id, track)
        return track
    }

    suspend fun save(clipId: Long, track: SpeakerTrackData?) = database.clips().setSpeakerTrack(clipId, track?.let(::encodeSpeakerTrack))

    /**
     * Sebelum render: deteksi bila perlu, atau jalur disesuaikan ke rentang klip yang sudah dipendekkan.
     * @return klip dengan jalur terbaru.
     */
    suspend fun ensure(clip: ClipEntity, frame: FrameStyle, onProgress: (Float) -> Unit): ClipEntity {
        if (clip.cropPosition != null || !frame.usesCropPosition()) return clip
        val project = database.projects().find(clip.projectId) ?: return clip
        val stored = decodeSpeakerTrack(clip.speakerTrack)
        val track = when {
            stored != null && stored.matches(clip.startTime, clip.endTime) -> return clip
            stored != null && stored.covers(clip.startTime, clip.endTime) -> stored.retarget(clip.startTime, clip.endTime).also { save(clip.id, it) }
            else -> detect(clip, project, onProgress = onProgress)
        }
        return clip.copy(speakerTrack = encodeSpeakerTrack(track))
    }
}
