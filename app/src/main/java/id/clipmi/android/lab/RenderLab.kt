package id.clipmi.android.lab

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import id.clipmi.android.nativebridge.AssRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uji mesin render: Media3 Transformer (GPU + encoder hardware) dibandingkan FFmpeg buatan sendiri (CPU + encoder
 * hardware MediaCodec) untuk potong, layar 9:16 (crop tengah atau video utuh dengan latar blur), subtitle libass, dan
 * rapikan audio. Input: lab/input.mp4 (hasil uji ytdlp atau `adb push`). Hasil: file MP4 di lab/render dan results.jsonl.
 *
 * Parameter: mode=crop|blur, start (detik, bawaan 60), seconds (bawaan 60), ass (nama fixture), audio=aac|ffmpeg.
 */
@UnstableApi
class RenderLab(private val context: Context, private val log: LabLog) {
    private val outDir = File(log.dir, "render").apply { mkdirs() }
    private val input get() = File(log.dir, "input.mp4").also { check(it.exists()) { "lab/input.mp4 belum ada (jalankan uji ytdlp atau adb push)" } }
    private val ffmpegBin get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private data class Job(val mode: String, val startS: Double, val seconds: Double, val ass: String, val audio: String)

    private fun job(params: Map<String, String>) = Job(
        mode = params["mode"] ?: "blur",
        startS = params["start"]?.toDouble() ?: 60.0,
        seconds = params["seconds"]?.toDouble() ?: 60.0,
        ass = params["ass"] ?: "poppins-outline",
        audio = params["audio"] ?: "aac",
    )

    // ---------------------------------------------------------------------------------------------------------------
    // Media3 Transformer

    suspend fun media3(params: Map<String, String>) {
        val job = job(params)
        val fonts = AssRenderer.installFonts(context)
        val subtitle = loopedAss(job)
        val (inW, inH) = videoSize(input)
        val output = File(outDir, "media3_${job.mode}.mp4").apply { delete() }

        val assRenderer = AssRenderer(OUT_W, OUT_H, fonts, File(fonts, "Poppins-Bold.ttf")).apply { load(subtitle.readText()) }
        val overlay = AssOverlay(assRenderer)
        val videoEffects = when (job.mode) {
            "crop" -> listOf(
                Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                OverlayEffect(listOf(overlay)),
            )
            // Video utuh di tengah, latar = video yang sama diregangkan ke 9:16 lalu di-blur. Frame tajam diskalakan
            // balik ke rasio aslinya oleh GaussianBlurWithFrameOverlaid. sigma 28 ≈ boxblur=8:2 desktop di layar 1/4.
            else -> listOf(
                Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_STRETCH_TO_FIT),
                GaussianBlurWithFrameOverlaid(28f, 1f, (OUT_W.toFloat() * inH / inW) / OUT_H),
                OverlayEffect(listOf(overlay)),
            )
        }

