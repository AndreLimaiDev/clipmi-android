package id.clipmi.core.render

import id.clipmi.core.analysis.Keywords
import id.clipmi.core.php.Fmt
import id.clipmi.core.php.Php
import id.clipmi.core.transcript.Word
import id.clipmi.core.transcript.WordTimer
import kotlin.math.ceil

/** Satu baris subtitle: waktu (detik) dan teks, dengan waktu per kata bila ada. */
@kotlinx.serialization.Serializable
data class SubtitleLine(val start: Double, val end: Double, val text: String, val words: List<Word>? = null)

/** Bilah judul di atas layar: tanpa warna latar = judul saja (template). */
data class TitleBarText(val text: String, val background: String?, val color: String?, val height: Int)

/**
 * Lebar teks satu baris (piksel) sebagaimana digambar libass, untuk kotak hook bersudut membulat. Di HP diukur dengan
 * FreeType (sama dengan GD desktop); di tes diambil dari ukuran yang diekspor desktop.
 */
fun interface TextMeasurer {
    fun width(family: String, size: Int, text: String): Double
}

/** Pembungkus kata seperti `TextMeasure::wrap` desktop (padanan WrapStyle 0 libass: baris seimbang). */
object TextWrap {
    fun width(measurer: TextMeasurer, family: String, size: Int, text: String): Double =
        if (Php.trim(text).isEmpty()) 0.0 else measurer.width(family, size, text)

    fun wrap(measurer: TextMeasurer, family: String, size: Int, text: String, maxWidth: Double): List<String> {
        val lines = greedy(measurer, family, size, text, maxWidth)
        if (lines.size < 2) return lines

        // Lebar tersempit yang masih menghasilkan jumlah baris yang sama, dicari bertahap (bagi dua).
        var low = 0.0
        var high = maxWidth
        repeat(16) {
            val middle = (low + high) / 2
            if (greedy(measurer, family, size, text, middle).size <= lines.size) high = middle else low = middle
        }
        return greedy(measurer, family, size, text, high)
    }

    private fun greedy(measurer: TextMeasurer, family: String, size: Int, text: String, maxWidth: Double): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in pregSplitSpaces(Php.trim(text))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && width(measurer, family, size, candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}

/** `preg_split('/\s+/u', $text)`: potongan kosong di awal/akhir tetap ada, seperti PHP. */
internal fun pregSplitSpaces(text: String): List<String> = text.split(WordTimer.WHITESPACE)

/**
 * Menulis subtitle ASS sesuai gaya subtitle (padanan `App\Render\AssWriter` desktop, keluaran identik byte demi byte).
 * Ukuran gaya ditentukan untuk layar 9:16 selebar 1080; untuk rasio lain ukuran huruf, garis tepi, dan margin ikut
 * diskalakan.
 */
class AssWriter(private val measurer: TextMeasurer = TextMeasurer { _, _, _ -> 0.0 }) {

