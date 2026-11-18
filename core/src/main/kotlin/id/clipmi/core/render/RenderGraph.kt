package id.clipmi.core.render

import id.clipmi.core.php.Php

/**
 * Filtergraph FFmpeg dari komposisi (padanan bagian murni `App\Render\ClipRenderer` desktop: filter, frame,
 * background, joinBumpers, opener, audioFilter). Teks yang dihasilkan identik dengan desktop; FFmpeg di HP dibangun
 * tanpa filter GPL, jadi `boxblur` diganti lewat [lgpl] tepat sebelum dijalankan.
 */
object RenderGraph {
    /**
     * Rapikan audio ceramah: buang dengung frekuensi rendah, kurangi noise latar ringan, samakan kekerasan ke -14 LUFS
     * dengan puncak -1,5 dB, lalu kembali ke 48 kHz.
     */
    const val AUDIO_FILTER = "highpass=f=80,afftdn=nf=-25,loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000"

    /** Kartu pembuka: kekaburan (radius boxblur di layar selebar 1080, dua kali) dan kecerahan frame-nya. */
    const val OPENER_BLUR = 30
    const val OPENER_BRIGHTNESS = 0.55

    /** Lama kartu pembuka memudar ke isi klip (detik). */
    const val OPENER_FADE = 0.3

    /** Nomor input gambar (input 0 = video): latar, template, logo. */
    data class Inputs(val background: Int? = null, val template: Int? = null, val logo: Int? = null)

    /**
     * Intro/outro atau kartu pembuka yang disambung: nomor input, punya suara, durasi, rantai filter video sendiri
     * (kartu pembuka), dan lama transisi ke klip.
     */
    data class BumperPart(val input: Int, val hasAudio: Boolean, val duration: Double, val chain: String? = null, val fade: Double? = null)

    /**
     * `boxblur=R:2` (GPL) menjadi dua kali `avgblur` (LGPL) dengan radius sama; hasil visualnya diuji setara di
     * Fase 0 (SSIM 0,993).
     */
    fun lgpl(graph: String): String = BOXBLUR.replace(graph) { m -> "avgblur=sizeX=${m.groupValues[1]},avgblur=sizeX=${m.groupValues[1]}" }

    private val BOXBLUR = Regex("""boxblur=(\d+):2""")

    /**
     * Filtergraph lengkap: bingkai, logo, watermark teks, lalu subtitle (paling atas) ke [v].
     *
     * @param keep ekspresi bagian klip yang tetap ada ([ClipCuts.selectExpression]); null = utuh.
     */
    fun filter(composition: Composition, inputs: Inputs = Inputs(), keep: String? = null): String {
        var graph = frame(composition, inputs)

        // Bagian yang dibuang dilewati setelah bingkai; nomor frame lalu disusun ulang tanpa celah.
        if (keep != null) graph += ",select='$keep',setpts=N/FRAME_RATE/TB"

        // Logo dan watermark ditempel sebelum subtitle, jadi subtitle dan bilah judul tetap di atasnya.
        if (inputs.logo != null) {
            val logo = composition.logo
            val (x, y) = composition.anchorExpression(logo.x, logo.y, "W", "H", "w", "h")
            val opacity = Php.numberFormat(logo.opacity, 2)
            graph += "[base];[${inputs.logo}:v]scale=${logo.width}:-1,format=rgba,colorchannelmixer=aa=$opacity[logo];" +
                "[base][logo]overlay=$x:$y:format=auto"
        }

        composition.watermark?.let { watermark ->
            val (x, y) = composition.anchorExpression(watermark.x, watermark.y, "w", "h", "tw", "th")
            val opacity = Php.numberFormat(watermark.opacity, 2)
            // Teks dibaca dari file, supaya tanda baca tidak perlu di-escape.
            graph += ",drawtext=fontfile=fonts/${watermark.file}:textfile=watermark.txt:expansion=none" +
                ":fontsize=${watermark.size}" +
                ":fontcolor=0x${watermark.color.trimStart('#')}@$opacity" +
                ":shadowx=${watermark.shadow}:shadowy=${watermark.shadow}:shadowcolor=black@0.45:x='$x':y='$y'"
        }

        return "$graph,ass=subs.ass:fontsdir=fonts[v]"
    }

