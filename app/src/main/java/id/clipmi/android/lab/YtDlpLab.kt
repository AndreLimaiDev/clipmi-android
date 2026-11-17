package id.clipmi.android.lab

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import id.clipmi.core.youtube.YtDlpArgs
import java.io.File
import java.nio.ByteBuffer

/**
 * Uji unduh YouTube di HP: yt-dlp (Python 3.12 tertanam dari youtubedl-android) + QuickJS untuk tantangan JS.
 * Langkah sama dengan desktop: metadata + subtitle json3 dulu, lalu video. Video dan audio diunduh terpisah dan
 * digabung MediaMuxer (tanpa encode ulang), karena tidak ada FFmpeg untuk yt-dlp.
 */
class YtDlpLab(private val context: Context, private val log: LabLog) {
    private val ytdl = YoutubeDL.getInstance()
    private val cacheDir = File(context.cacheDir, "yt-dlp").apply { mkdirs() }.absolutePath

    private fun init(): Long {
        val started = System.nanoTime()
        ytdl.init(context)
        return (System.nanoTime() - started) / 1_000_000
    }

    fun update(channel: String) {
        init()
        val before = ytdl.version(context)
        val started = System.nanoTime()
        val status = ytdl.updateYoutubeDL(
            context,
            when (channel) {
                "nightly" -> YoutubeDL.UpdateChannel._NIGHTLY
                "master" -> YoutubeDL.UpdateChannel._MASTER
                else -> YoutubeDL.UpdateChannel._STABLE
            },
        )
        log.result(
            "ytdlp_update",
            mapOf("ok" to true, "status" to status, "before" to before, "after" to ytdl.version(context),
                "ms" to (System.nanoTime() - started) / 1_000_000),
        )
    }