    /**
     * Semua waktu di [lines] (termasuk waktu per kata) relatif terhadap awal klip.
     *
     * @param centerY titik tengah vertikal subtitle dari gaya bingkai; null = posisi gaya subtitle.
     * @param canvas lebar, tinggi layar, dan skala elemen terhadap layar 9:16 selebar 1080.
     * @param hookTop jarak teks hook dari atas layar; null = di bawah bilah judul atau 300 px (layar 1920).
     * @param hookLayout tampilan hook ([HookLayout.resolve]); null = bawaan dengan ukuran [hookSize].
     */
    fun build(
        lines: List<SubtitleLine>,
        duration: Double,
        hook: String?,
        style: CaptionStyle,
        centerY: Int? = null,
        titleBar: TitleBarText? = null,
        canvas: Triple<Int, Int, Double> = Triple(WIDTH, HEIGHT, 1.0),
        hookTop: Int? = null,
        hookSize: Int = 62,
        keywords: List<String> = emptyList(),
        hookLayout: HookLayout? = null,
    ): String {
        val keys = if (style.keywordHighlight == true) keywords else emptyList()
        val (width, height, scale) = canvas
        val px = { value: Double -> Php.roundInt(value * scale) }
        val layout = hookLayout ?: HookLayout.resolve(kotlinx.serialization.json.JsonObject(mapOf("size" to kotlinx.serialization.json.JsonPrimitive(hookSize))), duration)
        val hookFont = px(layout.size.toDouble())
        val hookPadding = px(layout.size * 18 / 62.0)
        // Kotak bersudut membulat tidak bisa dibuat BorderStyle 3, jadi digambar sendiri di belakang teks.
        val hookRounded = layout.alpha != null && layout.radius > 0
        val (hookBorder, hookOutline, hookEdge) = when {
            hookRounded -> Triple(1, "&H00000000", 0)
            layout.alpha != null -> Triple(3, hex2(layout.alpha) + Php.substr(color(layout.background), 4), hookPadding)
            else -> Triple(1, color(layout.background), maxOf(2, Php.roundInt(hookFont / 12.0)))
        }
        val bold = if (Fonts.isBold(style.fontFamily)) -1 else 0
        val (alignment, marginV) = if (style.position == "middle") 5 to 0 else 2 to Php.roundInt(height * BOTTOM_MARGIN.toDouble() / HEIGHT)
        val fontSize = px((style.fontSize ?: 0).toDouble())
        val effect = style.effect ?: "outline"
        // Cahaya lembut: garis tepi lebih tebal lalu dikaburkan (\blur pada event), huruf tetap tajam.
        val outline = maxOf(2, Php.roundInt(fontSize / (if (effect == "glow") 9.0 else 15.0)))
        val (shadow, shadowColor) = when (effect) {
            "shadow" -> maxOf(2, Php.roundInt(fontSize / 14.0)) to "&H50000000"
            "glow" -> 0 to "&H64000000"
            else -> 1 to "&H64000000"
        }
        val boxAlpha = hex2(Php.roundInt(255 * (1 - clamp(style.lineBoxOpacity ?: 65, 0, 100) / 100.0)))
        val hookMargin = hookTop ?: (if (titleBar != null) titleBar.height + px(80.0) else Php.roundInt(height * 300.0 / HEIGHT))
        val family = style.fontFamily.orEmpty()

        // Warna ASS: &HAABBGGRR, AA = transparansi (00 = pekat).
        val header = listOf(
            "[Script Info]",
            "ScriptType: v4.00+",
            "PlayResX: $width",
            "PlayResY: $height",
            "WrapStyle: 0",
            "ScaledBorderAndShadow: yes",
            "",
            "[V4+ Styles]",
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
            "Style: Sub,$family,$fontSize,${color(style.primaryColor)},${color(style.primaryColor)},${color(style.outlineColor)},$shadowColor,$bold,0,0,0,100,100,0,0,1,$outline,$shadow,$alignment,${px(90.0)},${px(90.0)},$marginV,1",
            // Kotak latar baris: metrik sama dengan Sub, hurufnya tak terlihat, kotaknya (OutlineColour) semi-transparan.
            "Style: Line,$family,$fontSize,&HFF000000,&HFF000000,$boxAlpha${Php.substr(color(style.lineBoxColor ?: "#000000"), 4)},&HFF000000,$bold,0,0,0,100,100,0,0,3," +
                "${maxOf(6, Php.roundInt(fontSize * 0.22))},0,$alignment,${px(90.0)},${px(90.0)},$marginV,1",
            // Hook: bawaannya kotak hitam transparan di bagian atas; posisi yang diseret di editor memakai \pos.
            "Style: Hook,$family,$hookFont,${color(layout.color)},${color(layout.color)},$hookOutline,&H00000000,$bold,0,0,0,100,100,0,0,$hookBorder,$hookEdge,0,8,${px(110.0)},${px(110.0)},$hookMargin,1",
            // Bilah judul: teks di tengah bilah, dibungkus otomatis bila judul panjang.
            "Style: Title,$family,${px(60.0)},${color(titleBar?.color ?: "#FFFFFF")},${color(titleBar?.color ?: "#FFFFFF")},&H00000000,&H00000000,$bold,0,0,0,100,100,0,0,1,0,0,5,${px(70.0)},${px(70.0)},0,1",
            // Kotak sorot: metrik sama dengan Sub supaya kotak tepat di belakang kata; hurufnya tak terlihat.
            "Style: Box,$family,$fontSize,&HFF000000,&HFF000000,${color(style.highlightColor)},&HFF000000,$bold,0,0,0,100,100,0,0,3,${maxOf(4, Php.roundInt(fontSize * 0.1))},0,$alignment,${px(90.0)},${px(90.0)},$marginV,1",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        ).joinToString("\n")

        val events = mutableListOf<String>()
        val place = if (centerY != null) "{\\an5\\pos(${width / 2},$centerY)}" else ""

        if (titleBar != null && Php.trim(titleBar.text).isNotEmpty()) {
            val barHeight = titleBar.height
            // Persegi selebar layar digambar dengan perintah gambar ASS (\p1), lalu judul di lapisan atasnya.
            if (titleBar.background != null) {
                events += dialogue(0, 0.0, duration, "Title", "{\\an7\\pos(0,0)\\bord0\\shad0\\1c${color(titleBar.background)}&\\p1}m 0 0 l $width 0 $width $barHeight 0 $barHeight{\\p0}")
            }
            events += dialogue(1, 0.0, duration, "Title", "{\\an5\\pos(${width / 2},${barHeight / 2})}" + escape(titleBar.text))
        }

        if (hook != null && Php.trim(hook).isNotEmpty()) {
            val at = if (layout.x != null) "{\\an5\\pos(${Php.roundInt(width * layout.x / 100.0)},${Php.roundInt(height * layout.y!! / 100.0)})}" else ""
            val motion = when (layout.animation) {
                "pop" -> "{\\fad(80,250)\\fscx70\\fscy70\\t(0,200,\\fscx100\\fscy100)}"
                "none" -> ""
                else -> "{\\fad(150,250)}"
            }
            val end = minOf(layout.end, duration)

            if (end > layout.start && hookRounded) {
                // Teks dipecah sendiri (tanpa pembungkusan ASS, \q2) supaya ukuran kotaknya diketahui.
                val rows = TextWrap.wrap(measurer, family, hookFont, hook, (width - 2 * px(110.0)).toDouble())
                val boxWidth = ceil(rows.maxOf { TextWrap.width(measurer, family, hookFont, it) } + 2 * hookPadding).toInt()
                val boxHeight = rows.size * hookFont + 2 * hookPadding
                val (x, y) = if (layout.x != null) {
                    Php.roundInt(width * layout.x / 100.0) to Php.roundInt(height * layout.y!! / 100.0)
                } else {
                    width / 2 to Php.roundInt(hookMargin + rows.size * hookFont / 2.0)
                }
                val box = "{\\an5\\pos($x,$y)\\bord0\\shad0\\1c${color(layout.background)}\\1a${hex2(layout.alpha!!)}&\\p1}" +
                    roundedRect(boxWidth, boxHeight, minOf(px(layout.radius.toDouble()), boxHeight / 2)) + "{\\p0}"

                events += dialogue(1, layout.start, end, "Hook", motion + box)
                events += dialogue(2, layout.start, end, "Hook", "{\\an5\\pos($x,$y)\\q2}" + motion + rows.joinToString("\\N") { escape(it) })
            } else if (end > layout.start) {
                events += dialogue(1, layout.start, end, "Hook", at + motion + escape(hook))
            }
        }

        // Efek per event: kaburkan garis tepi untuk cahaya lembut; baris muncul pelan untuk animasi fade.
        val glow = if (effect == "glow") "{\\blur${maxOf(2, Php.roundInt(fontSize / 14.0))}}" else ""
        val fade = if ((style.animation ?: "none") == "fade") "{\\fad(180,0)}" else ""
        // Lapisan: kotak latar baris paling bawah, lalu kotak sorot kata, lalu teks.
        val lineLayer = if (style.lineBox == true) 1 else 0

        for (line in lines) {
            val words = line.words.orEmpty()
            val perWord = (style.highlightActiveWord == true || style.animation == "pop" || style.animation == "reveal") && words.isNotEmpty()
            val wordEvents = if (perWord) {
                wordEvents(line, words, style, glow, Keywords.mark(words.map { it.text }, keys))
            } else {
                listOf(WordEvent(line.start, line.end, keywordText(line.text, style, keys, glow), null))
            }

            if (style.lineBox == true) {
                // Satu kotak untuk seluruh baris, dengan teks yang sama tata letaknya dengan event kata.
                val boxLine = if (perWord) words.joinToString(wordSeparator(style)) { text(it.text, style) } else text(line.text, style)
                val start = maxOf(0.0, line.start)
                val end = minOf(duration, line.end)
                if (end > start) {
                    events += dialogue(0, start, end, "Line", place + fade + "{\\blur${maxOf(2, Php.roundInt(fontSize / 20.0))}}" + boxLine)
                }
            }

            wordEvents.forEachIndexed { i, event ->
                val start = maxOf(0.0, event.start)
                val end = minOf(duration, event.end)
                val intro = if (i == 0) fade else ""
                if (end > start) {
                    if (event.box != null) events += dialogue(lineLayer, start, end, "Box", place + intro + event.box)
                    // Dengan kotak sorot, teks harus di lapisan atas kotaknya.
                    events += dialogue(lineLayer + (if (event.box != null) 1 else 0), start, end, "Sub", place + intro + glow + event.text)
                }
            }
        }

        return header + "\n" + events.joinToString("\n") + "\n"
    }