    /** Tata letak bingkai tanpa subtitle, sebagai filtergraph yang diakhiri rantai filter terbuka. */
    private fun frame(composition: Composition, inputs: Inputs): String {
        val width = composition.canvas.width
        val height = composition.canvas.height
        val video = composition.video
        val zoom = video.zoom
        val fraction = { value: Int -> Php.numberFormat(value / 100.0, 2) }

        // Posisi crop tetap (jalur penceramah belum ada di HP).
        val fixedX = "(iw-ow)*" + fraction(video.cropX)
        val x = fixedX
        val y = if (video.cropY == 50) "(ih-oh)/2" else "(ih-oh)*" + fraction(video.cropY)
        // Zoom pada video yang dipotong: video diskalakan lebih besar dulu, lalu dipotong seukuran area.
        val crop = { areaWidth: Int, areaHeight: Int ->
            "scale=${FrameStyle.even(areaWidth * zoom)}:${FrameStyle.even(areaHeight * zoom)}:force_original_aspect_ratio=increase," +
                "crop=$areaWidth:$areaHeight:$x:$y,setsar=1"
        }
        // Zoom pada mode video utuh: sebagian video dipotong sebelum diperkecil ke layar.
        val zoomText = Php.numberFormat(zoom, 3).trimEnd('0').trimEnd('.')
        val fitAt = if (video.cropX == 50 && video.cropY == 50) "" else ":$fixedX:$y"
        val fitZoom = if (zoom > 1) "crop=trunc(iw/$zoomText/2)*2:trunc(ih/$zoomText/2)*2$fitAt," else ""

        if (video.mode == "crop") return "[0:v]${crop(width, height)}"

        val rect = video.rect
        val background = composition.background!!
        val color = "0x" + background.color.orEmpty().trimStart('#')

        // Template: video mengisi area transparan, lalu gambar template ditempel di atasnya.
        if (composition.template && inputs.template != null) {
            rect!!
            return "[0:v]${crop(rect.width, rect.height)},pad=$width:$height:${rect.x}:${rect.y}:color=$color[video];" +
                "[${inputs.template}:v]scale=$width:$height,format=rgba[template];[video][template]overlay=0:0:format=auto"
        }

        val front: String
        val frontX: String
        val frontY: String
        val padX: String
        val padY: String
        if (rect == null) {
            // Video utuh tanpa ukuran sumber: FFmpeg menyesuaikan ukurannya, letaknya dihitung dari ukuran hasil.
            val bar = composition.bar
            front = "${fitZoom}scale=$width:${height - bar}:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1"
            val place = { outer: String, inner: String ->
                when (video.position) {
                    "top" -> bar.toString()
                    "bottom" -> "$outer-$inner"
                    else -> if (bar > 0) "$bar+($outer-$bar-$inner)/2" else "($outer-$inner)/2"
                }
            }
            frontX = "(W-w)/2"
            frontY = place("H", "h")
            padX = "(ow-iw)/2"
            padY = place("oh", "ih")
        } else {
            front = if (video.fill == "cover") crop(rect.width, rect.height) else "${fitZoom}scale=${rect.width}:${rect.height},setsar=1"
            frontX = rect.x.toString()
            frontY = rect.y.toString()
            padX = frontX
            padY = frontY
        }

        val hasImage = background.kind == "image" && inputs.background != null

        // Latar polos tanpa bingkai video: cukup diberi pad, tanpa memproses video dua kali.
        if (!composition.framesVideo() && (background.kind == "color" || (background.kind == "image" && !hasImage))) {
            return "[0:v]$front,pad=$width:$height:$padX:$padY:color=$color"
        }

        var graph = "[0:v]split=2[bg][fg];" + background(composition, inputs)
        var back = "back"
        var frontChain = front

        if (composition.framesVideo()) {
            rect!!
            val radius = video.radius
            val border = video.border
            val borderColor = "0x" + video.borderColor.trimStart('#')

            if (radius > 0) {
                // Sudut membulat: topeng abu-abu dibuat sekali (satu frame) lalu dipakai untuk semua frame.
                graph += "color=c=black:s=${rect.width}x${rect.height}:r=1:d=1,format=gray,geq=lum='${roundedMask(radius)}'[mask];"
                frontChain += ",format=yuva420p[rounded];[rounded][mask]alphamerge"
            }

            if (border > 0 && radius > 0) {
                graph += "color=c=$borderColor:s=${rect.width + 2 * border}x${rect.height + 2 * border}:r=1:d=1,format=rgba," +
                    "geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='${roundedMask(radius + border)}'[plate];" +
                    "[back][plate]overlay=${rect.x - border}:${rect.y - border}[framed];"
                back = "framed"
            } else if (border > 0) {
                graph += "[back]drawbox=x=${rect.x - border}:y=${rect.y - border}" +
                    ":w=${rect.width + 2 * border}:h=${rect.height + 2 * border}:color=$borderColor:t=fill[framed];"
                back = "framed"
            }
        }

        return "$graph[fg]$frontChain[front];[$back][front]overlay=$frontX:$frontY"
    }

