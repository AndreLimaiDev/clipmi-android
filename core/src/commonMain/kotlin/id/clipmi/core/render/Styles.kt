package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Font yang dibundel (padanan `App\Render\Fonts` desktop). Kunci = nama keluarga di dalam file TTF (dipakai libass).
 * "bold" = file-nya varian Bold dari keluarga itu, jadi gaya ASS perlu flag Bold agar file inilah yang dipilih.
 */
object Fonts {
    data class Font(val label: String, val bold: Boolean, val file: String)

    val AVAILABLE: Map<String, Font> = linkedMapOf(
        "Poppins" to Font("Poppins Bold", true, "Poppins-Bold.ttf"),
        "Anton" to Font("Anton", false, "Anton-Regular.ttf"),
        "Archivo Black" to Font("Archivo Black", false, "ArchivoBlack-Regular.ttf"),
        "Montserrat ExtraBold" to Font("Montserrat ExtraBold", false, "Montserrat-ExtraBold.ttf"),
        "Bebas Neue" to Font("Bebas Neue", false, "BebasNeue-Regular.ttf"),
        "Lilita One" to Font("Lilita One", false, "LilitaOne-Regular.ttf"),
    )

    const val DEFAULT = "Poppins"

    fun isBold(family: String?): Boolean = AVAILABLE[family]?.bold ?: false

    /** Font khusus judul sampul Android ([CoverTitle]); tidak ditawarkan sebagai font subtitle. */
    val COVER: Map<String, Font> = linkedMapOf(
        "Instrument Serif" to Font("Instrument Serif Italic", false, "InstrumentSerif-Italic.ttf"),
        "Permanent Marker" to Font("Permanent Marker", false, "PermanentMarker-Regular.ttf"),
    )

    /** File font untuk keluarga ini, atau file font bawaan. */
    fun file(family: String?): String = (AVAILABLE[family] ?: COVER[family] ?: AVAILABLE.getValue(DEFAULT)).file
}

/**
 * Bentuk kotak sorot yang digambar (ClipMI HP): sudut (px layar 1080), tinggi dan lebar (persen ukuran bawaan), posisi
 * tegak (0 = paling atas baris, 50 = tengah, 100 = paling bawah).
 */
data class HighlightBox(val radius: Int, val height: Int, val width: Int, val y: Int) {
    companion object {
        const val RADIUS_MAX = 60
        val HEIGHT_RANGE = 10..150
        val WIDTH_RANGE = 50..200
        val Y_RANGE = 0..100

        /** Posisi bila belum diatur: kotak rendah menempel di bawah huruf (stabilo), kotak tinggi di tengah. */
        fun defaultY(height: Int): Int = if (height < 100) 100 else 50
    }
}

/**
 * Gaya subtitle (padanan model `CaptionStyle` desktop; nama kolom sama). Kolom boleh null seperti model desktop yang
 * belum disimpan; [DEFAULT] = gaya bawaan desktop.
 */