    private data class WordEvent(val start: Double, val end: Double, val text: String, val box: String?)

    /**
     * Satu event per kata, berganti saat kata berikutnya mulai: untuk sorotan kata aktif ("color" atau "box") dan
     * animasi per kata ("pop" membesar, "reveal" menyembunyikan kata yang belum diucapkan).
     */
    private fun wordEvents(line: SubtitleLine, words: List<Word>, style: CaptionStyle, restore: String, marked: Set<Int>): List<WordEvent> {
        val highlight = style.highlightActiveWord == true
        val box = highlight && style.highlightStyle == "box"
        // Di atas kotak, kata aktif tanpa garis tepi supaya hurufnya bersih.
        val active = when {
            box -> "{\\bord0\\shad0}"
            highlight -> "{\\1c${color(style.highlightColor)}}"
            else -> ""
        }
        val pop = if (style.animation == "pop") "{\\fscx118\\fscy118\\t(0,150,\\fscx100\\fscy100)}" else ""
        val upcoming = when {
            style.animation == "reveal" -> "{\\alpha&HFF&}"
            highlight && phpTruthy(style.upcomingColor) -> "{\\1c${color(style.upcomingColor)}}"
            else -> null
        }
        val separator = wordSeparator(style)
        val reset = "{\\r}$restore"
        val keyword = keywordTag(style)

        return words.mapIndexed { i, word ->
            val text = words.withIndex().joinToString(separator) { (j, other) ->
                val key = if (j in marked) keyword else ""
                when {
                    // Warna sorotan ditulis setelah warna kata kunci, jadi sorotan menang saat kata diucapkan.
                    j == i && (active.isNotEmpty() || pop.isNotEmpty()) -> pop + key + active + text(other.text, style) + reset
                    j > i && upcoming != null -> upcoming + key + text(other.text, style) + reset
                    j in marked -> keyword + text(other.text, style) + reset
                    else -> text(other.text, style)
                }
            }
            // Kotak ikut membesar bersama kata aktif (pop), supaya tetap tepat di belakangnya.
            val boxText = if (box) {
                "{\\3a&HFF&}" + words.withIndex().joinToString(separator) { (j, other) ->
                    if (j == i) "{\\3a&H00&}" + pop + text(other.text, style) + "{\\3a&HFF&" + (if (pop.isNotEmpty()) "\\fscx100\\fscy100" else "") + "}"
                    else text(other.text, style)
                }
            } else {
                null
            }
            WordEvent(if (i == 0) line.start else word.start, words.getOrNull(i + 1)?.start ?: line.end, text, boxText)
        }
    }