    /** Latar selayar penuh dari input [bg] ke [back]: blur dari video, warna polos, gradasi, atau gambar sendiri. */
    private fun background(composition: Composition, inputs: Inputs): String {
        val width = composition.canvas.width
        val height = composition.canvas.height
        val background = composition.background!!
        val base = "[bg]scale=2:2,scale=$width:$height:flags=neighbor,setsar=1"

        if (background.kind == "blur") {
            // Dibuat dari versi kecil (seperempat layar) supaya cepat, lalu diperbesar.
            val small = "${FrameStyle.even(width / 4.0)}:${FrameStyle.even(height / 4.0)}"
            return "[bg]scale=$small:force_original_aspect_ratio=increase,crop=$small,boxblur=${background.blur}:2,scale=$width:$height,setsar=1[back];"
        }

        if (background.kind == "image" && inputs.background != null) {
            return "[${inputs.background}:v]scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,setsar=1[still];" +
                "$base[base0];[base0][still]overlay=0:0[back];"
        }

        if (background.kind == "gradient") {
            val (r1, g1, b1) = rgb(background.color)
            val (r2, g2, b2) = rgb(background.color2)
            return "color=c=black:s=${width}x$height:r=1:d=1,format=rgb24," +
                "geq=r='$r1+(${r2 - r1})*Y/H':g='$g1+(${g2 - g1})*Y/H':b='$b1+(${b2 - b1})*Y/H'[still];" +
                "$base[base0];[base0][still]overlay=0:0[back];"
        }

        return "$base,drawbox=x=0:y=0:w=iw:h=ih:color=0x${background.color.orEmpty().trimStart('#')}:t=fill[back];"
    }

    /** `sscanf($hex, '%02x%02x%02x')`. */
    private fun rgb(hex: String?): Triple<Int, Int, Int> {
        val h = hex.orEmpty().trimStart('#')
        val part = { i: Int -> Php.substr(h, i, 2).toIntOrNull(16) ?: 0 }
        return Triple(part(0), part(2), part(4))
    }

    /** Ekspresi geq untuk persegi bersudut membulat dengan tepi halus (255 di dalam, 0 di luar). */
    private fun roundedMask(radius: Int): String {
        val dx = "max(0,max($radius-X,X-(W-1-$radius)))"
        val dy = "max(0,max($radius-Y,Y-(H-1-$radius)))"
        return "255*clip($radius.5-hypot($dx,$dy),0,1)"
    }

