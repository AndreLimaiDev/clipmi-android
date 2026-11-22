package id.clipmi.ios

import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.core.php.Php
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.RenderGraph
import id.clipmi.core.render.SegmentWords
import id.clipmi.core.render.SpeakerTrack
import id.clipmi.core.render.SubtitleEdits
import id.clipmi.core.render.TextMeasurer
import id.clipmi.core.transcript.Word
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

class RenderException(message: String) : Exception(message)

/** Info video dari AVFoundation (padanan MediaInfo Android). */
data class VideoInfo(val width: Int?, val height: Int?, val fps: Double?, val hasAudio: Boolean, val duration: Double?)

/**
 * Lebar teks untuk kotak hook, sama dengan FontMeasurer Android: ukuran ASS × unitsPerEm/(winAscent+winDescent) ×
 * 0,75 poin, diukur FreeType (text_measure_core.c yang sama dengan Android, lewat Swift).
 */
class IosFontMeasurer(private val engine: MediaEngine, private val fontsDir: String) : TextMeasurer {
    private val scales = HashMap<String, Double>()

    override fun width(family: String, size: Int, text: String): Double {
        val path = "$fontsDir/${Fonts.file(family)}"
        val scale = scales.getOrPut(path) { emScale(path) }
        return engine.textWidth(path, size * scale * 0.75, text).takeIf { it >= 0 } ?: 0.0
    }

    /** unitsPerEm / (usWinAscent + usWinDescent), dari tabel head dan OS/2 file TrueType. */
    private fun emScale(path: String): Double = runCatching {
        val b = readBytes(path) ?: return@runCatching 1.0
        fun u16(at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        val tables = HashMap<String, Int>()
        for (i in 0 until u16(4)) {
            val record = 12 + 16 * i
            val tag = (0 until 4).map { (b[record + it].toInt() and 0xFF).toChar() }.joinToString("")
            tables[tag] = u32(record + 8).toInt()
        }
        val head = tables["head"] ?: return@runCatching 1.0
        val os2 = tables["OS/2"] ?: return@runCatching 1.0
        val unitsPerEm = u16(head + 18)
        val winHeight = u16(os2 + 74) + u16(os2 + 76)
        if (winHeight > 0) unitsPerEm.toDouble() / winHeight else 1.0
    }.getOrDefault(1.0)
}

/**
 * Render klip di iPhone, padanan `Renderer.render` Android: komposisi, filtergraph, dan subtitle ASS dari core (identik
 * dengan desktop dan Android), FFmpeg di dalam proses dengan VideoToolbox, lalu disimpan ke Photos. Intro/outro dari
 * pustaka dan sampul belum ada di iOS; kartu pembuka hook didukung.
 */
class IosRenderer(private val repo: Repository, val engine: MediaEngine) {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())
    private val words = ListSerializer(Word.serializer())
    private val db get() = repo.db
    val fontsDir get() = engine.fontsDirectory()

    private suspend fun captionStyle(id: Long?): CaptionStyle =
        (id?.let { db.styles().captionStyle(it) } ?: db.styles().defaultCaptionStyle())?.toCore() ?: CaptionStyle.DEFAULT

    private suspend fun frameStyle(id: Long?): FrameStyle =
        (id?.let { db.styles().frameStyle(it) } ?: db.styles().defaultFrameStyle())?.toCore() ?: FrameStyle.DEFAULT

    /** Data klip untuk core render dengan gaya yang berlaku (dipakai juga pratinjau editor). */
    suspend fun spec(clip: ClipEntity, projectCrop: Int): ClipSpec = ClipSpec(
        startTime = clip.startTime,
        endTime = clip.endTime,
        title = clip.title,
        partNumber = clip.partNumber,
        hook = clip.hook,
        keywords = clip.keywords?.let { runCatching { json.decodeFromString(strings, it) }.getOrNull() },
        cuts = clip.cuts?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        showHookOverlay = clip.showHookOverlay,
        hookLayout = clip.hookLayout?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        subtitleLines = clip.subtitleLines?.let { runCatching { json.decodeFromString(SubtitleEdits.serializer(), it) }.getOrNull() },
        subtitleEdited = clip.subtitleEdited,
        cropZoom = clip.cropZoom,
        cropY = clip.cropY,
        cropPosition = clip.cropPosition,
        projectCropPosition = projectCrop,
        style = captionStyle(clip.captionStyleId),
        frame = frameStyle(clip.frameStyleId),
    )

    suspend fun segments(projectId: Long): List<SegmentWords> = db.segments().forProject(projectId).map {
        SegmentWords(it.startTime, it.endTime, it.words?.let { w -> runCatching { json.decodeFromString(words, w) }.getOrNull() })
    }