    /** Baris tanpa event per kata: kata kunci di dalam baris diberi warna kata kunci. */
    private fun keywordText(text: String, style: CaptionStyle, keywords: List<String>, restore: String): String {
        val words = pregSplitSpaces(Php.trim(text))
        val marked = Keywords.mark(words, keywords)
        if (marked.isEmpty()) return text(text, style)
        return words.withIndex().joinToString(" ") { (i, word) ->
            if (i in marked) keywordTag(style) + text(word, style) + "{\\r}" + restore else text(word, style)
        }
    }

    private fun keywordTag(style: CaptionStyle) = "{\\1c${color(style.keywordColor ?: "#22C55E")}}"

    /** Mode kotak sorot: jarak antarkata dilebarkan (spasi keras) supaya kotak tidak menempel ke kata sebelahnya. */
    private fun wordSeparator(style: CaptionStyle) = if (style.highlightActiveWord == true && style.highlightStyle == "box") "\\h " else " "

    private fun dialogue(layer: Int, start: Double, end: Double, style: String, text: String) =
        "Dialogue: $layer,${time(start)},${time(end)},$style,,0,0,0,,$text"

    /** Teks subtitle siap tulis: di-escape, dan dijadikan huruf kapital jika gaya memintanya. */
    private fun text(text: String, style: CaptionStyle): String = escape(text).let { if (style.uppercase == true) Php.mbStrtoupper(it) else it }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920