@Serializable
data class CaptionStyle(
    val name: String? = null,
    @SerialName("font_family") val fontFamily: String? = null,
    @SerialName("font_size") val fontSize: Int? = null,
    val uppercase: Boolean? = null,
    @SerialName("primary_color") val primaryColor: String? = null,
    @SerialName("outline_color") val outlineColor: String? = null,
    @SerialName("highlight_active_word") val highlightActiveWord: Boolean? = null,
    @SerialName("highlight_style") val highlightStyle: String? = null,
    @SerialName("highlight_color") val highlightColor: String? = null,
    @SerialName("upcoming_color") val upcomingColor: String? = null,
    @SerialName("keyword_highlight") val keywordHighlight: Boolean? = null,
    @SerialName("keyword_color") val keywordColor: String? = null,
    val position: String? = null,
    @SerialName("words_per_line") val wordsPerLine: Int? = null,
    val animation: String? = null,
    val effect: String? = null,
    @SerialName("line_box") val lineBox: Boolean? = null,
    @SerialName("line_box_color") val lineBoxColor: String? = null,
    @SerialName("line_box_opacity") val lineBoxOpacity: Int? = null,
    /** Kotak sorot kata: sudut membulat (px layar 1080) dan tinggi (persen tinggi kotak bawaan); null = kotak lama. */
    @SerialName("highlight_radius") val highlightRadius: Int? = null,
    @SerialName("highlight_height") val highlightHeight: Int? = null,
    /** Lebar kotak (persen lebar bawaan: kata + jarak tepi) dan posisi tegak (0 = paling atas baris, 50 = tengah, 100 = paling bawah). */
    @SerialName("highlight_width") val highlightWidth: Int? = null,
    @SerialName("highlight_y") val highlightY: Int? = null,
) {
    /** Kotak sorot digambar sendiri (sudut/tinggi/lebar/posisi diatur) alih-alih kotak BorderStyle 3 bawaan desktop. */
    fun drawsHighlightBox(): Boolean =
        highlightActiveWord == true && highlightStyle == "box" &&
            (highlightRadius != null || highlightHeight != null || highlightWidth != null || highlightY != null)

    /** Bentuk kotak sorot ini, untuk dicocokkan dengan [HIGHLIGHT_BOXES]. */
    fun highlightBox(): HighlightBox? =
        if (highlightRadius == null && highlightHeight == null && highlightWidth == null && highlightY == null) null
        else HighlightBox(highlightRadius ?: 0, highlightHeight ?: 100, highlightWidth ?: 100, highlightY ?: HighlightBox.defaultY(highlightHeight ?: 100))

    /** Batas karakter per tampilan menyesuaikan ukuran font: paling banyak dua baris di layar 1080 px. */
    fun maxCharsPerLine(): Int = maxOf(12, kotlin.math.floor(2100.0 / maxOf(1, fontSize ?: 0)).toInt())

    companion object {
        val POSITIONS = linkedMapOf("bottom" to "Bawah", "middle" to "Tengah")
        val HIGHLIGHT_STYLES = linkedMapOf("color" to "Warna huruf", "box" to "Kotak di belakang kata")
        val ANIMATIONS = linkedMapOf(
            "none" to "Tanpa animasi",
            "pop" to "Kata membesar saat diucapkan",
            "reveal" to "Kata muncul satu per satu",
            "fade" to "Baris muncul pelan",
        )
        val EFFECTS = linkedMapOf("outline" to "Garis tepi", "shadow" to "Garis tepi + bayangan", "glow" to "Cahaya lembut")

        /** Preset kotak sorot (ClipMI HP). Null = kotak lama desktop. */
        val HIGHLIGHT_BOXES: Map<String, Pair<String, HighlightBox?>> = linkedMapOf(
            "kotak" to ("Kotak" to null),
            "membulat" to ("Membulat" to HighlightBox(16, 100, 100, 50)),
            "pil" to ("Pil" to HighlightBox(60, 100, 100, 50)),
            "tengah" to ("Pita tengah" to HighlightBox(8, 55, 105, 50)),
            "stabilo" to ("Stabilo" to HighlightBox(6, 45, 100, 100)),
            "garis" to ("Garis bawah" to HighlightBox(4, 18, 100, 100)),
        )

        /** Gaya bawaan desktop (`CaptionStyle::resolveDefault` tanpa gaya tersimpan). */
        val DEFAULT = CaptionStyle(
            name = "Bawaan",
            fontFamily = Fonts.DEFAULT,
            fontSize = 76,
            uppercase = false,
            primaryColor = "#FFFFFF",
            outlineColor = "#000000",
            highlightActiveWord = false,
            highlightStyle = "color",
            highlightColor = "#FFD400",
            upcomingColor = null,
            keywordHighlight = true,
            keywordColor = "#22C55E",
            position = "bottom",
            wordsPerLine = 4,
            animation = "none",
            effect = "outline",
            lineBox = false,
            lineBoxColor = "#000000",
            lineBoxOpacity = 65,
        )
    }
}

