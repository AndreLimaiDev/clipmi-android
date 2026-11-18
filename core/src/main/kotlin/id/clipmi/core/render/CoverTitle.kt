package id.clipmi.core.render

import id.clipmi.core.analysis.Keywords
import id.clipmi.core.php.Php
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Pengaturan judul sampul khusus Android (kolom `cover_layout`, JSON). Model "classic" = judul sampul desktop
 * (`ClipRenderer::cover`: gaya subtitle di 58% tinggi layar); model lain digambar oleh [CoverTitle].
 *
 * [text] = teks sampul (Enter = baris baru, `*kata*` = kata sorotan); null = judul klip. [accent] = warna sorotan
 * "#RRGGBB" (null = warna bawaan model). [position] = "top" | "center" | "bottom" (null = bawaan model). [size] = skala
 * ukuran huruf dalam persen.
 */
@Serializable
data class CoverTitleLayout(
    val model: String = CoverTitle.CLASSIC,
    val text: String? = null,
    val accent: String? = null,
    val position: String? = null,
    val size: Int = 100,
) {
    val classic: Boolean get() = model !in CoverTitle.MODELS || model == CoverTitle.CLASSIC
}

/** Teks besar di sampul dengan beberapa model tipografi (editorial, kapital, pop, sorot kotak, tulisan tangan). */
object CoverTitle {
    const val CLASSIC = "classic"

    /** Model → label untuk pilihan di editor. */
    val MODELS: Map<String, String> = linkedMapOf(
        CLASSIC to "Klasik",
        "editorial" to "Editorial",
        "kapital" to "Kapital tebal",
        "pop" to "Pop",
        "sorot" to "Sorot kotak",
        "tangan" to "Tulisan tangan",
    )

    /** Warna sorotan bawaan tiap model. */
    val ACCENTS: Map<String, String> = mapOf(
        "editorial" to "#FFFFFF",
        "kapital" to "#FFFFFF",
        "pop" to "#FFE14D",
        "sorot" to "#E8F77A",
        "tangan" to "#FFE14D",
    )

    /** Pilihan warna sorotan di editor. */
    val PALETTE = listOf("#FFFFFF", "#FFE14D", "#E8F77A", "#34D399", "#F97316", "#F472B6", "#60A5FA")

    const val SIZE_MIN = 70
    const val SIZE_MAX = 130

    private const val SANS = "Archivo Black"
    private const val SERIF = "Instrument Serif"
    private const val CONDENSED_LIGHT = "Bebas Neue"
    private const val CONDENSED_HEAVY = "Anton"
    private const val SMALL = "Montserrat ExtraBold"
    private const val HAND = "Permanent Marker"

    /** Cahaya gelap lembut di sekitar huruf supaya teks putih tetap terbaca di latar terang. */
    private const val GLOW = "\\bord6\\3c&H000000&\\3a&HA0&\\shad0\\blur10"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(value: String?): CoverTitleLayout =
        value?.let { runCatching { json.decodeFromString(CoverTitleLayout.serializer(), it) }.getOrNull() } ?: CoverTitleLayout()

    fun encode(layout: CoverTitleLayout): String? = if (layout == CoverTitleLayout()) null else json.encodeToString(CoverTitleLayout.serializer(), layout)

    /** Satu kata dan apakah kata itu sorotan. */
    data class Token(val text: String, val accent: Boolean)

    /**
     * Baris dan kata dari teks sampul: `*kata*` (boleh beberapa kata) jadi sorotan. Tanpa tanda bintang, kata kunci
     * klip yang muncul di teks jadi sorotan. Teks tanpa baris baru dipecah otomatis menjadi baris pendek.
     */
    fun tokens(text: String, keywords: List<String>, wordsPerLine: Int): List<List<Token>> {
        val starred = text.contains('*')
        val rawLines = text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var inside = false
        val lines = rawLines.map { line ->
            line.split(Regex("[ \t]+")).filter { it.isNotEmpty() }.mapNotNull { raw ->
                var word = raw
                var accent = inside
                if (word.startsWith('*')) { accent = true; inside = true; word = word.trimStart('*') }
                if (word.endsWith('*')) { word = word.trimEnd('*'); inside = false }
                word.takeIf { it.isNotEmpty() }?.let { Token(it, accent) }
            }
        }.filter { it.isNotEmpty() }
        val marked = if (starred) lines else {
            val flat = lines.flatten()
            val hits = Keywords.mark(flat.map { it.text }, keywords)
            var i = 0
            lines.map { line -> line.map { t -> t.copy(accent = (i++) in hits) } }
        }
        // Satu baris panjang tanpa Enter: dipecah seimbang.
        return if (rawLines.size == 1 && marked.firstOrNull().orEmpty().size > wordsPerLine) {
            val words = marked.first()
            val count = (words.size + wordsPerLine - 1) / wordsPerLine
            val per = (words.size + count - 1) / count
            words.chunked(per)
        } else {
            marked
        }
    }

