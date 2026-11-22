package id.clipmi.ios

import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.RenderGraph
import id.clipmi.core.render.SubtitleLine
import id.clipmi.core.render.TemplateArea
import id.clipmi.core.render.TemplateFrame
import id.clipmi.core.render.AssWriter
import id.clipmi.core.transcript.Word
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class StyleImageRejected(message: String) : Exception(message)

/**
 * Gambar gaya bingkai dari Photos (padanan MediaLibrary Android): logo dan template disimpan PNG (kanal transparan
 * tetap), latar JPEG, di Documents/branding (folder yang sama dengan Android, ikut cadangan). Template sekaligus dicari
 * area transparannya (TemplateFrame core, sama dengan Android dan desktop).
 */
class StyleMedia(private val repo: Repository, private val renderer: IosRenderer) {
    private val engine get() = renderer.engine

    enum class Kind(val prefix: String, val maxSide: Int, val png: Boolean) {
        Logo("frame", 1024, true),
        Background("background", 2160, false),
        Template("template", 2160, true),
    }

    private suspend fun pick(kind: Kind): String? = suspendCancellableCoroutine { cont ->
        createDirectory(repo.absolute("branding"))
        val relative = "branding/${kind.prefix}-${random()}.${if (kind.png) "png" else "jpg"}"
        engine.pickStyleImage(repo.absolute(relative), kind.png, kind.maxSide, object : PickListener {
            override fun finished(path: String?) {
                if (cont.isActive) cont.resume(path?.let { relative })
            }
        })
    }

    /**
     * Memilih gambar untuk [field] ("logo", "background", "template") dan memasangnya di [draft]; null bila batal.
     * [created] menerima path salinan, supaya yang tidak jadi disimpan bisa dihapus.
     */
    suspend fun importInto(field: String, draft: FrameStyleEntity, created: (String) -> Unit): FrameStyleEntity? {
        val kind = when (field) {
            "logo" -> Kind.Logo
            "background" -> Kind.Background
            else -> Kind.Template
        }
        val relative = pick(kind) ?: return null
        created(relative)
        return when (kind) {
            Kind.Logo -> draft.copy(logoPath = relative)
            Kind.Background -> draft.copy(backgroundPath = relative)
            Kind.Template -> {
                val analysis = analyzeTemplate(repo.absolute(relative))
                draft.copy(templatePath = relative, aspect = analysis.aspect, templateArea = Json.encodeToString(TemplateArea.serializer(), analysis.area))
            }
        }
    }

    private fun analyzeTemplate(path: String): TemplateFrame.Analysis {
        val output = repo.absolute("tmp/template-alpha.bin")
        createDirectory(repo.absolute("tmp"))
        val size = engine.alphaPixels(path, output) ?: throw StyleImageRejected("Gambar template tidak bisa dibaca.")
        val (width, height) = size.split('x').map { it.toInt() }
        val bytes = readBytes(output) ?: throw StyleImageRejected("Gambar template tidak bisa dibaca.")
        removeItem(output)
        val alpha = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        if (alpha.all { it == 255 }) {
            throw StyleImageRejected("Template ini tidak punya area transparan untuk video. Simpan desainnya sebagai PNG dengan bagian video dikosongkan (transparan).")
        }
        return try {
            TemplateFrame.analyze(alpha, width, height)
        } catch (e: TemplateFrame.Rejected) {
            throw StyleImageRejected(e.message.orEmpty())
        }
    }

    /** Salinan gambar gaya lain untuk gaya baru, supaya menghapus salah satu gaya tidak menghapus gambar yang lain. */
    fun copyImage(path: String?): String? {
        val source = path?.takeIf { fileSize(repo.absolute(it)) > 0 } ?: return null
        val name = source.substringAfterLast('/')
        val copy = source.substringBeforeLast('/') + "/" + name.substringBeforeLast('.').substringBeforeLast('-') + "-" + random() + "." + name.substringAfterLast('.', "png")
        copyItem(repo.absolute(source), repo.absolute(copy))
        return copy
    }

    fun delete(path: String?) {
        path?.let { removeItem(repo.absolute(it)) }
    }

    fun exists(path: String?) = path != null && fileSize(repo.absolute(path)) > 0

    /**
     * Pratinjau gaya bingkai: satu frame dari [sample] (JPEG/PNG) dengan filtergraph render yang sama (padanan
     * Renderer.framePreview Android). Mengembalikan path JPEG hasil.
     */
    suspend fun framePreview(frame: FrameStyle, caption: CaptionStyle, sample: String, width: Int = 540): String {
        val core = ClipRender(AssWriter(IosFontMeasurer(engine, renderer.fontsDir)))
        val spec = ClipSpec(startTime = 0.0, endTime = 3.0, title = "Judul klip contoh", style = caption, frame = frame)
        val sampleSize = if (frame.mode == "fit" && frame.framesVideo()) imageSize(sample) else null
        val composition = core.composition(spec, sampleSize)
        val words = listOf("Contoh", "subtitle", "di", "sini")
        val line = SubtitleLine(0.0, 3.0, words.joinToString(" "), words.mapIndexed { i, w -> Word(w, i * 0.4, i * 0.4 + 0.4) })
        val workDir = repo.absolute("tmp/frame-preview")
        removeItem(workDir)
        createDirectory(workDir)
        try {
            val imageArgs = renderer.prepareStill(core, spec, composition, workDir, listOf(line), 3.0)
            val (inputs, args) = imageArgs
            val filter = RenderGraph.lgpl(RenderGraph.filter(composition, inputs) + ";[v]scale=$width:-2[o]")
            val output = repo.absolute("tmp/frame-preview.jpg")
            removeItem(output)
            val (code, tail) = renderer.runFfmpeg(
                listOf("-y", "-loop", "1", "-t", "1", "-i", sample) + args +
                    listOf("-filter_complex", filter, "-map", "[o]", "-ss", "0.5", "-frames:v", "1", "-q:v", "4", output),
                workDir, 1.0,
            ) {}
            if (code != 0 || fileSize(output) <= 0) throw StyleImageRejected(tail.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160))
            return output
        } finally {
            removeItem(workDir)
        }
    }

    private fun imageSize(path: String): Pair<Int, Int>? {
        val output = repo.absolute("tmp/size-probe.bin")
        val size = engine.alphaPixels(path, output)
        removeItem(output)
        return size?.split('x')?.map { it.toInt() }?.let { it[0] to it[1] }
    }

    private fun random(length: Int = 6): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