/** Area video transparan pada gambar template, dalam pecahan layar. */
@Serializable
data class TemplateArea(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Gaya bingkai (padanan model `FrameStyle` desktop; nama kolom dan perhitungan geometri sama persis, termasuk
 * pembulatan genap untuk encoder).
 */
@Serializable
data class FrameStyle(
    val name: String? = null,
    val aspect: String? = null,
    val mode: String? = null,
    val background: String? = null,
    @SerialName("blur_strength") val blurStrength: Int? = null,
    @SerialName("background_color") val backgroundColor: String? = null,
    @SerialName("background_color_2") val backgroundColor2: String? = null,
    @SerialName("background_path") val backgroundPath: String? = null,
    @SerialName("template_path") val templatePath: String? = null,
    @SerialName("template_area") val templateArea: TemplateArea? = null,
    @SerialName("video_height") val videoHeight: Int? = null,
    @SerialName("video_position") val videoPosition: String? = null,
    val zoom: Int? = null,
    @SerialName("corner_radius") val cornerRadius: Int? = null,
    @SerialName("border_width") val borderWidth: Int? = null,
    @SerialName("border_color") val borderColor: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("logo_x") val logoX: Int? = null,
    @SerialName("logo_y") val logoY: Int? = null,
    @SerialName("logo_size") val logoSize: Int? = null,
    @SerialName("logo_opacity") val logoOpacity: Int? = null,
    @SerialName("watermark_text") val watermarkText: String? = null,
    @SerialName("watermark_font") val watermarkFont: String? = null,
    @SerialName("watermark_size") val watermarkSize: Int? = null,
    @SerialName("watermark_color") val watermarkColor: String? = null,
    @SerialName("watermark_opacity") val watermarkOpacity: Int? = null,
    @SerialName("watermark_x") val watermarkX: Int? = null,
    @SerialName("watermark_y") val watermarkY: Int? = null,
    @SerialName("title_bar") val titleBar: Boolean? = null,
    @SerialName("title_bar_color") val titleBarColor: String? = null,
    @SerialName("title_text_color") val titleTextColor: String? = null,
    @SerialName("subtitle_y") val subtitleY: Int? = null,
    /** Posisi crop bawaan gaya ini (0–100), dipakai klip yang belum mengatur crop sendiri; null = bawaan project. */
    @SerialName("crop_x") val cropX: Int? = null,
    @SerialName("crop_y") val cropY: Int? = null,
    /** Template: "full" = video memenuhi layar di balik template; null/"area" = di area transparan saja. */
    @SerialName("template_fill") val templateFill: String? = null,
) {
    data class Aspect(val label: String, val hint: String, val width: Int, val height: Int, val scale: Double)

    private val aspectInfo: Aspect get() = ASPECTS[aspect] ?: ASPECTS.getValue("9:16")

    fun isCrop() = mode == "crop"

    /** Mode yang memotong sisi kiri-kanan video, sehingga posisi crop berlaku. */
    fun usesCropPosition() = mode == "crop" || mode == "top" || mode == "template"

    fun canvasWidth(): Int = aspectInfo.width

    fun canvasHeight(): Int = aspectInfo.height

    /** Skala teks dan elemen terhadap layar 9:16 selebar 1080. */
    fun uiScale(): Double = aspectInfo.scale

    /** Tinggi bilah judul: 300 px pada layar 9:16, sebanding pada rasio lain; template tidak memakai bilah. */
    fun titleBarHeight(): Int = if (titleBar == true && !usesTemplate()) even(canvasHeight() * 0.15625) else 0

    /** Tinggi area video mode "top", dibulatkan genap untuk encoder. */
    fun videoAreaHeight(): Int {
        val height = even(canvasHeight() * clamp(videoHeight ?: 65, 30, 85) / 100.0)
        return minOf(height, canvasHeight() - titleBarHeight())
    }

    /** Posisi atas video setinggi [height] di ruang di bawah bilah judul. */
    fun videoTop(height: Int? = null, margin: Int = 0): Int {
        val h = height ?: videoAreaHeight()
        val bar = titleBarHeight()
        return when (videoPosition) {
            "top" -> bar + margin
            "bottom" -> canvasHeight() - h - margin
            else -> even(bar + maxOf(0, canvasHeight() - bar - h) / 2.0)
        }
    }

    /** Jarak video dari tepi layar saat diberi sudut membulat atau garis tepi. */
    fun videoInset(): Int = if (framesVideo()) scaled(40.0) + scaled((borderWidth ?: 0).toDouble()) else 0

    /**
     * Letak dan ukuran video di layar. Untuk mode "fit" ukurannya bergantung rasio video sumber; tanpa ukuran sumber
     * dan tanpa bingkai, hasilnya null dan FFmpeg yang menghitung letaknya.
     */
    fun videoRect(source: Pair<Int, Int>? = null): Rect? {
        val width = canvasWidth()
        val height = canvasHeight()

        if (isCrop()) return Rect(0, 0, width, height)

        if (usesTemplate()) {
            // Video memenuhi layar di balik template (bisa di-crop bebas), atau hanya di area transparannya.
            return if (templateFill == "full") Rect(0, 0, width, height) else templateAreaRect()
        }

        val inset = videoInset()

        if (mode == "top") {
            val areaHeight = minOf(videoAreaHeight(), height - titleBarHeight() - 2 * inset)
            return Rect(inset, videoTop(areaHeight, inset), width - 2 * inset, areaHeight)
        }

        if (source == null && !framesVideo()) return null

        val (sourceWidth, sourceHeight) = if (source != null && source.first > 0 && source.second > 0) source else 1920 to 1080
        val maxWidth = width - 2 * inset
        val maxHeight = height - titleBarHeight() - 2 * inset
        var videoWidth = maxWidth
        var videoHeight = even(phpDivide(maxWidth.toLong() * sourceHeight, sourceWidth))

        if (videoHeight > maxHeight) {
            videoWidth = even(phpDivide(maxHeight.toLong() * sourceWidth, sourceHeight))
            videoHeight = even(maxHeight.toDouble())
        }

        return Rect(even((width - videoWidth) / 2.0), videoTop(videoHeight, inset), videoWidth, videoHeight)
    }

    /**
     * Titik tengah vertikal subtitle: pilihan manual, atau pada mode "top" tengah area latar terluas di atas/bawah
     * video. Null = posisi dari gaya subtitle.
     */
    fun subtitleCenterY(videoTop: Int? = null, videoHeight: Int? = null): Int? {
        subtitleY?.let { return Php.roundInt(canvasHeight() * clamp(it, 5, 95) / 100.0) }

        if (usesTemplate()) {
            val rect = videoRect()!!
            return Php.roundInt(rect.y + rect.height * 0.8)
        }

        if (mode != "top") return null

        val rect = videoRect()!!
        val vh = videoHeight ?: rect.height
        val vt = videoTop ?: rect.y
        val bottom = vt + vh
        val above = vt - titleBarHeight()

        // Area bawah diutamakan; area atas dipakai bila jelas lebih lega.
        return if (canvasHeight() - bottom >= above * 0.9) (bottom + canvasHeight()) / 2 else (titleBarHeight() + vt) / 2
    }

    fun zoomFactor(): Double = clamp(zoom ?: 100, 100, 200) / 100.0

    /** Video tidak memenuhi layar, jadi sudut membulat dan garis tepi berlaku. */
    fun framesVideo(): Boolean = !isCrop() && !usesTemplate() && ((cornerRadius ?: 0) > 0 || (borderWidth ?: 0) > 0)

    /** Ukuran dalam piksel layar dari nilai yang ditentukan untuk layar 9:16 selebar 1080. */
    fun scaled(value: Double): Int = Php.roundInt(value * uiScale())

    /** Lebar logo: persen dari sisi layar yang lebih pendek, supaya sama besar di semua rasio. */
    fun logoWidth(): Int = even(minOf(canvasWidth(), canvasHeight()) * clamp(logoSize ?: 18, 5, 40) / 100.0)

    /** Mode template yang area videonya lengkap. */
    fun usesTemplate(): Boolean = mode == "template" && templateArea != null

    /** Area transparan template di layar (letak judul, hook, dan subtitle mengikuti area ini). */
    fun templateAreaRect(): Rect? {
        val area = templateArea ?: return null
        val width = canvasWidth()
        val height = canvasHeight()
        // Video sedikit melebihi area transparan (tertutup template), supaya tidak ada celah di tepinya.
        val left = maxOf(0.0, area.x - 0.005)
        val top = maxOf(0.0, area.y - 0.005)
        val right = minOf(1.0, area.x + area.width + 0.005)
        val bottom = minOf(1.0, area.y + area.height + 0.005)
        return Rect(even(width * left), even(height * top), even(width * (right - left)), even(height * (bottom - top)))
    }

    fun hasWatermarkText(): Boolean = Php.trim(watermarkText.orEmpty()).isNotEmpty()

    /** Ringkasan singkat, misalnya "9:16 · Video utuh · latar blur 8". */
    fun summary(): String {
        val bg = when (background) {
            "blur" -> "latar blur ${blurStrength.php()}"
            "gradient" -> "latar gradasi"
            "image" -> "latar gambar"
            else -> "latar ${backgroundColor.orEmpty()}"
        }
        val parts = mutableListOf(
            aspect ?: "9:16",
            when (mode) {
                "crop" -> MODES.getValue("crop")
                "template" -> MODES.getValue("template")
                "top" -> MODES.getValue("top") + " ${videoHeight.php()}% · $bg"
                else -> MODES.getValue("fit") + " · $bg"
            },
        )
        if ((zoom ?: 100) > 100) parts += "zoom ${zoom}%"
        if (framesVideo()) parts += if ((borderWidth ?: 0) > 0) "bingkai video" else "sudut membulat"
        if (logoPath != null) parts += "logo"
        if (hasWatermarkText()) parts += "watermark teks"
        if (titleBar == true) parts += "bilah judul"
        return parts.joinToString(" · ")
    }

    companion object {
        val ASPECTS: Map<String, Aspect> = linkedMapOf(
            "9:16" to Aspect("9:16", "Reels, TikTok, Shorts", 1080, 1920, 1.0),
            "4:5" to Aspect("4:5", "Feed Instagram, Facebook", 1080, 1350, 0.9),
            "1:1" to Aspect("1:1", "Feed persegi", 1080, 1080, 0.85),
            "16:9" to Aspect("16:9", "YouTube biasa", 1920, 1080, 0.6),
        )

        val MODES = linkedMapOf("crop" to "Crop penuh", "fit" to "Video utuh", "top" to "Video + area teks", "template" to "Template sendiri")
        val VIDEO_POSITIONS = linkedMapOf("top" to "Atas", "center" to "Tengah", "bottom" to "Bawah")
        val BACKGROUNDS = linkedMapOf("blur" to "Blur dari video", "color" to "Warna polos", "gradient" to "Gradasi", "image" to "Gambar sendiri")

        /** Sembilan titik jangkar logo dan watermark: label, x, y dalam persen ruang geraknya. */
        val ANCHORS: Map<String, Triple<String, Int, Int>> = linkedMapOf(
            "top-left" to Triple("Kiri atas", 0, 0),
            "top-center" to Triple("Tengah atas", 50, 0),
            "top-right" to Triple("Kanan atas", 100, 0),
            "middle-left" to Triple("Kiri tengah", 0, 50),
            "middle-center" to Triple("Tengah", 50, 50),
            "middle-right" to Triple("Kanan tengah", 100, 50),
            "bottom-left" to Triple("Kiri bawah", 0, 100),
            "bottom-center" to Triple("Tengah bawah", 50, 100),
            "bottom-right" to Triple("Kanan bawah", 100, 100),
        )

        /** Jarak logo dan watermark dari tepi layar, pada layar selebar 1080. */
        const val EDGE_MARGIN = 48

        /** Gaya bawaan desktop (`FrameStyle::resolveDefault` tanpa gaya tersimpan). */
        val DEFAULT = FrameStyle(
            name = "Crop penuh",
            aspect = "9:16",
            mode = "crop",
            background = "blur",
            blurStrength = 8,
            backgroundColor = "#000000",
            videoHeight = 65,
            videoPosition = "center",
            zoom = 100,
            titleBar = false,
        )

        /** `FrameStyle::even`: dibulatkan ke bilangan genap terdekat (round PHP). */
        fun even(value: Double): Int = Php.roundInt(value / 2) * 2

        fun anchorFor(x: Int?, y: Int?): String? = ANCHORS.entries.firstOrNull { it.value.second == x && it.value.third == y }?.key
    }
}

internal fun clamp(value: Int, min: Int, max: Int) = maxOf(min, minOf(max, value))

/** `$a / $b` PHP untuk bilangan bulat: hasil pecahan double bila tidak habis dibagi. */
internal fun phpDivide(a: Long, b: Int): Double = a.toDouble() / b

/** Interpolasi "{$value}" PHP: null menjadi teks kosong. */
private fun Int?.php(): String = this?.toString().orEmpty()