    /**
     * Dokumen ASS berisi judul sampul untuk layar [width]×[height]. Diletakkan di atas frame sampul (setelah bingkai)
     * dengan filter ass FFmpeg; font dicari di folder font aplikasi.
     */
    fun build(layout: CoverTitleLayout, title: String, keywords: List<String>, width: Int, height: Int, measurer: TextMeasurer): String {
        val text = layout.text?.takeIf { it.isNotBlank() } ?: title
        val scale = layout.size.coerceIn(SIZE_MIN, SIZE_MAX) / 100.0
        val accent = AssWriter.color(layout.accent ?: ACCENTS[layout.model] ?: "#FFFFFF")
        val white = AssWriter.color("#FFFFFF")
        val events = mutableListOf<String>()
        val margin = width * 0.07

        fun event(layer: Int, body: String) {
            events += "Dialogue: $layer,0:00:00.00,0:00:10.00,T,,0,0,0,,$body"
        }

        /** Latar gelap bertahap dari [from] ke [to] (tinggi 0–1), [strength] = opasitas maksimum 0–1. */
        fun shade(from: Double, to: Double, strength: Double) {
            val bands = 48
            for (b in 0 until bands) {
                val y0 = Php.roundInt(height * (from + (to - from) * b / bands))
                val y1 = Php.roundInt(height * (from + (to - from) * (b + 1) / bands))
                val t = (b + 0.5) / bands
                val opacity = strength * (if (to > from) t else 1 - t)
                val alpha = String.format(Locale.ROOT, "%02X", (255 * (1 - opacity)).toInt().coerceIn(0, 255))
                event(0, "{\\an7\\pos(0,$y0)\\p1\\bord0\\shad0\\1c&H000000&\\1a&H$alpha&}m 0 0 l $width 0 $width ${y1 - y0} 0 ${y1 - y0}{\\p0}")
            }
        }

        fun esc(word: String) = AssWriter.escape(word)

        fun anchorY(default: String): Double = when (layout.position ?: default) {
            "top" -> 0.22
            "bottom" -> 0.76
            else -> 0.5
        }

        when (layout.model) {
            "editorial" -> {
                // Huruf tebal rapat; kata sorotan miring serif. Tiap baris disamakan lebarnya dengan lebar layar.
                val lines = tokens(text, keywords, 2)
                shade(0.2, 1.0, 0.5)
                val sizes = lines.map { line -> fit(line, width - 2 * margin, measurer, 260 * scale, 90.0, { if (it.accent) SERIF else SANS }) }
                val leading = 0.9
                val total = sizes.sum() * leading
                var y = height * anchorY("center") - total / 2
                lines.forEachIndexed { i, line ->
                    val size = sizes[i]
                    y += size * leading
                    val body = line.joinToString(" ") { t ->
                        if (t.accent) "{\\fn$SERIF\\i1\\1c$accent}${esc(t.text)}{\\fn$SANS\\i0\\1c$white}" else esc(t.text)
                    }
                    event(1, "{\\an2\\pos(${width / 2},${Php.roundInt(y)})\\fn$SANS\\fs${Php.roundInt(size)}\\1c$white\\fsp-2$GLOW}$body")
                }
            }
            "kapital" -> {
                // Kapital padat: kata biasa tipis (Bebas Neue), kata sorotan tebal (Anton); bayangan lembut.
                val lines = tokens(text.uppercase(Locale.ROOT), keywords.map { it.uppercase(Locale.ROOT) }, 2)
                val sizes = lines.map { line -> fit(line, width - 2 * margin, measurer, 240 * scale, 80.0, { if (it.accent) CONDENSED_HEAVY else CONDENSED_LIGHT }) }
                val leading = 0.95
                val total = sizes.sum() * leading
                shade(0.35, 1.0, 0.55)
                var y = height * anchorY("bottom") - total / 2
                lines.forEachIndexed { i, line ->
                    y += sizes[i] * leading
                    val body = line.joinToString(" ") { t ->
                        if (t.accent) "{\\fn$CONDENSED_HEAVY\\1c$accent}${esc(t.text)}{\\fn$CONDENSED_LIGHT\\1c$white}" else esc(t.text)
                    }
                    event(1, "{\\an2\\pos(${width / 2},${Php.roundInt(y)})\\fn$CONDENSED_LIGHT\\fs${Php.roundInt(sizes[i])}\\1c$white$GLOW}$body")
                }
            }
            "pop" -> {
                // Baris kecil putih dan baris sorotan sangat besar berwarna, rata kiri.
                var lines = tokens(text.uppercase(Locale.ROOT), keywords.map { it.uppercase(Locale.ROOT) }, 3)
                if (lines.none { line -> line.any { it.accent } }) {
                    // Tanpa sorotan: baris terpanjang jadi baris besar.
                    val longest = lines.indices.maxByOrNull { i -> lines[i].sumOf { it.text.length } } ?: 0
                    lines = lines.mapIndexed { i, line -> if (i == longest) line.map { it.copy(accent = true) } else line }
                }
                val big = lines.map { line -> line.any { it.accent } }
                val sizes = lines.mapIndexed { i, line ->
                    if (big[i]) fit(line, width - 2 * margin, measurer, 230 * scale, 90.0, { CONDENSED_HEAVY })
                    else min(64 * scale, fit(line, width - 2 * margin, measurer, 64 * scale, 36.0, { SMALL }))
                }
                val leading = 1.0
                val total = sizes.sum() * leading
                shade(0.45, 1.0, 0.7)
                var y = height * anchorY("bottom") - total / 2
                lines.forEachIndexed { i, line ->
                    y += sizes[i] * leading
                    val font = if (big[i]) CONDENSED_HEAVY else SMALL
                    val color = if (big[i]) accent else white
                    val body = line.joinToString(" ") { esc(it.text) }
                    event(1, "{\\an1\\pos(${Php.roundInt(margin)},${Php.roundInt(y)})\\fn$font\\fs${Php.roundInt(sizes[i])}\\1c$color$GLOW}$body")
                }
            }
            "sorot" -> {
                // Tiap baris di atas kotak warna (BorderStyle 3), teks gelap; kata sorotan kotak putih.
                val lines = tokens(text, keywords, 3)
                val size = 84 * scale
                val leading = 1.32
                val total = lines.size * size * leading
                var y = height * anchorY("center") - total / 2
                val dark = AssWriter.color("#111111")
                for (line in lines) {
                    y += size * leading
                    val body = line.joinToString(" ") { t ->
                        if (t.accent) "{\\3c$white}${esc(t.text)}{\\3c$accent}" else esc(t.text)
                    }
                    event(1, "{\\an2\\pos(${width / 2},${Php.roundInt(y)})\\fn$SANS\\fs${Php.roundInt(size)}\\1c$dark\\3c$accent\\bord${Php.roundInt(size * 0.16)}\\shad0}$body")
                }
            }
            "tangan" -> {
                // Huruf kuas sedikit miring di atas latar yang digelapkan.
                val lines = tokens(text, keywords, 2)
                val sizes = lines.map { line -> fit(line, width - 2 * margin, measurer, 200 * scale, 80.0, { HAND }) }
                val leading = 1.0
                val total = sizes.sum() * leading
                shade(0.0, 1.0, 0.35)
                var y = height * anchorY("center") - total / 2
                lines.forEachIndexed { i, line ->
                    y += sizes[i] * leading
                    val body = line.joinToString(" ") { t -> if (t.accent) "{\\1c$accent}${esc(t.text)}{\\1c$white}" else esc(t.text) }
                    event(1, "{\\an2\\pos(${width / 2},${Php.roundInt(y)})\\frz4\\fn$HAND\\fs${Php.roundInt(sizes[i])}\\1c$white$GLOW}$body")
                }
            }
        }

        return buildString {
            append("[Script Info]\nScriptType: v4.00+\nPlayResX: $width\nPlayResY: $height\nWrapStyle: 2\nScaledBorderAndShadow: yes\n\n")
            append("[V4+ Styles]\n")
            append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
            // BorderStyle 3: garis tepi menjadi kotak di belakang tiap baris (dipakai model sorot kotak).
            val border = if (layout.model == "sorot") 3 else 1
            append("Style: T,$SANS,80,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,$border,0,0,2,0,0,0,1\n\n")
            append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            events.forEach { append(it).append('\n') }
        }
    }

    /** Ukuran huruf supaya satu baris selebar [target] (dibatasi [max] dan [min]). */
    private fun fit(line: List<Token>, target: Double, measurer: TextMeasurer, hi: Double, lo: Double, font: (Token) -> String): Double {
        val probe = 100
        val space = measurer.width(font(line.first()), probe, "a a") - measurer.width(font(line.first()), probe, "aa")
        val width = line.sumOf { measurer.width(font(it), probe, it.text) } + space * (line.size - 1)
        if (width <= 0) return hi
        return min(hi, max(lo, probe * target / width))
    }
}
