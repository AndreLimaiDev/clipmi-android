package id.clipmi.core.render

import id.clipmi.core.analysis.ClipText
import id.clipmi.core.php.Php
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Data klip yang menentukan tampilan video hasil (padanan kolom dan metode `Clip` desktop yang dipakai render):
 * rentang, potongan, subtitle, hook, crop, dan gaya yang berlaku (gaya klip atau gaya bawaan).
 */
data class ClipSpec(
    val startTime: Double,
    val endTime: Double,
    val title: String,
    val partNumber: Int? = null,
    val hook: String? = null,
    val keywords: List<String>? = null,
    /** `{"words": [[a, b], …], "pauses": […], "hidden": […]}`. */
    val cuts: JsonObject? = null,
    val showHookOverlay: Boolean = true,
    val hookLayout: JsonObject? = null,
    val subtitleLines: SubtitleEdits? = null,
    val subtitleEdited: Boolean = false,
    val cropZoom: Int? = null,
    val cropY: Int? = null,
    val cropPosition: Int? = null,
    val projectCropPosition: Int = 50,
    val style: CaptionStyle = CaptionStyle.DEFAULT,
    val frame: FrameStyle = FrameStyle.DEFAULT,
    /** Panjang intro dari pustaka yang ikut dirender (null = tanpa intro atau filenya hilang). */
    val introSeconds: Double? = null,
    /** Panjang outro dari pustaka yang ikut dirender. */
    val outroSeconds: Double? = null,
    /** Jalur "ikuti penceramah" yang berlaku (klip mengikuti penceramah dan jalurnya cocok dengan rentang klip), atau null. */
    val speakerKeyframes: List<List<Double>>? = null,
) {
    /** Panjang rentang klip di video sumber, termasuk bagian yang dibuang. */
    fun duration(): Double = Php.round(endTime - startTime, 3)

    private fun cutList(vararg kinds: String): List<Range> = kinds.flatMap { ClipCuts.parse(cuts?.get(it) as JsonElement?) }

    /** Semua bagian yang dibuang (kata dan jeda) dalam rentang klip. */
    fun cutRanges(): List<Range> = ClipCuts.normalize(cutList("words", "pauses"), startTime, endTime)

    /** Kata yang tidak tampil di subtitle: dibuang dari video, atau hanya disembunyikan. */
    fun wordsOffSubtitle(): List<Range> = ClipCuts.normalize(cutList("words", "hidden"), startTime, endTime)

    /** Panjang video hasil: rentang klip dikurangi bagian yang dibuang. */
    fun outputDuration(): Double = Php.round(duration() - ClipCuts.removed(cutRanges()), 3)

    /** Teks hook yang tampil di video, atau null bila kosong atau dimatikan. */
    fun shownHook(): String? = if (showHookOverlay && Php.trim(hook.orEmpty()).isNotEmpty()) hook else null

    /**
     * Pembuka sebelum isi klip (`Clip::leadIn` desktop): intro dari pustaka, atau kartu pembuka hook bila klip tanpa
     * intro, hook-nya tampil, dan kartunya dinyalakan.
     */
    fun leadKind(): String? = when {
        introSeconds != null -> "intro"
        shownHook() != null && HookLayout.resolve(hookLayout, 1.0).opener -> "opener"
        else -> null
    }

    fun leadSeconds(): Double = when (leadKind()) {
        "intro" -> Php.round(introSeconds!!, 3)
        "opener" -> HookLayout.OPENER_SECONDS
        else -> 0.0
    }

    /** Panjang video hasil termasuk pembuka dan outro (`finalDuration()` desktop). */
    fun finalDuration(): Double = Php.round(outputDuration() + leadSeconds() + (outroSeconds ?: 0.0), 3)

    fun hookLayout(): HookLayout = HookLayout.resolve(hookLayout, outputDuration(), leadSeconds())

    fun effectiveCropPosition(): Int = cropPosition ?: projectCropPosition

    fun effectiveZoom(): Double = if (cropZoom != null) clamp(cropZoom, ZOOM_RANGE.first, ZOOM_RANGE.last) / 100.0 else frame.zoomFactor()

    fun effectiveCropY(): Int = clamp(cropY ?: 50, 0, 100)

    fun displayTitle(): String = ClipText.displayTitle(title, partNumber)

    companion object {
        /** Durasi minimal klip. */
        const val MIN_DURATION = 1.0

        /** Batas zoom crop manual (persen). */
        val ZOOM_RANGE = 100..250
    }
}

