package id.clipmi.android.domain

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import id.clipmi.core.youtube.YtDlpArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

class YtDlpException(message: String) : Exception(message)

/**
 * yt-dlp lewat youtubedl-android (Python tertanam) dengan QuickJS-ng untuk tantangan JavaScript YouTube.
 * Hanya untuk konten yang sudah mendapat izin dari pemiliknya. Langkahnya sama dengan `App\YouTube\YtDlp` desktop,
 * dengan satu perbedaan: video dan audio diunduh sebagai dua file lalu digabung FFmpeg.
 */
class YtDlp(private val context: Context) {
    private val ytdl = YoutubeDL.getInstance()
    private val cacheDir = File(context.cacheDir, "yt-dlp").apply { mkdirs() }.absolutePath

    private suspend fun ensureInit() = initLock.withLock {
        if (!initialized) {
            withContext(Dispatchers.IO) { ytdl.init(context) }
            initialized = true
        }
    }

    suspend fun version(): String? {
        ensureInit()
        return ytdl.version(context)
    }

    /** Memperbarui yt-dlp ke rilis stabil terbaru (YouTube sering berubah). */
    suspend fun update(): String {
        ensureInit()
        return withContext(Dispatchers.IO) { ytdl.updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)?.name ?: "UNKNOWN" }
    }

    /**
     * Menulis youtube.info.json, subtitle Indonesia (youtube.{bahasa}.json3), dan thumbnail ke [directory] tanpa
     * mengunduh video. Ini satu-satunya ekstraksi (dan tantangan JS, ±16 detik di HP kelas menengah) per video.
     */
    suspend fun fetchInfoAndSubtitles(url: String, directory: File): JsonObject {
        directory.deleteRecursively()
        directory.mkdirs()
        val result = execute("info-${directory.parentFile?.name}", YtDlpArgs.metadata(cacheDir, directory.absolutePath) + listOf("--", url))
        val info = File(directory, "youtube.info.json")
        if (!info.isFile) throw YtDlpException(errorMessage(result.second))
        return Json.parseToJsonElement(info.readText()).jsonObject
    }

    /**
     * Mengunduh video H.264 ≤1080p dan audio AAC ke [directory] sebagai source.stream.mp4/.m4a. Memakai ulang
     * info.json dari [fetchInfoAndSubtitles] bila ada (URL stream berlaku ±6 jam), jadi tantangan JS tidak diulang.
     */
    suspend fun downloadStreams(url: String, directory: File, infoJson: File?, onProgress: (Float) -> Unit): Pair<File, File> {
        directory.mkdirs()
        val source = if (infoJson != null && infoJson.isFile && infoJson.lastModified() > System.currentTimeMillis() - 5 * 3600_000L) {
            listOf("--load-info-json", infoJson.absolutePath)
        } else {
            listOf("--", url)
        }
        // Video dan audio diunduh berurutan; persen kembali ke 0 untuk stream kedua, jadi dipetakan ke 0–85 dan 85–100.
        var stream = 0
        var last = 0f
        val result = execute("media-${directory.name}", YtDlpArgs.media(cacheDir, directory.absolutePath) + source) { pct ->
            if (pct + 20 < last) stream++
            last = pct
            onProgress(if (stream == 0) pct * 0.85f else 85 + pct * 0.15f)
        }
        val video = File(directory, "source.stream.mp4")
        val audio = File(directory, "source.stream.m4a")
        if (!video.isFile || !audio.isFile) throw YtDlpException(errorMessage(result.second))
        return video to audio
    }

    fun cancel(processId: String) = ytdl.destroyProcessById(processId)

    private suspend fun execute(id: String, args: List<String>, onProgress: ((Float) -> Unit)? = null): Pair<Int, String> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(emptyList()).addCommands(args)
            try {
                val response = ytdl.execute(request, id) { _, _, line -> YtDlpArgs.parseProgress(line)?.let { onProgress?.invoke(it) } }
                response.exitCode to response.err
            } catch (e: Exception) {
                1 to (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Baris ERROR terakhir yt-dlp, dirapikan untuk ditampilkan. */
    private fun errorMessage(stderr: String): String =
        stderr.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: stderr.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: "yt-dlp gagal tanpa pesan"

    companion object {
        private val initLock = Mutex()
        @Volatile private var initialized = false
    }
}
