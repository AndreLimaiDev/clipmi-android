package id.clipmi.android.domain

import id.clipmi.core.render.CoverTitle
import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.nativebridge.AssRenderer
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.php.Php
import id.clipmi.core.php.Str
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.Composition
import id.clipmi.core.render.HookLayout
import id.clipmi.core.render.SubtitleLine
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.RenderGraph
import id.clipmi.core.render.SegmentWords
import id.clipmi.core.render.SpeakerTrack
import id.clipmi.core.render.SubtitleEdits
import id.clipmi.core.render.TemplateArea
import id.clipmi.core.render.TextMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

fun CaptionStyleEntity.toCore() = CaptionStyle(
    name, fontFamily, fontSize, uppercase, primaryColor, outlineColor, highlightActiveWord, highlightStyle, highlightColor,
    upcomingColor, keywordHighlight, keywordColor, position, wordsPerLine, animation, effect, lineBox, lineBoxColor, lineBoxOpacity,
    highlightRadius = highlightRadius, highlightHeight = highlightHeight, highlightWidth = highlightWidth, highlightY = highlightY,
)

fun FrameStyleEntity.toCore() = FrameStyle(
    name = name, aspect = aspect, mode = mode, background = background, blurStrength = blurStrength,
    backgroundColor = backgroundColor, backgroundColor2 = backgroundColor2, backgroundPath = backgroundPath,
    templatePath = templatePath,
    templateArea = templateArea?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(TemplateArea.serializer(), it) }.getOrNull() },
    videoHeight = videoHeight, videoPosition = videoPosition, zoom = zoom, cornerRadius = cornerRadius, borderWidth = borderWidth,
    borderColor = borderColor, logoPath = logoPath, logoX = logoX, logoY = logoY, logoSize = logoSize, logoOpacity = logoOpacity,
    watermarkText = watermarkText, watermarkFont = watermarkFont, watermarkSize = watermarkSize, watermarkColor = watermarkColor,
    watermarkOpacity = watermarkOpacity, watermarkX = watermarkX, watermarkY = watermarkY, titleBar = titleBar,
    titleBarColor = titleBarColor, titleTextColor = titleTextColor, subtitleY = subtitleY,
    cropX = cropX, cropY = cropY, templateFill = templateFill,
)

/**
 * Lebar teks satu baris untuk kotak hook bersudut membulat, sama dengan `TextMeasure::width` desktop: ukuran huruf ASS
 * dikali unitsPerEm/(winAscent+winDescent) seperti libass, × 0,75 poin, lalu diukur FreeType seperti GD
 * ([id.clipmi.android.nativebridge.TextMeasure]). Paint Android hanya cadangan bila font tidak terbaca.
 */
class FontMeasurer(private val fontsDir: File) : TextMeasurer {
    private val typefaces = HashMap<String, Typeface>()
    private val scales = HashMap<String, Double>()

    override fun width(family: String, size: Int, text: String): Double {
        val file = UserFonts.file(fontsDir, family)
        val scale = scales.getOrPut(file.path) { emScale(file) }
        id.clipmi.android.nativebridge.TextMeasure.width(file, size * scale * 0.75, text)?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaces.getOrPut(file.path) { Typeface.createFromFile(file) }
            textSize = (size * scales.getOrPut(file.path) { emScale(file) }).toFloat()
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return bounds.width().toDouble()
    }

    /** unitsPerEm / (usWinAscent + usWinDescent), dari tabel head dan OS/2 file TrueType. */
    private fun emScale(file: File): Double = runCatching {
        RandomAccessFile(file, "r").use { f ->
            fun u16(at: Long): Int { f.seek(at); return f.readUnsignedShort() }
            fun u32(at: Long): Long { f.seek(at); return f.readInt().toLong() and 0xFFFFFFFFL }
            val tables = HashMap<String, Long>()
            val count = u16(4)
            for (i in 0 until count) {
                val record = 12L + 16 * i
                f.seek(record)
                val tag = ByteArray(4).also(f::readFully).toString(Charsets.ISO_8859_1)
                tables[tag] = u32(record + 8)
            }
            val head = tables["head"] ?: return@use 1.0
            val os2 = tables["OS/2"] ?: return@use 1.0
            val unitsPerEm = u16(head + 18)
            val winHeight = u16(os2 + 74) + u16(os2 + 76)
            if (winHeight > 0) unitsPerEm.toDouble() / winHeight else 1.0
        }
    }.getOrDefault(1.0)
}