        /** Lama teks hook tampil di awal klip (detik). */
        const val HOOK_SECONDS = 3.5

        /** Jarak subtitle dari bawah layar untuk posisi "bawah" pada layar setinggi 1920. */
        private const val BOTTOM_MARGIN = 460

        /** Format waktu ASS: H:MM:SS.cc (perseratus detik). */
        fun time(seconds: Double): String {
            val cs = Php.roundInt(seconds * 100)
            return "${cs / 360000}:${Fmt.pad(cs / 6000 % 60, 2)}:${Fmt.pad(cs / 100 % 60, 2)}.${Fmt.pad(cs % 100, 2)}"
        }

        /** "#RRGGBB" menjadi warna ASS "&H00BBGGRR". */
        fun color(hex: String?): String {
            val h = hex.orEmpty().trimStart('#').uppercase()
            return "&H00" + Php.substr(h, 4, 2) + Php.substr(h, 2, 2) + Php.substr(h, 0, 2)
        }

        /** Kurung kurawal dan backslash punya arti khusus di ASS, jadi diganti agar teks tampil apa adanya. */
        fun escape(text: String): String =
            Php.trim(WordTimer.WHITESPACE.replace(text, " ")).replace('\\', '/').replace('{', '(').replace('}', ')')

        /** Persegi panjang bersudut membulat sebagai perintah gambar ASS (\p1), dimulai di (0, 0). */
        internal fun roundedRect(width: Int, height: Int, radius: Int): String {
            val r = maxOf(0, radius)
            val c = Php.roundInt(r * 0.448)
            return "m $r 0 l ${width - r} 0 b ${width - c} 0 $width $c $width $r " +
                "l $width ${height - r} b $width ${height - c} ${width - c} $height ${width - r} $height " +
                "l $r $height b $c $height 0 ${height - c} 0 ${height - r}" +
                " l 0 $r b 0 $c $c 0 $r 0"
        }

        private fun hex2(value: Int) = "&H" + Fmt.hex2(value)

        /** Nilai "truthy" PHP untuk teks: bukan null, bukan "", bukan "0". */
        private fun phpTruthy(value: String?) = !value.isNullOrEmpty() && value != "0"
    }
}