    /**
     * Menyambung intro, klip, dan outro (filter concat). [graph] = filtergraph klip yang berakhir di [joined];
     * hasil: video di [joined], suara di [a].
     */
    fun joinBumpers(
        graph: String,
        frame: FrameStyle,
        parts: Map<String, BumperPart>,
        fps: String,
        keep: String?,
        mainAudio: Boolean,
        length: Double,
        enhanceAudio: Boolean = true,
    ): String {
        val width = frame.canvasWidth()
        val height = frame.canvasHeight()
        val format = "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo"
        val silence = { seconds: Double -> "anullsrc=r=48000:cl=stereo,atrim=duration=${seconds(seconds)}" }
        var result = graph.removeSuffix("[joined]") + "[clip];[clip]fps=$fps,format=yuv420p[mv]"
        val mainFilters = listOfNotNull(
            keep?.let { "aselect='$it',asetpts=N/SR/TB" },
            if (enhanceAudio) AUDIO_FILTER else null,
            format,
        )
        result += ";" + (if (mainAudio) "[0:a]" + mainFilters.joinToString(",") else silence(length)) + "[ma]"
        val segments = mutableListOf<String>()
        var main = "[mv][ma]"

        for (kind in listOf("intro", "main", "outro")) {
            if (kind == "main") {
                segments += main
                continue
            }
            val part = parts[kind] ?: continue
            result += ";" + (part.chain ?: "[${part.input}:v]scale=$width:$height:force_original_aspect_ratio=decrease," +
                "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=$fps,format=yuv420p") + "[${kind}v]"
            result += ";" + (if (part.hasAudio) "[${part.input}:a]$format" else silence(part.duration)) + "[${kind}a]"

            // Pembuka dengan transisi (kartu pembuka): memudar ke klip yang mulai tepat di akhir pembuka.
            if (kind == "intro" && part.fade != null) {
                result += ";[introv][mv]xfade=transition=fade:duration=${seconds(part.fade)}:offset=${seconds(part.duration)}[fadedv]" +
                    ";[introa][ma]concat=n=2:v=0:a=1[fadeda]"
                main = "[fadedv][fadeda]"
                continue
            }
            segments += "[${kind}v][${kind}a]"
        }

        return result + ";" + segments.joinToString("") + "concat=n=${segments.size}:v=1:a=1[joined][a]"
    }

    /**
     * Rantai filter video kartu pembuka: frame pertama klip memenuhi layar, dikaburkan dan digelapkan, lalu diulang
     * sepanjang [seconds].
     *
     * @param center titik tengah penceramah (0–1 lebar video); null = posisi crop tetap.
     */
    fun opener(input: Int, frame: FrameStyle, center: Double?, cropPosition: Int, fps: String, seconds: Double): String {
        val width = frame.canvasWidth()
        val height = frame.canvasHeight()
        val x = if (center != null) {
            "'max(0,min(iw-ow,iw*${Php.numberFormat(center, 4)}-ow/2))'"
        } else {
            "(iw-ow)*" + Php.numberFormat(clamp(cropPosition, 0, 100) / 100.0, 2)
        }
        val blur = maxOf(1, frame.scaled(OPENER_BLUR.toDouble()))
        val light = Php.numberFormat(OPENER_BRIGHTNESS, 2)
        val frames = maxOf(1, Php.roundInt(seconds * fps.toDouble()))

        return "[$input:v]trim=end_frame=1,scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height:$x:(ih-oh)/2,setsar=1," +
            "boxblur=$blur:2,format=rgb24,colorchannelmixer=rr=$light:gg=$light:bb=$light," +
            "loop=loop=${frames - 1}:size=1:start=0,setpts=N/($fps*TB),fps=$fps,format=yuv420p"
    }

    /** Argumen audio: bagian yang dibuang dilewati, lalu dirapikan bila opsinya aktif. */
    fun audioFilter(keep: String?, enhanceAudio: Boolean = true): List<String> {
        val filters = listOfNotNull(keep?.let { "aselect='$it',asetpts=N/SR/TB" }, if (enhanceAudio) AUDIO_FILTER else null)
        return if (filters.isEmpty()) emptyList() else listOf("-af", filters.joinToString(","))
    }

    fun seconds(value: Double): String = Php.numberFormat(value, 3)
}
