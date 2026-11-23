package id.clipmi.ios

import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.core.php.Php
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSilence
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.SubtitleLine
import id.clipmi.core.render.Composition
import id.clipmi.core.render.CoverTitle
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.Range
import id.clipmi.core.render.RenderGraph
import id.clipmi.core.render.SegmentWords
import id.clipmi.core.render.SpeakerTrack
import id.clipmi.core.render.SubtitleEdits
import id.clipmi.core.render.TextMeasurer
import id.clipmi.core.transcript.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        val path = IosUserFonts.file(fontsDir, family)
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
 * Render klip di iPhone, padanan `Renderer` Android: komposisi, filtergraph, dan subtitle ASS dari core (identik dengan
 * desktop dan Android), FFmpeg di dalam proses dengan VideoToolbox, lalu disimpan ke Photos. Sampul (frame atau gambar
 * galeri, judul), kartu pembuka hook, dan intro/outro dari pustaka didukung.
 *
 * FFmpeg di dalam proses memakai keadaan global, jadi semua pemanggilan (render, sampul, deteksi jeda) antre lewat
 * [ffmpegLock] dan selalu ditunggu sampai benar-benar selesai.
 */
class IosRenderer(private val repo: Repository, val engine: MediaEngine) {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())
    private val words = ListSerializer(Word.serializer())
    private val db get() = repo.db
    private val ffmpegLock = Mutex()

    /** Font tambahan pengguna; [fontsDir] = folder gabungan font bawaan + tambahan (disamakan saat aplikasi dibuka). */
    val userFonts = IosUserFonts(repo, engine).also { it.sync() }
    val fontsDir get() = userFonts.dir

    /** Jalur "ikuti pembicara" (Apple Vision). */
    val speakers = IosSpeakers(repo, this)

    /** Gaya klip, bawaan, atau yang pertama (sama dengan editor Android). */
    fun captionFor(id: Long?, styles: List<CaptionStyleEntity>): CaptionStyle =
        (id?.let { i -> styles.firstOrNull { it.id == i } } ?: styles.firstOrNull { it.isDefault } ?: styles.firstOrNull())?.toCore() ?: CaptionStyle.DEFAULT

    fun frameFor(id: Long?, styles: List<FrameStyleEntity>): FrameStyle =
        (id?.let { i -> styles.firstOrNull { it.id == i } } ?: styles.firstOrNull { it.isDefault } ?: styles.firstOrNull())?.toCore() ?: FrameStyle.DEFAULT

    fun keywords(clip: ClipEntity): List<String>? = clip.keywords?.let { runCatching { json.decodeFromString(strings, it) }.getOrNull() }

    fun encodeKeywords(list: List<String>): String? = list.takeIf { it.isNotEmpty() }?.let { json.encodeToString(strings, it) }

    /** Data klip untuk core render dengan gaya yang sudah dipilih (dipakai juga pratinjau editor). */
    fun specFor(
        clip: ClipEntity, projectCrop: Int, caption: CaptionStyle, frame: FrameStyle, introSeconds: Double? = null, outroSeconds: Double? = null,
    ): ClipSpec = ClipSpec(
        startTime = clip.startTime,
        endTime = clip.endTime,
        title = clip.title,
        partNumber = clip.partNumber,
        hook = clip.hook,
        keywords = keywords(clip),
        cuts = clip.cuts?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        showHookOverlay = clip.showHookOverlay,
        hookLayout = clip.hookLayout?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        subtitleLines = clip.subtitleLines?.let { runCatching { json.decodeFromString(SubtitleEdits.serializer(), it) }.getOrNull() },
        subtitleEdited = clip.subtitleEdited,
        cropZoom = clip.cropZoom,
        cropY = clip.cropY,
        cropPosition = clip.cropPosition,
        projectCropPosition = projectCrop,
        style = caption,
        frame = frame,
        speakerKeyframes = clip.speakerKeyframes(),
        introSeconds = introSeconds,
        outroSeconds = outroSeconds,
    )

    suspend fun spec(clip: ClipEntity, projectCrop: Int): ClipSpec =
        specFor(
            clip, projectCrop, captionFor(clip.captionStyleId, db.styles().captionStyles()), frameFor(clip.frameStyleId, db.styles().frameStyles()),
            bumper(clip.introId)?.duration, bumper(clip.outroId)?.duration,
        )

    /** Intro/outro yang ikut dirender: dipilih dan filenya ada (sama dengan Android). */
    suspend fun bumper(id: Long?): BumperEntity? = id?.let { db.bumpers().find(it) }?.takeIf { exists(repo.absolute(it.path)) }

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

    /** Satu frame video sebagai JPEG kecil (latar blur pratinjau); false bila gagal. */
    suspend fun videoFrame(source: String, seconds: Double, output: String, maxSize: Int = 360): Boolean =
        withContext(Dispatchers.Default) { engine.videoFrame(source, seconds, output, maxSize) }

    /**
     * Merender [clipId]; status klip (render_status/progress/error/output_path) diperbarui di database.
     * @param onProgress 0..1.
     */
    suspend fun render(clipId: Long, onProgress: (Double) -> Unit) {
        val stored = db.clips().find(clipId) ?: throw RenderException("Klip tidak ditemukan.")
        setStatus(clipId, RenderStatus.Rendering, progress = 0)
        try {
            // Ikuti pembicara tanpa hasil deteksi untuk rentang ini: dideteksi dulu (30% pertama progres).
            val frame = frameFor(stored.frameStyleId, db.styles().frameStyles())
            val detect = speakers.needsDetection(stored, frame)
            val clip = speakers.ensure(stored, frame) { onProgress(it * 0.3) }
            val (output, cover) = renderFile(clip) { onProgress(if (detect) 0.3 + it * 0.7 else it) }
            val error = saveToPhotos(output, image = false)
            if (error != null) throw RenderException("Video selesai tetapi gagal disimpan ke Photos: $error")
            // Sampul ikut disimpan, siap dipakai sebagai cover saat mengunggah; gagal di sini tidak menggagalkan render.
            if (cover != null) saveToPhotos(cover, image = true)
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

    /** Font bawaan dan font tambahan ke folder kerja (ass=...:fontsdir=fonts), seperti Android. */
    private fun copyFonts(workDir: String) {
        createDirectory("$workDir/fonts")
        for (file in listDirectory(fontsDir).filter { it.endsWith(".ttf") || it.endsWith(".otf") }) {
            copyItem("$fontsDir/$file", "$workDir/fonts/$file")
        }
    }

    /**
     * Folder kerja untuk satu frame contoh (pratinjau gaya bingkai): font, subtitle, watermark, dan gambar bingkai;
     * input 0 = gambar contoh.
     */
    internal fun prepareStill(
        core: ClipRender, spec: ClipSpec, composition: Composition, workDir: String, lines: List<SubtitleLine>, length: Double,
    ): Pair<RenderGraph.Inputs, MutableList<String>> {
        copyFonts(workDir)
        writeText("$workDir/subs.ass", core.subtitleTrack(spec, composition, lines, length, null))
        composition.watermark?.let { writeText("$workDir/watermark.txt", it.text) }
        return frameImages(spec.frame, workDir)
    }

    /** Gambar bingkai (latar, template, logo) dari Documents ke folder kerja; nomor input mulai dari 1. */
    private fun frameImages(frame: FrameStyle, workDir: String): Pair<RenderGraph.Inputs, MutableList<String>> {
        val images = linkedMapOf(
            "background" to (if (frame.background == "image" && !frame.usesTemplate()) frame.backgroundPath else null),
            "template" to (if (frame.usesTemplate()) frame.templatePath else null),
            "logo" to frame.logoPath,
        ).mapValues { (_, path) -> path?.let { repo.absolute(it) }?.takeIf { exists(it) } }.filterValues { it != null }
        val args = mutableListOf<String>()
        val numbers = HashMap<String, Int>()
        images.entries.forEachIndexed { i, (name, path) ->
            numbers[name] = i + 1
            val copy = "$name.${path!!.substringAfterLast('.', "png").lowercase()}"
            copyItem(path, "$workDir/$copy")
            args += listOf("-i", copy)
        }
        return RenderGraph.Inputs(numbers["background"], numbers["template"], numbers["logo"]) to args
    }

    /** @return path absolut MP4 hasil (Documents/renders/<id>.mp4) dan sampul yang dipakai (bila ada). */
    private suspend fun renderFile(clip: ClipEntity, onProgress: (Double) -> Unit): Pair<String, String?> {
        val project = db.projects().find(clip.projectId) ?: throw RenderException("Project tidak ditemukan.")
        val source = project.videoPath?.let { repo.absolute(it) }?.takeIf { exists(it) }
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
        // Sampul dibuat sebelum folder kerja render (FFmpeg sendiri); gagal = render tanpa sampul.
        val cover = if (clip.hasCover) runCatching { cover(clip, project) }.getOrNull() else null

        val workDir = repo.absolute("render-${clip.id}")
        removeItem(workDir)
        copyFonts(workDir)

        val leadKind = spec.leadKind()
        val hook = spec.shownHook()
        val hookAfterJoin = leadKind != null && hook != null
        writeText(
            "$workDir/subs.ass",
            core.subtitleTrack(spec, composition, core.relativeLines(spec, segments(clip.projectId)), length, if (hookAfterJoin) null else hook, hookLayout = spec.hookLayout()),
        )
        composition.watermark?.let { writeText("$workDir/watermark.txt", it.text) }
        val (inputs, imageArgs) = frameImages(frame, workDir)

        var filter = RenderGraph.filter(composition, inputs, keep).removeSuffix("[v]") + "[joined]"
        var next = imageArgs.count { it == "-i" } + 1
        var coverInput: Int? = null
        if (cover != null && clip.coverInVideo) {
            coverInput = next++
            copyItem(cover, "$workDir/cover.jpg")
            imageArgs += listOf("-i", "cover.jpg")
        }
        // Intro/outro dari pustaka, atau kartu pembuka: disambung dengan klip; suaranya ikut dirangkai di filtergraph.
        val bumpers = listOfNotNull(bumper(clip.introId)?.let { "intro" to it }, bumper(clip.outroId)?.let { "outro" to it })
        var total = length
        var audio: String? = null
        var joined = "joined"
        if (bumpers.isNotEmpty() || leadKind != null) {
            val fps = Php.numberFormat(minOf(60.0, info?.fps?.takeIf { it > 0 } ?: 30.0), 3)
            val parts = LinkedHashMap<String, RenderGraph.BumperPart>()
            for ((kind, bumper) in bumpers) {
                val file = repo.absolute(bumper.path)
                val bumperInfo = probe(file)
                val part = RenderGraph.BumperPart(next, bumperInfo?.hasAudio ?: false, bumperInfo?.duration?.takeIf { it > 0 } ?: bumper.duration)
                parts[kind] = part
                total += part.duration
                imageArgs += listOf("-i", file)
                next++
                db.bumpers().touchUsed(bumper.id)
            }
            // Kartu pembuka: frame pertama klip dari video sumber (input tersendiri, hanya sekejap dibaca).
            if (leadKind == "opener") {
                val lead = spec.leadSeconds()
                parts["intro"] = RenderGraph.BumperPart(
                    next, false, lead,
                    RenderGraph.opener(next, frame, spec.speakerKeyframes?.let { SpeakerTrack.positionAt(it, 0.0) }, spec.effectiveCropPosition(), fps, lead + RenderGraph.OPENER_FADE),
                    RenderGraph.OPENER_FADE,
                )
                total += lead
                imageArgs += listOf("-ss", RenderGraph.seconds(spec.startTime), "-t", "0.5", "-i", source)
                next++
            }
            filter = RenderGraph.joinBumpers(filter, frame, parts, fps, keep, info?.hasAudio ?: true, length, enhance)
            audio = "[a]"
            // Hook di waktu video hasil: sejak awal isi klip, digeser sepanjang pembuka yang benar-benar disambung.
            if (hookAfterJoin) {
                writeText("$workDir/hook.ass", core.hookTrack(spec, composition, hook!!, total, parts["intro"]?.duration ?: 0.0))
                filter += ";[joined]ass=hook.ass:fontsdir=fonts[hooked]"
                joined = "hooked"
            }
        }
        // Sampul di awal video: menutupi 0,1 detik pertama (3 frame) untuk platform yang memakai frame pertama sebagai
        // cover, tanpa menggeser suara (sama dengan Android).
        filter += if (coverInput != null) {
            ";[$coverInput:v]scale=${frame.canvasWidth()}:${frame.canvasHeight()},setsar=1[cover];[$joined][cover]overlay=0:0:enable='lt(t,0.1)'[v]"
        } else {
            ";[$joined]null[v]"
        }
        filter = RenderGraph.lgpl(filter)

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
                return target to cover
            }
            lastError = "FFmpeg gagal ($code). ${tail.lines().takeLast(4).joinToString(" ").take(400)}"
        }
        throw RenderException(lastError)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sampul
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sampul klip (padanan `Renderer.ensureCover` Android), dibuat saat pertama diminta. Nama file memuat kunci dari
     * pengaturan sampul, judul, gaya, dan crop, jadi dibuat ulang begitu salah satunya berubah.
     * @return path absolut JPEG.
     */
    suspend fun cover(clip: ClipEntity, project: ProjectEntity, caption: CaptionStyle? = null, frame: FrameStyle? = null): String {
        val spec = if (caption != null && frame != null) specFor(clip, project.cropPosition, caption, frame) else spec(clip, project.cropPosition)
        val dir = repo.absolute("${repo.projectDir(project.id)}/covers")
        createDirectory(dir)
        val name = "clip-${clip.id}-${coverKey(clip, project, spec)}.jpg"
        val file = "$dir/$name"
        if (!exists(file)) {
            listDirectory(dir).filter { it.startsWith("clip-${clip.id}-") }.forEach { removeItem("$dir/$it") }
            renderCover(clip, project, spec, file)
        }
        return file
    }

    /** Menyimpan sampul klip ke Photos; null = tersimpan, selain itu pesan gagal. */
    suspend fun saveCover(clip: ClipEntity, project: ProjectEntity, caption: CaptionStyle, frame: FrameStyle): String? =
        saveToPhotos(cover(clip, project, caption, frame), image = true)

    private fun coverKey(clip: ClipEntity, project: ProjectEntity, spec: ClipSpec): String {
        val parts = listOf(
            // Versi cara membuat sampul (sama dengan Android v2: sampul dari frame hanya berisi video).
            "v2",
            clip.coverTime, clip.coverPath, clip.coverTitle, clip.coverLayout, clip.keywords, spec.displayTitle(), spec.frame, spec.style,
            spec.speakerKeyframes, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), clip.startTime, project.videoPath,
        )
        // FNV-1a 64 bit (MD5 tidak ada di Kotlin common); cukup untuk membedakan pengaturan sampul.
        var hash = -0x340d631b7bdddcdbL
        for (byte in parts.toString().encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0').take(12)
    }

    /**
     * Satu frame video saja (crop, zoom, posisi klip; tanpa bingkai, logo, watermark, dan subtitle), atau gambar dari
     * galeri yang dipotong seukuran layar; judul klip bisa ditulis besar di atasnya (sama dengan Android).
     */
    private suspend fun renderCover(clip: ClipEntity, project: ProjectEntity, spec: ClipSpec, output: String) {
        val frame = spec.frame
        val image = clip.coverPath?.let { repo.absolute(it) }?.takeIf { exists(it) }
        val video = project.videoPath?.let { repo.absolute(it) }?.takeIf { exists(it) }
        if (image == null && video == null) throw RenderException("Sampul butuh video klip atau gambar sampul.")

        val width = frame.canvasWidth()
        val height = frame.canvasHeight()
        // Model judul tipografi (bukan klasik): judul digambar di atas frame jadi.
        val layout = CoverTitle.parse(clip.coverLayout)
        val styled = clip.coverTitle && !layout.classic
        val title = if (clip.coverTitle && !styled) spec.displayTitle() else null
        // Judul besar di sekitar tiga perlima tinggi layar, di atas area subtitle.
        val titleTop = layout.y?.let { Php.roundInt(height * it.coerceIn(0, 100) / 100.0) } ?: Php.roundInt(height * 0.58)
        val measurer = IosFontMeasurer(engine, fontsDir)
        val core = ClipRender(AssWriter(measurer))
        val workDir = repo.absolute("cover-${clip.id}")
        removeItem(workDir)
        copyFonts(workDir)
        try {
            val (args, filter) = if (image != null) {
                writeText(
                    "$workDir/subs.ass",
                    AssWriter(measurer).build(emptyList(), 2.0, title, spec.style, null, null, Triple(width, height, frame.uiScale()), titleTop, COVER_TITLE_SIZE),
                )
                copyItem(image, "$workDir/upload.jpg")
                listOf("-loop", "1", "-t", "1", "-i", "upload.jpg") to
                    "[0:v]scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,setsar=1,ass=subs.ass:fontsdir=fonts[v]"
            } else {
                val time = clip.coverTime ?: clip.startTime
                val speaker = spec.speakerKeyframes?.let { listOf(listOf(0.0, SpeakerTrack.positionAt(it, time - clip.startTime))) }
                val plain = FrameStyle(aspect = frame.aspect, mode = "crop", zoom = frame.zoom)
                val composition = Composition.make(plain, null, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), speaker)
                val plainSpec = spec.copy(frame = plain)
                writeText("$workDir/subs.ass", core.subtitleTrack(plainSpec, composition, emptyList(), 2.0, title, titleTop, COVER_TITLE_SIZE))
                composition.watermark?.let { writeText("$workDir/watermark.txt", it.text) }
                val (inputs, imageArgs) = frameImages(plain, workDir)
                (listOf("-ss", RenderGraph.seconds(maxOf(0.0, time - 0.4)), "-t", "1.5", "-i", video!!) + imageArgs) to RenderGraph.filter(composition, inputs)
            }
            var graph = filter
            if (styled) {
                val keywords = keywords(clip).orEmpty()
                writeText("$workDir/cover.ass", CoverTitle.build(layout, spec.displayTitle(), keywords, width, height, measurer))
                graph = graph.removeSuffix("[v]") + "[vt];[vt]ass=cover.ass:fontsdir=fonts[v]"
            }
            // Frame di detik 0,4: judul sudah selesai muncul pelan.
            val (code, tail) = runFfmpeg(
                listOf("-y") + args + listOf("-filter_complex", RenderGraph.lgpl(graph), "-map", "[v]", "-ss", "0.4", "-frames:v", "1", "-q:v", "3", "cover.jpg"),
                workDir, 0.0,
            ) {}
            if (code != 0 || fileSize("$workDir/cover.jpg") <= 0) {
                throw RenderException("Sampul tidak berhasil dibuat ($code). ${tail.lines().takeLast(3).joinToString(" ").take(300)}")
            }
            moveItem("$workDir/cover.jpg", output)
        } finally {
            removeItem(workDir)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Jeda
    // ---------------------------------------------------------------------------------------------------------------

    /** Jeda di suara klip (volumedetect lalu silencedetect, sama dengan `Ffmpeg.silences` Android). */
    suspend fun silences(source: String, start: Double, duration: Double): List<Range> {
        val dir = repo.absolute("silence")
        removeItem(dir)
        createDirectory(dir)
        try {
            val input = listOf("-ss", Php.numberFormat(start, 3), "-t", Php.numberFormat(duration, 3), "-i", source, "-vn")
            val volume = ffmpegLog(input + listOf("-af", "volumedetect", "-f", "null", "-"), dir)
            val mean = Regex("""mean_volume:\s*(-?[\d.]+) dB""").find(volume)?.groupValues?.get(1)?.toDouble()
                ?: throw RenderException("Volume klip tidak terbaca")
            val noise = ClipSilence.noiseThreshold(mean)
            val log = ffmpegLog(input + listOf("-af", "silencedetect=noise=${Php.numberFormat(noise, 1)}dB:d=${ClipSilence.MIN}", "-f", "null", "-"), dir)
            return ClipSilence.parse(log, start, start + duration)
        } finally {
            removeItem(dir)
        }
    }

    /** Log lengkap satu pemanggilan FFmpeg (file log ditulis bersambung, jadi dihapus dulu). */
    private suspend fun ffmpegLog(args: List<String>, dir: String): String {
        removeItem("$dir/ffmpeg.log")
        val (code, tail) = runFfmpeg(args, dir, 0.0) {}
        if (code != 0) throw RenderException("FFmpeg gagal ($code). ${tail.lines().takeLast(3).joinToString(" ").take(300)}")
        return readText("$dir/ffmpeg.log").orEmpty()
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Satu FFmpeg pada satu waktu. Bila coroutine dibatalkan, FFmpeg dihentikan lalu tetap ditunggu sampai selesai
     * sebelum giliran berikutnya (keadaan global FFmpeg tidak boleh dipakai dua pemanggilan sekaligus).
     */
    internal suspend fun runFfmpeg(args: List<String>, directory: String, duration: Double, onProgress: (Double) -> Unit): Pair<Int, String> =
        ffmpegLock.withLock {
            withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Pair<Int, String>>()
                engine.runFfmpeg(
                    args, directory, duration,
                    object : RenderListener {
                        override fun progress(fraction: Double) = onProgress(fraction)
                        override fun finished(code: Int, logTail: String) {
                            done.complete(code to logTail)
                        }
                    },
                )
                try {
                    done.await()
                } catch (e: CancellationException) {
                    engine.cancelFfmpeg()
                    withContext(NonCancellable) { done.await() }
                    throw e
                }
            }
        }

    private suspend fun saveToPhotos(path: String, image: Boolean): String? = suspendCancellableCoroutine { cont ->
        val listener = object : SaveListener {
            override fun finished(error: String?) {
                if (cont.isActive) cont.resume(error)
            }
        }
        if (image) engine.saveImageToPhotos(path, listener) else engine.saveVideoToPhotos(path, listener)
    }

    fun cancel() = engine.cancelFfmpeg()

    private fun exists(path: String) = fileSize(path) > 0

    companion object {
        const val KEY_ENHANCE_AUDIO = "render.enhance_audio"

        /** 6 Mbps untuk 1080×1920 (sama dengan Android); rasio lain sebanding jumlah piksel. */
        const val VIDEO_BITRATE = 6_000_000L

        /** Ukuran judul klasik di sampul (sama dengan Android). */
        const val COVER_TITLE_SIZE = 92

        /** VideoToolbox (encoder hardware iPhone): nv12 dulu, lalu yuv420p. */
        val ENCODERS = listOf(
            listOf("-c:v", "h264_videotoolbox", "-pix_fmt", "nv12"),
            listOf("-c:v", "h264_videotoolbox", "-pix_fmt", "yuv420p"),
        )
    }
}