        val clip = MediaItem.Builder().setUri(input.toURI().toString()).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((job.startS * 1000).toLong())
                .setEndPositionMs(((job.startS + job.seconds) * 1000).toLong())
                .build(),
        ).build()

        val thermalBefore = DeviceProbe.thermal(context)
        val started = System.nanoTime()
        var audioMs = 0L
        val composition = if (job.mode == "blur2") {
            // Seperti desktop: latar dibuat dari versi 1/4 layar (crop 270x480 + blur kecil) lalu diperbesar, video
            // tajam selebar layar di depan. Dua sequence dari file yang sama, digabung VideoCompositor; subtitle
            // sebagai efek tingkat Composition (di atas hasil gabungan).
            val front = EditedMediaItem.Builder(clip)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(OUT_W, OUT_W * inH / inW / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            val back = EditedMediaItem.Builder(clip).setRemoveAudio(true).setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(OUT_W / 4, OUT_H / 4, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        // sigma 7 di 270x480 ≈ boxblur=8:2 (dua kotak lebar 17: varians 2 x 24).
                        androidx.media3.effect.GaussianBlur(7f),
                        Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_STRETCH_TO_FIT),
                    ),
                ),
            ).build()
            Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(listOf(front)),
                EditedMediaItemSequence.withVideoFrom(listOf(back)),
            )
                .setVideoCompositorSettings(object : androidx.media3.common.VideoCompositorSettings {
                    override fun getOutputSize(inputSizes: List<androidx.media3.common.util.Size>) =
                        androidx.media3.common.util.Size(OUT_W, OUT_H)

                    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long) =
                        androidx.media3.effect.StaticOverlaySettings.Builder().build()
                })
                .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
                .build()
        } else if (job.audio == "ffmpeg") {
            // Audio dirapikan FFmpeg (rantai filter desktop), video oleh Media3; digabung dalam satu Composition.
            val audioFile = File(outDir, "audio_clean.m4a")
            audioMs = runFfmpeg(audioArgs(job, audioFile), job.seconds)
            val video = EditedMediaItem.Builder(clip).setRemoveAudio(true).setEffects(Effects(emptyList(), videoEffects)).build()
            val audio = EditedMediaItem.Builder(MediaItem.fromUri(audioFile.toURI().toString())).build()
            Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(listOf(video)),
                EditedMediaItemSequence.withAudioFrom(listOf(audio)),
            ).build()
        } else {
            val item = EditedMediaItem.Builder(clip).setEffects(Effects(emptyList(), videoEffects)).build()
            Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item))).build()
        }

        val result = export(composition, output)
        val totalMs = (System.nanoTime() - started) / 1_000_000
        assRenderer.close()
        log.result(
            "render_media3",
            mapOf(
                "ok" to true, "mode" to job.mode, "audio" to job.audio, "clip_s" to job.seconds,
                "ms" to totalMs, "audio_ms" to audioMs,
                "x_realtime" to "%.2f".format(java.util.Locale.ROOT, job.seconds * 1000 / totalMs),
                "bytes" to result.fileSizeBytes, "video_kbps" to result.averageVideoBitrate / 1000,
                "frames" to result.videoFrameCount, "size" to "${result.width}x${result.height}",
                "encoder" to result.videoEncoderName, "audio_encoder" to result.audioEncoderName,
                "overlay_uploads" to overlay.uploads, "overlay_first_us" to overlay.firstPresentationUs,
                "temp_before" to thermalBefore["battery_temp_c"], "temp_after" to DeviceProbe.thermal(context)["battery_temp_c"],
                "thermal_after" to DeviceProbe.thermal(context)["thermal_status"],
                "output" to output.name,
            ),
        )
    }

    private suspend fun export(composition: Composition, output: File): ExportResult = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // Tanpa ini Media3 meng-encode 1920x1080 + rotasi -90 (demi encoder lama); desktop menulis 1080x1920 asli.
            .setPortraitEncodingEnabled(true)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
        coroutineScope {
            val ticker = launch {
                val holder = ProgressHolder()
                var last = -10
                while (true) {
                    delay(1000)
                    transformer.getProgress(holder)
                    if (holder.progress - last >= 10) { last = holder.progress; log.line("  media3 ${holder.progress}%") }
                }
            }
            try { done.await() } finally { ticker.cancel() }
        }
    }

    /** Overlay subtitle: bitmap libass per frame; diunggah ulang ke GPU hanya bila gambarnya berubah. */
    private class AssOverlay(private val ass: AssRenderer) : BitmapOverlay() {
        private val bitmap = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        var uploads = 0
        var firstPresentationUs: Long? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (firstPresentationUs == null) firstPresentationUs = presentationTimeUs
            if (ass.render(presentationTimeUs / 1000, bitmap)) uploads++
            return bitmap
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // FFmpeg

    suspend fun ffmpeg(params: Map<String, String>) {
        val job = job(params)
        val fonts = AssRenderer.installFonts(context)
        val subtitle = loopedAss(job)
        val output = File(outDir, "ffmpeg_${job.mode}.mp4").apply { delete() }
        val video = when (job.mode) {
            "crop" -> "[0:v]scale=$OUT_W:$OUT_H:force_original_aspect_ratio=increase,crop=$OUT_W:$OUT_H,setsar=1"
            // Sama dengan background() + bingkai "video utuh" desktop; boxblur (GPL) diganti avgblur dua kali.
            else -> "[0:v]split[bg][fg];" +
                "[bg]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,avgblur=sizeX=8,avgblur=sizeX=8," +
                "scale=$OUT_W:$OUT_H,setsar=1[back];[fg]scale=$OUT_W:-2,setsar=1[front];[back][front]overlay=0:(H-h)/2"
        }
        val filter = "$video,ass=${subtitle.name}:fontsdir=${fonts.absolutePath}[v]"
        val args = listOf(
            "-hide_banner", "-nostats", "-loglevel", "error", "-progress", "pipe:1", "-y",
            "-ss", job.startS.toString(), "-t", job.seconds.toString(), "-i", input.absolutePath,
            "-filter_complex", filter, "-map", "[v]", "-map", "0:a:0?",
            // Encoder Qualcomm menolak yuv420p (configure -10000); nv12 adalah format input yang umum didukung.
            "-c:v", "h264_mediacodec", "-ndk_codec", "1", "-b:v", VIDEO_BITRATE.toString(), "-pix_fmt", "nv12",
            "-af", AUDIO_FILTER, "-c:a", "aac", "-b:a", "128k", "-ar", "48000",
            "-movflags", "+faststart", output.absolutePath,
        )
        val thermalBefore = DeviceProbe.thermal(context)
        val ms = runFfmpeg(args, job.seconds, workDir = outDir)
        log.result(
            "render_ffmpeg",
            mapOf(
                "ok" to output.exists(), "mode" to job.mode, "clip_s" to job.seconds, "ms" to ms,
                "x_realtime" to "%.2f".format(java.util.Locale.ROOT, job.seconds * 1000 / ms), "bytes" to output.length(),
                "temp_before" to thermalBefore["battery_temp_c"], "temp_after" to DeviceProbe.thermal(context)["battery_temp_c"],
                "thermal_after" to DeviceProbe.thermal(context)["thermal_status"], "output" to output.name,
            ),
        )
    }

    suspend fun audioOnly(params: Map<String, String>) {
        val job = job(params)
        val output = File(outDir, "audio_clean.m4a")
        val ms = runFfmpeg(audioArgs(job, output), job.seconds)
        log.result("audio_ffmpeg", mapOf("ok" to output.exists(), "clip_s" to job.seconds, "ms" to ms,
            "x_realtime" to "%.2f".format(java.util.Locale.ROOT, job.seconds * 1000 / ms), "bytes" to output.length()))
    }

    private fun audioArgs(job: Job, output: File) = listOf(
        "-hide_banner", "-nostats", "-loglevel", "error", "-progress", "pipe:1", "-y",
        "-ss", job.startS.toString(), "-t", job.seconds.toString(), "-i", input.absolutePath,
        "-vn", "-af", AUDIO_FILTER, "-c:a", "aac", "-b:a", "128k", "-ar", "48000", output.apply { delete() }.absolutePath,
    )

    /** Menjalankan libffmpeg.so dari nativeLibraryDir (satu-satunya lokasi yang boleh dieksekusi di Android 10+). */
    private suspend fun runFfmpeg(args: List<String>, seconds: Double, workDir: File = outDir): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val process = ProcessBuilder(listOf(ffmpegBin.absolutePath) + args).directory(workDir).start()
        val errors = StringBuilder()
        val stderr = Thread { process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) } }.apply { start() }
        var last = -10
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("out_time_us=")) {
                val pct = ((line.substringAfter('=').toLongOrNull() ?: 0) / 1e4 / seconds).toInt()
                if (pct - last >= 10) { last = pct; log.line("  ffmpeg $pct%") }
            }
        }
        val exit = process.waitFor()
        stderr.join()
        if (errors.isNotBlank()) log.line("  ffmpeg stderr: " + errors.toString().trim().lines().take(10).joinToString(" | "))
        check(exit == 0) { "ffmpeg keluar dengan kode $exit" }
        (System.nanoTime() - started) / 1_000_000
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Subtitle sepanjang klip: event fixture desktop (±4 detik) diulang terus, supaya beban libass sama dengan klip
     * sungguhan. File yang sama dipakai Media3 dan FFmpeg.
     */
    private fun loopedAss(job: Job): File {
        val source = context.assets.open("lab/${job.ass}.ass").bufferedReader().readText()
        val (header, events) = source.lines().partition { !it.startsWith("Dialogue:") }
        val parsed = events.map { line ->
            val parts = line.split(",", limit = 10)
            Triple(parseTime(parts[1]), parseTime(parts[2]), parts)
        }
        val period = (parsed.maxOf { it.second } + 0.5)
        val looped = buildList {
            var offset = 0.0
            while (offset < job.seconds) {
                for ((start, end, parts) in parsed) {
                    if (start + offset >= job.seconds) continue
                    add((listOf(parts[0], formatTime(start + offset), formatTime(minOf(end + offset, job.seconds))) + parts.drop(3)).joinToString(","))
                }
                offset += period
            }
        }
        return File(outDir, "sub.ass").apply { writeText((header + looped).joinToString("\n") + "\n") }
    }

    private fun parseTime(value: String): Double {
        val (h, m, s) = value.trim().split(":")
        return h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()
    }

    private fun formatTime(seconds: Double): String {
        val cs = Math.round(seconds * 100)
        return "%d:%02d:%02d.%02d".format(cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100)
    }

    private fun videoSize(file: File): Pair<Int, Int> = MediaExtractor().run {
        setDataSource(file.absolutePath)
        val format = (0 until trackCount).map(::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        release()
        format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
    }

    companion object {
        const val OUT_W = 1080
        const val OUT_H = 1920
        const val VIDEO_BITRATE = 6_000_000

        /** Sama dengan `ClipRenderer::AUDIO_FILTER` desktop. */
        const val AUDIO_FILTER = "highpass=f=80,afftdn=nf=-25,loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000"
    }
}