    fun run(url: String, video: Boolean, reuse: Boolean, onProgress: (Float) -> Unit) {
        val initMs = init()
        log.line("yt-dlp ${ytdl.version(context)} siap (init $initMs ms)")
        // Biaya tetap menyalakan Python + memuat yt-dlp, tanpa jaringan.
        val startup = execute("version", listOf("--version"))
        log.line("python+yt-dlp --version: ${startup.ms} ms")
        val dir = File(log.dir, "ytdlp").apply { deleteRecursively(); mkdirs() }

        // 1) Metadata + subtitle json3 + thumbnail. -v memperlihatkan JS runtime yang dipakai.
        val meta = execute("meta", YtDlpArgs.metadata(cacheDir, dir.absolutePath) + listOf("-v", "--", url))
        meta.err.lines().filter { it.contains("JS runtimes") || it.contains("[jsc]") || it.contains("n challenge") || it.contains("ERROR") }
            .take(12).forEach { log.line("  $it") }
        File(dir, "meta-stderr.txt").writeText(meta.err)
        val subtitle = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".json3") }
        val info = File(dir, "youtube.info.json")
        log.line("metadata ${meta.ms} ms; info.json=${info.length()} B; subtitle=${subtitle?.name} ${subtitle?.length()} B")
        val results = mutableMapOf<String, Any?>(
            "ytdlp" to ytdl.version(context),
            "init_ms" to initMs,
            "meta_ms" to meta.ms,
            "meta_exit" to meta.exit,
            "subtitle" to subtitle?.name,
            "subtitle_bytes" to subtitle?.length(),
            "jsc" to meta.err.lines().firstOrNull { it.contains("JS Challenge Providers") }?.trim(),
        )
        if (!video) {
            log.result("ytdlp", results + mapOf("ok" to (meta.exit == 0 && info.exists())))
            return
        }

        // 2) Video H.264 ≤1080p + audio AAC (dua file), dengan progres. reuse: pakai info.json dari langkah 1, jadi
        //    ekstraksi dan tantangan JS tidak diulang (URL stream YouTube berlaku ±6 jam).
        var lastPct = -10f
        val source = if (reuse && info.exists()) listOf("--load-info-json", info.absolutePath) else listOf("--", url)
        val media = execute("media", YtDlpArgs.media(cacheDir, dir.absolutePath) + source) { pct ->
            if (pct - lastPct >= 5f) { lastPct = pct; log.line("  unduh ${pct.toInt()}%") }
            onProgress(pct)
        }
        val videoFile = dir.listFiles().orEmpty().firstOrNull { it.name == "source.stream.mp4" }
        val audioFile = dir.listFiles().orEmpty().firstOrNull { it.name == "source.stream.m4a" }
        if (media.exit != 0 || videoFile == null || audioFile == null) {
            media.err.lines().takeLast(15).forEach { log.line("  $it") }
            error("Unduh gagal (exit ${media.exit})")
        }
        val bytes = videoFile.length() + audioFile.length()
        log.line("unduh ${media.ms} ms: ${videoFile.name} ${videoFile.length() / 1024} KB + ${audioFile.name} ${audioFile.length() / 1024} KB")

        // 3) Gabung tanpa encode ulang.
        val output = File(dir, "source.mp4")
        val muxStarted = System.nanoTime()
        mux(videoFile, audioFile, output)
        val muxMs = (System.nanoTime() - muxStarted) / 1_000_000
        val format = MediaExtractor().run {
            setDataSource(output.absolutePath)
            val f = (0 until trackCount).map(::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            release()
            "${f.getString(MediaFormat.KEY_MIME)} ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}"
        }
        // Salinan tetap untuk uji render (render_media3/render_ffmpeg memakai lab/input.mp4).
        output.copyTo(File(log.dir, "input.mp4"), overwrite = true)

        log.result(
            "ytdlp",
            results + mapOf(
                "ok" to true,
                "download_ms" to media.ms,
                "reuse_info_json" to reuse,
                "startup_ms" to startup.ms,
                "download_mbps" to "%.1f".format(java.util.Locale.ROOT, bytes * 8 / 1e6 / (media.ms / 1000.0)),
                "video_bytes" to videoFile.length(),
                "audio_bytes" to audioFile.length(),
                "mux_ms" to muxMs,
                "video_format" to format,
            ),
        )
    }

    private class Run(val exit: Int, val ms: Long, val out: String, val err: String)

    private fun execute(id: String, args: List<String>, onProgress: ((Float) -> Unit)? = null): Run {
        val request = YoutubeDLRequest(emptyList()).addCommands(args)
        val started = System.nanoTime()
        // Baris info yt-dlp ("[youtube] ...", "[info] ...") dicatat dengan jam supaya terlihat tahap yang lambat.
        val response = ytdl.execute(request, "lab-$id") { _, _, line ->
            val progress = YtDlpArgs.parseProgress(line)
            if (progress != null) onProgress?.invoke(progress)
            else if (line.startsWith("[") && !line.startsWith("[download]")) {
                log.line("  +${(System.nanoTime() - started) / 1_000_000} ms $line")
            }
        }
        return Run(response.exitCode, (System.nanoTime() - started) / 1_000_000, response.out, response.err)
    }

    /** Menggabungkan track video dan audio ke satu MP4 tanpa encode ulang (MediaExtractor → MediaMuxer). */
    private fun mux(video: File, audio: File, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(video, audio).map { file ->
            MediaExtractor().apply { setDataSource(file.absolutePath) }.let { ex ->
                val track = (0 until ex.trackCount).first {
                    val mime = ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!
                    if (file == video) mime.startsWith("video/") else mime.startsWith("audio/")
                }
                ex.selectTrack(track)
                ex to muxer.addTrack(ex.getTrackFormat(track))
            }
        }
        muxer.start()
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        for ((ex, track) in sources) {
            while (true) {
                info.size = ex.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buffer, info)
                ex.advance()
            }
            ex.release()
        }
        muxer.stop()
        muxer.release()
    }
}