class RenderException(message: String) : Exception(message)

/**
 * Merender klip menjadi MP4 (padanan `ClipRenderer::render` desktop): komposisi, filtergraph, dan subtitle ASS dari
 * core (identik dengan desktop), FFmpeg dengan encoder hardware HP, lalu disimpan ke Movies/ClipMI/<project>.
 */
class Renderer(
    private val context: Context,
    private val database: AppDatabase,
    private val workspace: Workspace,
    private val transcripts: Transcripts,
    private val settings: Settings,
    private val ffmpeg: Ffmpeg,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun captionStyle(id: Long?): CaptionStyle =
        (id?.let { database.styles().captionStyle(it) } ?: database.styles().defaultCaptionStyle())?.toCore() ?: CaptionStyle.DEFAULT

    suspend fun frameStyle(id: Long?): FrameStyle =
        (id?.let { database.styles().frameStyle(it) } ?: database.styles().defaultFrameStyle())?.toCore() ?: FrameStyle.DEFAULT

    /** Intro/outro yang ikut dirender: dipilih dan filenya ada (`Clip::bumper` desktop). */
    suspend fun bumper(id: Long?): BumperEntity? = id?.let { database.bumpers().find(it) }?.takeIf { workspace.file(it.path)?.isFile == true }

    /** Data klip untuk core render, dengan gaya yang berlaku. */
    suspend fun spec(clip: ClipEntity, project: ProjectEntity): ClipSpec = ClipSpec(
        startTime = clip.startTime,
        endTime = clip.endTime,
        title = clip.title,
        partNumber = clip.partNumber,
        hook = clip.hook,
        keywords = clip.keywords?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() },
        cuts = clip.cuts?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        showHookOverlay = clip.showHookOverlay,
        hookLayout = clip.hookLayout?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        subtitleLines = clip.subtitleLines?.let { runCatching { json.decodeFromString(SubtitleEdits.serializer(), it) }.getOrNull() },
        subtitleEdited = clip.subtitleEdited,
        cropZoom = clip.cropZoom,
        cropY = clip.cropY,
        cropPosition = clip.cropPosition,
        projectCropPosition = project.cropPosition,
        style = captionStyle(clip.captionStyleId),
        frame = frameStyle(clip.frameStyleId),
        introSeconds = bumper(clip.introId)?.duration,
        outroSeconds = bumper(clip.outroId)?.duration,
        speakerKeyframes = clip.speakerKeyframes(),
    )

    suspend fun segments(projectId: Long): List<SegmentWords> = database.segments().forProject(projectId).map {
        SegmentWords(it.startTime, it.endTime, it.words?.let(transcripts::decodeWords))
    }

    fun fontsDir(): File = AssRenderer.installFonts(context)

    /** Ukuran sumber hanya diperlukan untuk video utuh yang dibingkai (`ClipRenderer::sourceSize` desktop). */
    private fun sourceSize(frame: FrameStyle, video: File): Pair<Int, Int>? = if (frame.mode == "fit" && frame.framesVideo()) MediaInfo.videoSize(video) else null

    /**
     * Isi folder kerja (`ClipRenderer::prepare` desktop): font, subtitle ASS, teks watermark, dan gambar bingkai.
     * FFmpeg dijalankan dari folder ini, jadi filter memakai nama relatif.
     *
     * @return nomor input gambar (latar, template, logo; mulai dari 1) dan argumen -i-nya.
     */
    private fun prepare(
        core: ClipRender, spec: ClipSpec, composition: Composition, workDir: File, lines: List<SubtitleLine>, length: Double, hook: String?,
        hookTop: Int? = null, hookSize: Int = 62, hookLayout: HookLayout? = null,
    ): Pair<RenderGraph.Inputs, MutableList<String>> {
        val frame = spec.frame
        File(workDir, "fonts").mkdirs()
        fontsDir().listFiles().orEmpty().forEach { it.copyTo(File(workDir, "fonts/${it.name}"), overwrite = true) }
        File(workDir, "subs.ass").writeText(core.subtitleTrack(spec, composition, lines, length, hook, hookTop, hookSize, hookLayout))
        composition.watermark?.let { File(workDir, "watermark.txt").writeText(it.text) }

        val images = linkedMapOf(
            "background" to (if (frame.background == "image" && !frame.usesTemplate()) workspace.file(frame.backgroundPath) else null),
            "template" to (if (frame.usesTemplate()) workspace.file(frame.templatePath) else null),
            "logo" to workspace.file(frame.logoPath),
        ).filterValues { it?.isFile == true }
        val args = mutableListOf<String>()
        val numbers = HashMap<String, Int>()
        images.entries.forEachIndexed { i, (name, file) ->
            numbers[name] = i + 1
            args += listOf("-i", copyImage(file!!, workDir, name))
        }
        return RenderGraph.Inputs(numbers["background"], numbers["template"], numbers["logo"]) to args
    }

    private fun copyImage(file: File, workDir: File, name: String): String {
        val copy = File(workDir, "$name.${file.extension.lowercase(Locale.ROOT)}")
        file.copyTo(copy, overwrite = true)
        return copy.name
    }

    /**
     * @param onProgress persen 0–100.
     * @return URI MediaStore file hasil.
     */
    suspend fun render(clip: ClipEntity, onProgress: (Int) -> Unit): Uri = withContext(Dispatchers.IO) {
        val project = database.projects().find(clip.projectId) ?: throw RenderException("Project tidak ditemukan.")
        // Selalu dari video sumber asli, bukan video pratinjau.
        val source = workspace.file(project.videoPath)?.takeIf { it.isFile }
            ?: throw RenderException("Video sumber tidak ditemukan. Unduh ulang atau pilih ulang file videonya.")

        val spec = spec(clip, project)
        val frame = spec.frame
        val core = ClipRender(AssWriter(FontMeasurer(fontsDir())))
        val composition = core.composition(spec, sourceSize(frame, source))
        val duration = spec.duration()
        val length = spec.outputDuration()
        val cuts = spec.cutRanges()
        val keep = if (cuts.isNotEmpty()) ClipCuts.selectExpression(ClipCuts.kept(cuts, spec.startTime, spec.endTime), spec.startTime) else null
        val enhance = settings.enhanceAudio()

        val workDir = File(context.cacheDir, "render-${clip.id}")
        workDir.deleteRecursively()
        workDir.mkdirs()
        try {
            // Pembuka (intro atau kartu pembuka): hook bisa tampil di atasnya, jadi hook ditulis di subtitle terpisah
            // yang dipasang setelah pembuka disambung.
            val leadKind = spec.leadKind()
            val hook = spec.shownHook()
            val hookAfterJoin = leadKind != null && hook != null
            val (inputs, imageArgs) = prepare(
                core, spec, composition, workDir, core.relativeLines(spec, segments(clip.projectId)), length,
                if (hookAfterJoin) null else hook, hookLayout = spec.hookLayout(),
            )

            // Klip (bingkai dan subtitle) berakhir di label [joined]; intro/outro dan sampul disusun sesudahnya.
            var filter = RenderGraph.filter(composition, inputs, keep).removeSuffix("[v]") + "[joined]"
            val cover = if (clip.hasCover) coverFile(clip, project) else null
            var next = imageArgs.count { it == "-i" } + 1
            var coverInput: Int? = null
            if (cover != null && clip.coverInVideo) {
                coverInput = next++
                imageArgs += listOf("-i", copyImage(cover, workDir, "cover"))
            }

            // Intro/outro dari pustaka, atau kartu pembuka: disambung dengan klip; suaranya ikut dirangkai di filtergraph.
            val bumpers = listOfNotNull(bumper(clip.introId)?.let { "intro" to it }, bumper(clip.outroId)?.let { "outro" to it })
            var total = length
            var audio: String? = null
            var joined = "joined"

            if (bumpers.isNotEmpty() || leadKind != null) {
                val fps = Php.numberFormat(minOf(60.0, MediaInfo.frameRate(source) ?: 30.0), 3)
                val parts = LinkedHashMap<String, RenderGraph.BumperPart>()
                for ((kind, bumper) in bumpers) {
                    val file = workspace.file(bumper.path)!!
                    val part = RenderGraph.BumperPart(next, MediaInfo.hasAudio(file), MediaInfo.durationSeconds(file) ?: bumper.duration)
                    parts[kind] = part
                    total += part.duration
                    imageArgs += listOf("-i", file.absolutePath)
                    next++
                    database.bumpers().touchUsed(bumper.id)
                }
                // Kartu pembuka: frame pertama klip dari video sumber (input tersendiri, hanya sekejap dibaca), dibuat
                // sepanjang pembuka + transisi.
                if (leadKind == "opener") {
                    val lead = spec.leadSeconds()
                    parts["intro"] = RenderGraph.BumperPart(
                        next, false, lead,
                        RenderGraph.opener(next, frame, spec.speakerKeyframes?.let { SpeakerTrack.positionAt(it, 0.0) }, spec.effectiveCropPosition(), fps, lead + RenderGraph.OPENER_FADE),
                        RenderGraph.OPENER_FADE,
                    )
                    total += lead
                    imageArgs += listOf("-ss", RenderGraph.seconds(spec.startTime), "-t", "0.5", "-i", source.absolutePath)
                    next++
                }
                filter = RenderGraph.joinBumpers(filter, frame, parts, fps, keep, MediaInfo.hasAudio(source), length, enhance)
                audio = "[a]"

                // Hook di waktu video hasil: sejak awal isi klip, digeser sepanjang pembuka yang benar-benar disambung.
                if (hookAfterJoin) {
                    File(workDir, "hook.ass").writeText(core.hookTrack(spec, composition, hook!!, total, parts["intro"]?.duration ?: 0.0))
                    filter += ";[joined]ass=hook.ass:fontsdir=fonts[hooked]"
                    joined = "hooked"
                }
            }

            // Sampul di awal video: menutupi 0,1 detik pertama (3 frame), supaya platform yang memakai frame pertama
            // sebagai cover menampilkannya; tanpa menggeser suara. Dengan intro, sampul menutupi awal intro.
            filter += if (coverInput != null) {
                ";[$coverInput:v]scale=${frame.canvasWidth()}:${frame.canvasHeight()},setsar=1[cover];[$joined][cover]overlay=0:0:enable='lt(t,0.1)'[v]"
            } else {
                ";[$joined]null[v]"
            }
            filter = RenderGraph.lgpl(filter)

            val output = File(workDir, "output.mp4")
            val canvas = composition.canvas
            val bitrate = maxOf(3_000_000L, VIDEO_BITRATE * canvas.width * canvas.height / (1080L * 1920))
            var lastError: Exception? = null
            for (encoder in ENCODERS) {
                output.delete()
                try {
                    ffmpeg.run(
                        listOf("-y", "-ss", RenderGraph.seconds(spec.startTime), "-t", RenderGraph.seconds(duration), "-i", source.absolutePath) +
                            imageArgs +
                            listOf("-t", RenderGraph.seconds(total), "-filter_complex", filter, "-map", "[v]", "-map", audio ?: "0:a:0?") +
                            encoder + listOf("-b:v", bitrate.toString()) +
                            (if (audio == null) RenderGraph.audioFilter(keep, enhance) else emptyList()) +
                            listOf("-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-movflags", "+faststart", output.name),
                        durationSeconds = total,
                        directory = workDir,
                    ) { onProgress(it.toInt()) }
                    if (output.length() > 0) {
                        lastError = null
                        break
                    }
                } catch (e: FfmpegException) {
                    // Encoder HP menolak format ini (configure gagal): coba urutan berikutnya.
                    lastError = e
                }
            }
            lastError?.let { throw it }
            if (!output.isFile || output.length() == 0L) throw RenderException("FFmpeg tidak menghasilkan video.")

            val uri = publish(output, clip, project)
            // Sampul ikut disimpan (Pictures/ClipMI/<project>), siap dipakai sebagai cover saat mengunggah.
            publishCover(cover, clip, project)
            uri
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Pratinjau gaya bingkai: satu frame dari [sample] (gambar seperti frame video) dengan filtergraph render yang sama
     * (latar, area video, template, logo, watermark, bilah judul) dan subtitle contoh, diperkecil ke lebar [width].
     */
    suspend fun framePreview(frame: FrameStyle, caption: CaptionStyle, sample: File, output: File, width: Int = 540) = withContext(Dispatchers.IO) {
        val core = ClipRender(AssWriter(FontMeasurer(fontsDir())))
        val spec = ClipSpec(startTime = 0.0, endTime = 3.0, title = "Judul klip contoh", style = caption, frame = frame)
        val composition = core.composition(spec, if (frame.mode == "fit" && frame.framesVideo()) MediaInfo.imageSize(sample) else null)
        val words = listOf("Contoh", "subtitle", "di", "sini")
        val line = SubtitleLine(0.0, 3.0, words.joinToString(" "), words.mapIndexed { i, w -> id.clipmi.core.transcript.Word(w, i * 0.4, i * 0.4 + 0.4) })
        val workDir = File(context.cacheDir, "frame-preview-${System.nanoTime()}").apply { mkdirs() }
        try {
            val (inputs, imageArgs) = prepare(core, spec, composition, workDir, listOf(line), 3.0, null)
            val filter = RenderGraph.lgpl(RenderGraph.filter(composition, inputs) + ";[v]scale=$width:-2[o]")
            output.delete()
            ffmpeg.run(
                listOf("-y", "-loop", "1", "-t", "1", "-i", sample.absolutePath) + imageArgs +
                    listOf("-filter_complex", filter, "-map", "[o]", "-ss", "0.5", "-frames:v", "1", "-q:v", "4", output.absolutePath),
                directory = workDir,
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Gambar sampul untuk render; kegagalan sampul tidak menggagalkan render videonya. */
    private suspend fun coverFile(clip: ClipEntity, project: ProjectEntity): File? = try {
        ensureCover(clip, project)
    } catch (e: Exception) {
        android.util.Log.w("ClipMiRender", "Sampul klip ${clip.id} gagal dibuat", e)
        null
    }

    /**
     * Sampul klip (padanan `ClipCover` desktop), dibuat saat pertama diminta. Nama file memuat kunci dari pengaturan
     * sampul, judul, gaya, dan crop, jadi dibuat ulang begitu salah satunya berubah.
     */
    suspend fun ensureCover(clip: ClipEntity, project: ProjectEntity): File = withContext(Dispatchers.IO) {
        val spec = spec(clip, project)
        val dir = File(workspace.projectDir(project.id), "covers").apply { mkdirs() }
        val key = coverKey(clip, project, spec)
        val file = File(dir, "clip-${clip.id}-$key.jpg")
        if (!file.isFile) {
            dir.listFiles { f -> f.name.startsWith("clip-${clip.id}-") }?.forEach { it.delete() }
            renderCover(clip, project, spec, file)
        }
        file
    }

    /**
     * Mengunduh sampul klip ke galeri (Pictures/ClipMI/<judul project>/, tempat yang sama dengan sampul saat render).
     * @return folder tujuannya.
     */
    suspend fun downloadCover(clip: ClipEntity, project: ProjectEntity): String = withContext(Dispatchers.IO) {
        if (!publishCover(ensureCover(clip, project), clip, project)) throw RenderException("Sampul tidak bisa disimpan ke galeri.")
        "Pictures/ClipMI/" + safeName(project.title, 80)
    }

    private fun coverKey(clip: ClipEntity, project: ProjectEntity, spec: ClipSpec): String {
        val parts = listOf(
            // Versi cara membuat sampul: v2 = sampul dari frame hanya berisi video (tanpa bingkai, logo, watermark).
            "v2",
            clip.coverTime, clip.coverPath, clip.coverTitle, clip.coverLayout, clip.keywords, spec.displayTitle(), spec.frame, spec.style,
            spec.speakerKeyframes, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), clip.startTime, project.videoPath,
        )
        val digest = java.security.MessageDigest.getInstance("MD5").digest(parts.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * Satu frame video saja (crop, zoom, posisi klip; tanpa bingkai, logo, watermark, dan subtitle), atau gambar dari
     * galeri yang dipotong seukuran layar; judul klip bisa ditulis besar di atasnya (`ClipRenderer::cover` desktop,
     * kecuali bingkai yang tidak ikut).
     */
    private suspend fun renderCover(clip: ClipEntity, project: ProjectEntity, spec: ClipSpec, output: File) {
        val frame = spec.frame
        val image = workspace.file(clip.coverPath)?.takeIf { it.isFile }
        val video = listOf(project.videoPath, project.previewPath).firstNotNullOfOrNull { path -> workspace.file(path)?.takeIf { it.isFile } }
        if (image == null && video == null) throw RenderException("Sampul butuh video klip atau gambar sampul.")

        val width = frame.canvasWidth()
        val height = frame.canvasHeight()
        // Model judul Android (bukan klasik): judul tidak lewat jalur desktop, tetapi digambar di atas frame jadi.
        val layout = CoverTitle.parse(clip.coverLayout)
        val styled = clip.coverTitle && !layout.classic
        val title = if (clip.coverTitle && !styled) spec.displayTitle() else null
        // Judul besar di sekitar tiga perlima tinggi layar, di atas area subtitle.
        val titleTop = layout.y?.let { Php.roundInt(height * it.coerceIn(0, 100) / 100.0) } ?: Php.roundInt(height * 0.58)
        val core = ClipRender(AssWriter(FontMeasurer(fontsDir())))
        val workDir = File(context.cacheDir, "cover-${clip.id}-${System.nanoTime()}").apply { mkdirs() }
        try {
            val (args, filter) = if (image != null) {
                File(workDir, "fonts").mkdirs()
                fontsDir().listFiles().orEmpty().forEach { it.copyTo(File(workDir, "fonts/${it.name}"), overwrite = true) }
                File(workDir, "subs.ass").writeText(
                    AssWriter(FontMeasurer(fontsDir())).build(emptyList(), 2.0, title, spec.style, null, null, Triple(width, height, frame.uiScale()), titleTop, COVER_TITLE_SIZE),
                )
                listOf("-loop", "1", "-t", "1", "-i", copyImage(image, workDir, "upload")) to
                    "[0:v]scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,setsar=1,ass=subs.ass:fontsdir=fonts[v]"
            } else {
                val time = clip.coverTime ?: clip.startTime
                // Posisi pembicara pada detik sampul, sebagai crop tetap.
                val speaker = spec.speakerKeyframes?.let { listOf(listOf(0.0, SpeakerTrack.positionAt(it, time - clip.startTime))) }
                // Hanya videonya, memenuhi layar dengan crop, zoom, dan posisi klip: tanpa latar, template, bingkai,
                // logo, watermark, dan bilah judul gaya bingkai (berbeda dari ClipMI desktop, atas permintaan tim).
                val plain = FrameStyle(aspect = frame.aspect, mode = "crop", zoom = frame.zoom)
                val composition = Composition.make(plain, null, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), speaker)
                val (inputs, imageArgs) = prepare(core, spec.copy(frame = plain), composition, workDir, emptyList(), 2.0, title, titleTop, COVER_TITLE_SIZE)
                (listOf("-ss", RenderGraph.seconds(maxOf(0.0, time - 0.4)), "-t", "1.5", "-i", video!!.absolutePath) + imageArgs) to RenderGraph.filter(composition, inputs)
            }
            var graph = filter
            if (styled) {
                File(workDir, "fonts").takeIf { !it.isDirectory }?.let { dir ->
                    dir.mkdirs()
                    fontsDir().listFiles().orEmpty().forEach { it.copyTo(File(dir, it.name), overwrite = true) }
                }
                val keywords = clip.keywords?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
                File(workDir, "cover.ass").writeText(CoverTitle.build(layout, spec.displayTitle(), keywords, width, height, FontMeasurer(fontsDir())))
                // Judul di atas frame jadi (setelah bingkai bila ada).
                graph = graph.removeSuffix("[v]") + "[vt];[vt]ass=cover.ass:fontsdir=fonts[v]"
            }
            // Frame di detik 0,4: judul sudah selesai muncul pelan.
            ffmpeg.run(
                listOf("-y") + args + listOf("-filter_complex", RenderGraph.lgpl(graph), "-map", "[v]", "-ss", "0.4", "-frames:v", "1", "-q:v", "3", output.absolutePath),
                directory = workDir,
            )
            if (!output.isFile || output.length() == 0L) throw RenderException("Sampul tidak berhasil dibuat.")
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Sampul di samping hasil: Pictures/ClipMI/<judul project>/<nomor> - <judul>.jpg (galeri tidak menerima gambar di Movies). */
    private fun publishCover(cover: File?, clip: ClipEntity, project: ProjectEntity): Boolean {
        val resolver = context.contentResolver
        val name = String.format(Locale.ROOT, "%03d - %s.jpg", clip.id, safeName(ClipText.displayTitle(clip.title, clip.partNumber), 100))
        val folder = "Pictures/ClipMI/" + safeName(project.title, 80) + "/"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Sampul lama klip ini (nama bisa berubah bila judul diganti) diganti.
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf(folder, String.format(Locale.ROOT, "%03d - %%", clip.id)),
            )
        }
        if (cover == null) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> cover.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }

    /** Menyimpan hasil ke Movies/ClipMI/<judul project>/<nomor> - <judul klip>.mp4 (galeri HP); hasil lama diganti. */
    private fun publish(file: File, clip: ClipEntity, project: ProjectEntity): Uri {
        val resolver = context.contentResolver
        clip.outputPath?.let { old -> runCatching { resolver.delete(Uri.parse(old), null, null) } }

        val name = String.format(Locale.ROOT, "%03d - %s.mp4", clip.id, safeName(ClipText.displayTitle(clip.title, clip.partNumber), 100))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipMI/" + safeName(project.title, 80))
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw RenderException("Tidak bisa menyimpan video ke galeri.")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw RenderException("Gagal menyimpan video ke galeri: ${e.message}")
        }
        return uri
    }

    companion object {
        /** 6 Mbps untuk 1080×1920 (diuji di Fase 0); rasio lain sebanding jumlah piksel. */
        const val VIDEO_BITRATE = 6_000_000L

        /** Ukuran judul besar di sampul. */
        const val COVER_TITLE_SIZE = 92

        /**
         * Urutan encoder (padanan runWithEncoderFallback desktop): MediaCodec mode NDK dengan nv12 (Qualcomm menolak
         * yuv420p), lalu yuv420p, lalu MediaCodec lewat JNI.
         */
        val ENCODERS = listOf(
            listOf("-c:v", "h264_mediacodec", "-ndk_codec", "1", "-pix_fmt", "nv12"),
            listOf("-c:v", "h264_mediacodec", "-ndk_codec", "1", "-pix_fmt", "yuv420p"),
            listOf("-c:v", "h264_mediacodec", "-ndk_codec", "0", "-pix_fmt", "nv12"),
        )

        /** `\s` PCRE2 dengan /u (lihat WordTimer di core). */
        private val SPACES = Regex("""[\t\n\x0B\f\r\p{Z}]+""")
        private val FORBIDDEN = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]+")

        /** Nama file aman (padanan `OutputFolder::safeName` desktop). */
        fun safeName(name: String, limit: Int): String {
            val cleaned = Php.trim(FORBIDDEN.replace(name, " "))
            val limited = Str.limit(SPACES.replace(cleaned, " "), limit, "").trimEnd(' ', '.')
            return limited.ifEmpty { "Tanpa Judul" }
        }
    }
}