    fun probe(path: String): VideoInfo? = engine.probeVideo(path)?.let { text ->
        runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            VideoInfo(
                o["width"]?.jsonPrimitive?.intOrNull, o["height"]?.jsonPrimitive?.intOrNull, o["fps"]?.jsonPrimitive?.doubleOrNull,
                o["has_audio"]?.jsonPrimitive?.booleanOrNull ?: true, o["duration"]?.jsonPrimitive?.doubleOrNull,
            )
        }.getOrNull()
    }

    /**
     * Merender [clipId]; status klip (render_status/progress/error/output_path) diperbarui di database.
     * @param onProgress 0..1.
     */
    suspend fun render(clipId: Long, onProgress: (Double) -> Unit) {
        val clip = db.clips().find(clipId) ?: throw RenderException("Klip tidak ditemukan.")
        setStatus(clipId, RenderStatus.Rendering, progress = 0)
        try {
            val output = renderFile(clip, onProgress)
            val error = saveToPhotos(output)
            if (error != null) throw RenderException("Video selesai tetapi gagal disimpan ke Photos: $error")
            db.clips().find(clipId)?.let {
                db.clips().update(
                    it.copy(
                        renderStatus = RenderStatus.Done.value, renderProgress = null, renderError = null,
                        outputPath = output.removePrefix(repo.absolute("")), renderedAt = nowMillis(), updatedAt = nowMillis(),
                    ),
                )
            }
        } catch (e: Exception) {
            setStatus(clipId, RenderStatus.Failed, error = e.message ?: "Render gagal.")
            throw e
        }
    }

    private suspend fun setStatus(clipId: Long, status: RenderStatus, progress: Int? = null, error: String? = null) {
        db.clips().find(clipId)?.let {
            db.clips().update(it.copy(renderStatus = status.value, renderProgress = progress, renderError = error, updatedAt = nowMillis()))
        }
    }

    /** @return path absolut MP4 hasil (Documents/renders/<id>.mp4). */
    private suspend fun renderFile(clip: ClipEntity, onProgress: (Double) -> Unit): String {
        val project = db.projects().find(clip.projectId) ?: throw RenderException("Project tidak ditemukan.")
        val source = project.videoPath?.let { repo.absolute(it) }?.takeIf { readBytesExists(it) }
            ?: throw RenderException("Video sumber tidak ditemukan. Unduh ulang video project ini.")
        val info = probe(source)

        val spec = spec(clip, project.cropPosition)
        val frame = spec.frame
        val core = ClipRender(AssWriter(IosFontMeasurer(engine, fontsDir)))
        val sourceSize = if (frame.mode == "fit" && frame.framesVideo()) info?.let { i -> i.width?.let { w -> i.height?.let { h -> w to h } } } else null
        val composition = core.composition(spec, sourceSize)
        val duration = spec.duration()
        val length = spec.outputDuration()
        val cuts = spec.cutRanges()
        val keep = if (cuts.isNotEmpty()) ClipCuts.selectExpression(ClipCuts.kept(cuts, spec.startTime, spec.endTime), spec.startTime) else null
        val enhance = db.settings().get(KEY_ENHANCE_AUDIO) != "0"

        val workDir = repo.absolute("render-${clip.id}")
        removeItem(workDir)
        createDirectory("$workDir/fonts")
        // Font bawaan ke folder kerja (ass=...:fontsdir=fonts), seperti Android.
        for (file in Fonts.AVAILABLE.values.map { it.file } + Fonts.COVER.values.map { it.file }) {
            copyItem("$fontsDir/$file", "$workDir/fonts/$file")
        }

        val leadKind = spec.leadKind()
        val hook = spec.shownHook()
        val hookAfterJoin = leadKind != null && hook != null
        writeText(
            "$workDir/subs.ass",
            core.subtitleTrack(spec, composition, core.relativeLines(spec, segments(clip.projectId)), length, if (hookAfterJoin) null else hook, hookLayout = spec.hookLayout()),
        )
        composition.watermark?.let { writeText("$workDir/watermark.txt", it.text) }

        // Gambar bingkai (latar, template, logo) dari Documents.
        val images = linkedMapOf(
            "background" to (if (frame.background == "image" && !frame.usesTemplate()) frame.backgroundPath else null),
            "template" to (if (frame.usesTemplate()) frame.templatePath else null),
            "logo" to frame.logoPath,
        ).mapValues { (_, path) -> path?.let { repo.absolute(it) }?.takeIf { readBytesExists(it) } }.filterValues { it != null }
        val imageArgs = mutableListOf<String>()
        val numbers = HashMap<String, Int>()
        images.entries.forEachIndexed { i, (name, path) ->
            numbers[name] = i + 1
            val copy = "$name.${path!!.substringAfterLast('.', "png").lowercase()}"
            copyItem(path, "$workDir/$copy")
            imageArgs += listOf("-i", copy)
        }
        val inputs = RenderGraph.Inputs(numbers["background"], numbers["template"], numbers["logo"])

        var filter = RenderGraph.filter(composition, inputs, keep).removeSuffix("[v]") + "[joined]"
        var next = imageArgs.count { it == "-i" } + 1
        var total = length
        var audio: String? = null
        var joined = "joined"
        if (leadKind == "opener") {
            val fps = Php.numberFormat(minOf(60.0, info?.fps?.takeIf { it > 0 } ?: 30.0), 3)
            val lead = spec.leadSeconds()
            val parts = linkedMapOf(
                "intro" to RenderGraph.BumperPart(
                    next, false, lead,
                    RenderGraph.opener(next, frame, spec.speakerKeyframes?.let { SpeakerTrack.positionAt(it, 0.0) }, spec.effectiveCropPosition(), fps, lead + RenderGraph.OPENER_FADE),
                    RenderGraph.OPENER_FADE,
                ),
            )
            total += lead
            imageArgs += listOf("-ss", RenderGraph.seconds(spec.startTime), "-t", "0.5", "-i", source)
            next++
            filter = RenderGraph.joinBumpers(filter, frame, parts, fps, keep, info?.hasAudio ?: true, length, enhance)
            audio = "[a]"
            if (hookAfterJoin) {
                writeText("$workDir/hook.ass", core.hookTrack(spec, composition, hook!!, total, lead))
                filter += ";[joined]ass=hook.ass:fontsdir=fonts[hooked]"
                joined = "hooked"
            }
        }
        filter = RenderGraph.lgpl("$filter;[$joined]null[v]")

        val canvas = composition.canvas
        val bitrate = maxOf(3_000_000L, VIDEO_BITRATE * canvas.width * canvas.height / (1080L * 1920))
        val output = "$workDir/output.mp4"
        var lastError = "FFmpeg tidak menghasilkan video."
        for (encoder in ENCODERS) {
            removeItem(output)
            val args = listOf("-y", "-ss", RenderGraph.seconds(spec.startTime), "-t", RenderGraph.seconds(duration), "-i", source) +
                imageArgs +
                listOf("-t", RenderGraph.seconds(total), "-filter_complex", filter, "-map", "[v]", "-map", audio ?: "0:a:0?") +
                encoder + listOf("-b:v", bitrate.toString()) +
                (if (audio == null) RenderGraph.audioFilter(keep, enhance) else emptyList()) +
                listOf("-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-movflags", "+faststart", "output.mp4")
            val (code, tail) = runFfmpeg(args, workDir, total, onProgress)
            if (code == 255) throw RenderException("Render dibatalkan.")
            if (code == 0 && fileSize(output) > 0) {
                val target = repo.absolute("renders/${clip.id}.mp4")
                createDirectory(repo.absolute("renders"))
                removeItem(target)
                moveItem(output, target)
                removeItem(workDir)
                return target
            }
            lastError = "FFmpeg gagal ($code). ${tail.lines().takeLast(4).joinToString(" ").take(400)}"
        }
        throw RenderException(lastError)
    }

    private suspend fun runFfmpeg(args: List<String>, directory: String, duration: Double, onProgress: (Double) -> Unit): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { engine.cancelFfmpeg() }
            engine.runFfmpeg(
                args, directory, duration,
                object : RenderListener {
                    override fun progress(fraction: Double) = onProgress(fraction)
                    override fun finished(code: Int, logTail: String) {
                        if (cont.isActive) cont.resume(code to logTail)
                    }
                },
            )
        }

    private suspend fun saveToPhotos(path: String): String? = suspendCancellableCoroutine { cont ->
        engine.saveVideoToPhotos(
            path,
            object : SaveListener {
                override fun finished(error: String?) {
                    if (cont.isActive) cont.resume(error)
                }
            },
        )
    }

    fun cancel() = engine.cancelFfmpeg()

    private fun readBytesExists(path: String) = fileSize(path) > 0

    companion object {
        const val KEY_ENHANCE_AUDIO = "render.enhance_audio"

        /** 6 Mbps untuk 1080×1920 (sama dengan Android); rasio lain sebanding jumlah piksel. */
        const val VIDEO_BITRATE = 6_000_000L

        /** VideoToolbox (encoder hardware iPhone): nv12 dulu, lalu yuv420p. */
        val ENCODERS = listOf(
            listOf("-c:v", "h264_videotoolbox", "-pix_fmt", "nv12"),
            listOf("-c:v", "h264_videotoolbox", "-pix_fmt", "yuv420p"),
        )
    }
}
