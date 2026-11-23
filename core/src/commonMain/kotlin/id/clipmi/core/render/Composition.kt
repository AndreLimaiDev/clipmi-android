package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlinx.serialization.Serializable

/**
 * Komposisi satu layar video hasil (padanan `App\Render\Composition` desktop): ukuran layar, letak dan crop video,
 * latar, bingkai video, template, logo, watermark, serta letak subtitle, hook, dan bilah judul. Semua nilai sudah
 * dibatasi dan dalam piksel layar hasil. Dibaca renderer (filtergraph FFmpeg, [RenderGraph]) dan pratinjau editor,
 * supaya geometri hanya ditulis di satu tempat. Nama field sama dengan JSON `toArray()` desktop.
 */
@Serializable
data class Composition(
    val canvas: Canvas,
    val video: Video,
    val background: Background?,
    val template: Boolean,
    val bar: Int,
    val anchorArea: AnchorArea,
    val logo: Logo,
    val watermark: Watermark?,
    val titleBar: TitleBar?,
    val subtitleY: Int?,
    val hookTop: Int,
) {
    @Serializable
    data class Canvas(val width: Int, val height: Int, val scale: Double)

    /**
     * @property fill "cover": video menutupi areanya lalu dipotong di posisi crop; "contain": video utuh diperkecil.
     * @property keyframes jalur "ikuti penceramah" ([SpeakerTrack]); null = posisi crop tetap.
     */
    @Serializable
    data class Video(
        val mode: String,
        val fill: String,
        val rect: Rect?,
        val position: String,
        val zoom: Double,
        val cropX: Int,
        val cropY: Int,
        val keyframes: List<List<Double>>? = null,
        val radius: Int,
        val border: Int,
        val borderColor: String,
    )

    @Serializable
    data class Background(val kind: String?, val color: String?, val color2: String, val blur: Int)

    /** Ruang gerak logo dan watermark (jarak dari tepi layar). */
    @Serializable
    data class AnchorArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** x/y = posisi 0–1 di ruang geraknya. */
    @Serializable
    data class Logo(val width: Int, val opacity: Double, val x: Double, val y: Double)

    @Serializable
    data class Watermark(
        val text: String,
        val font: String,
        val bold: Boolean,
        val file: String,
        val size: Int,
        val color: String,
        val opacity: Double,
        val shadow: Int,
        val x: Double,
        val y: Double,
    )

    /** Tanpa warna latar (template): judul saja. */
    @Serializable
    data class TitleBar(val height: Int, val background: String?, val color: String?)

    /** Video tidak memenuhi layar dan diberi sudut membulat atau garis tepi, seperti kartu. */
    fun framesVideo(): Boolean = video.radius > 0 || video.border > 0

    /**
     * Letak elemen selebar [itemWidth] dan setinggi [itemHeight] pada posisi 0–1 di ruang geraknya, sebagai ekspresi
     * FFmpeg: tepi kiri/atas, tengah, sampai tepi kanan/bawah.
     */
    fun anchorExpression(x: Double, y: Double, width: String, height: String, itemWidth: String, itemHeight: String): Pair<String, String> {
        val area = anchorArea
        val fraction = { value: Double -> Php.numberFormat(value, 2).trimEnd('0').trimEnd('.').let { if (it.isEmpty() || it == "0") "0" else it } }
        return "${area.left}+($width-$itemWidth-${area.left + area.right})*${fraction(x)}" to
            "${area.top}+($height-$itemHeight-${area.top + area.bottom})*${fraction(y)}"
    }

    companion object {
        /**
         * Komposisi gaya bingkai saja, atau dengan crop milik klip.
         *
         * @param source ukuran video sumber; null = biarkan FFmpeg yang menghitung letak video utuh.
         * @param cropX posisi crop 0 (kiri) sampai 100 (kanan).
         * @param zoom zoom crop klip; null = zoom gaya bingkai.
         * @param cropY posisi crop 0 (atas) sampai 100 (bawah).
         */
        fun make(
            frame: FrameStyle,
            source: Pair<Int, Int>? = null,
            cropX: Int = 50,
            zoom: Double? = null,
            cropY: Int = 50,
            keyframes: List<List<Double>>? = null,
        ): Composition {
            val width = frame.canvasWidth()
            val height = frame.canvasHeight()
            val rect = frame.videoRect(source)
            val template = frame.usesTemplate()
            // Area untuk judul, hook, dan subtitle: area transparan template (walau video memenuhi layar), selain itu area video.
            val area = if (template) frame.templateAreaRect() else rect
            val bar = frame.titleBarHeight()
            val margin = frame.scaled(FrameStyle.EDGE_MARGIN.toDouble())
            val fraction = { value: Int?, default: Int -> Php.round(clamp(value ?: default, 0, 100) / 100.0, 2) }
            val opacity = { value: Int?, default: Int -> Php.round(clamp(value ?: default, 10, 100) / 100.0, 2) }
            val framed = frame.framesVideo()

            return Composition(
                canvas = Canvas(width, height, frame.uiScale()),
                video = Video(
                    mode = when {
                        frame.isCrop() -> "crop"
                        template -> "template"
                        frame.mode == "top" -> "top"
                        else -> "fit"
                    },
                    fill = if (frame.mode == "crop" || frame.mode == "top" || frame.mode == "template") "cover" else "contain",
                    rect = rect,
                    position = frame.videoPosition ?: "center",
                    zoom = zoom ?: frame.zoomFactor(),
                    cropX = clamp(cropX, 0, 100),
                    cropY = clamp(cropY, 0, 100),
                    // Jalur penceramah hanya berlaku untuk mode yang memotong sisi kiri–kanan video.
                    keyframes = if (frame.usesCropPosition()) keyframes else null,
                    radius = if (framed) frame.scaled((frame.cornerRadius ?: 0).toDouble()) else 0,
                    border = if (framed) frame.scaled((frame.borderWidth ?: 0).toDouble()) else 0,
                    borderColor = frame.borderColor ?: "#FFFFFF",
                ),
                background = if (frame.isCrop()) null else Background(
                    kind = frame.background,
                    color = frame.backgroundColor,
                    color2 = frame.backgroundColor2 ?: "#000000",
                    blur = frame.blurStrength ?: 0,
                ),
                template = template,
                bar = bar,
                anchorArea = AnchorArea(margin, bar + margin, margin, margin),
                logo = Logo(
                    width = frame.logoWidth(),
                    opacity = opacity(frame.logoOpacity, 85),
                    x = fraction(frame.logoX, 100),
                    y = fraction(frame.logoY, 0),
                ),
                watermark = if (!frame.hasWatermarkText()) null else Watermark(
                    text = Php.trim(frame.watermarkText!!),
                    font = frame.watermarkFont ?: Fonts.DEFAULT,
                    bold = Fonts.isBold(frame.watermarkFont ?: Fonts.DEFAULT),
                    file = Fonts.file(frame.watermarkFont),
                    size = frame.scaled(clamp(frame.watermarkSize ?: 40, 16, 120).toDouble()),
                    color = frame.watermarkColor ?: "#FFFFFF",
                    opacity = opacity(frame.watermarkOpacity, 80),
                    shadow = maxOf(1, frame.scaled(2.0)),
                    x = fraction(frame.watermarkX, 50),
                    y = fraction(frame.watermarkY, 100),
                ),
                titleBar = if (frame.titleBar != true) null else TitleBar(
                    // Template: judul di area atas desainnya, tanpa bilah warna.
                    height = if (template) area!!.y else bar,
                    background = if (template) null else frame.titleBarColor,
                    color = frame.titleTextColor,
                ),
                subtitleY = frame.subtitleCenterY(area?.y, area?.height),
                // Hook: di bagian atas area video template, di bawah bilah judul, atau sekitar 15% tinggi layar.
                hookTop = when {
                    template -> area!!.y + frame.scaled(60.0)
                    frame.titleBar == true -> bar + frame.scaled(80.0)
                    else -> Php.roundInt(height * 300.0 / AssWriter.HEIGHT)
                },
            )
        }
    }
}