/** Subtitle untuk pratinjau editor: ASS yang sama dengan render; hook di lapisan sendiri (waktu sejak awal pembuka). */
data class PreviewTracks(val width: Int, val height: Int, val subs: String, val hook: String?)

/** Bagian ClipRenderer desktop yang bergantung pada klip: baris relatif, komposisi, dan subtitle render/pratinjau. */
class ClipRender(private val ass: AssWriter) {

    fun composition(clip: ClipSpec, source: Pair<Int, Int>? = null): Composition =
        Composition.make(clip.frame, source, clip.effectiveCropPosition(), clip.effectiveZoom(), clip.effectiveCropY(), clip.speakerKeyframes)

    fun lines(clip: ClipSpec, segments: List<SegmentWords>): List<SubtitleLine> =
        SubtitleBuilder.linesFor(segments, clip.startTime, clip.endTime, clip.style, clip.subtitleEdited, clip.subtitleLines)

    /**
     * Waktu baris dan per kata dipindah ke waktu video hasil: relatif terhadap awal klip, tanpa bagian yang dibuang.
     * Kata yang dibuang atau disembunyikan ikut hilang, begitu juga baris yang semua katanya hilang.
     */
    fun relativeLines(clip: ClipSpec, segments: List<SegmentWords>): List<SubtitleLine> {
        val cuts = clip.cutRanges()
        val wordCuts = clip.wordsOffSubtitle()
        val map = { time: Double -> ClipCuts.map(cuts, clip.startTime, time) }
        val result = mutableListOf<SubtitleLine>()

        for (line in lines(clip, segments)) {
            val words = line.words.orEmpty()
            val kept = words.filter { !ClipCuts.covers(wordCuts, (it.start + it.end) / 2) }

            if (if (words.isEmpty()) ClipCuts.covers(cuts, (line.start + line.end) / 2) else kept.isEmpty()) continue

            // Ujung baris mengikuti kata pertama/terakhir yang tersisa; bila kata itu masih ada, ujung asli dipakai.
            val first = kept.firstOrNull()
            val last = kept.lastOrNull()
            result += SubtitleLine(
                start = map(if (first == null || first == words[0]) line.start else first.start),
                end = map(if (last == null || last == words.last()) line.end else last.end),
                text = if (kept.isEmpty() || kept.size == words.size) line.text else kept.joinToString(" ") { it.text },
                words = kept.map { it.copy(start = map(it.start), end = map(it.end)) },
            )
        }
        return result
    }

    /** Subtitle klip sebagai ASS: baris, bilah judul gaya bingkai, dan hook bila diberikan. */
    fun subtitleTrack(
        clip: ClipSpec,
        composition: Composition,
        lines: List<SubtitleLine>,
        length: Double,
        hook: String?,
        hookTop: Int? = null,
        hookSize: Int = 62,
        hookLayout: HookLayout? = null,
    ): String {
        val canvas = composition.canvas
        return ass.build(
            lines,
            length,
            hook,
            clip.style,
            composition.subtitleY,
            composition.titleBar?.let { TitleBarText(clip.displayTitle(), it.background, it.color, it.height) },
            Triple(canvas.width, canvas.height, canvas.scale),
            hookTop ?: composition.hookTop,
            hookSize,
            clip.keywords.orEmpty(),
            hookLayout,
        )
    }

    /** Hook di subtitle tersendiri sepanjang video hasil, waktunya digeser sepanjang pembuka ([shift]). */
    fun hookTrack(clip: ClipSpec, composition: Composition, hook: String, total: Double, shift: Double): String {
        val canvas = composition.canvas
        val layout = clip.hookLayout()
        return ass.build(
            emptyList(), total, hook, clip.style, null, null,
            Triple(canvas.width, canvas.height, canvas.scale), composition.hookTop,
            hookLayout = layout.copy(start = layout.start + shift, end = layout.end + shift),
        )
    }

    /** Subtitle pratinjau editor: sama dengan render; hook terpisah supaya bisa dipilih dan diseret di pratinjau. */
    fun previewTracks(clip: ClipSpec, composition: Composition, segments: List<SegmentWords>, outroSeconds: Double = clip.outroSeconds ?: 0.0): PreviewTracks {
        val length = clip.outputDuration()
        val lead = clip.leadSeconds()
        val hook = clip.shownHook()
        val total = lead + length + outroSeconds
        return PreviewTracks(
            composition.canvas.width,
            composition.canvas.height,
            subtitleTrack(clip, composition, relativeLines(clip, segments), length, null),
            hook?.let { hookTrack(clip, composition, it, total, lead) },
        )
    }
}
