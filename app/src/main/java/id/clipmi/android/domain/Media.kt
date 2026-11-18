package id.clipmi.android.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FfmpegException(message: String) : Exception(message)

/**
 * FFmpeg buatan sendiri (native/build-native.sh) yang dibundel sebagai libffmpeg.so: Android 10+ hanya mengizinkan
 * menjalankan file dari nativeLibraryDir. Perintahnya sama dengan `App\Video\FFmpeg` desktop.
 */
class Ffmpeg(context: Context) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /**
     * Menjalankan ffmpeg. [durationSeconds] + [onProgress] memakai `-progress pipe:1` untuk persen 0–100.
     * @throws FfmpegException berisi baris error terakhir ffmpeg bila gagal.
     */
    suspend fun run(args: List<String>, durationSeconds: Double? = null, directory: File? = null, onProgress: ((Float) -> Unit)? = null) =
        withContext(Dispatchers.IO) {
            val command = listOf(binary.absolutePath, "-hide_banner", "-nostats", "-loglevel", "error") +
                (if (onProgress != null) listOf("-progress", "pipe:1") else emptyList()) + args
            // Folder kerja: filter ass/drawtext memakai nama relatif (subs.ass, fonts/), tanpa escape path.
            val process = ProcessBuilder(command).apply { if (directory != null) directory(directory) }.start()
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) process.destroy() }
            val errors = StringBuilder()
            val stderr = Thread { process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) } }.apply { start() }
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (onProgress != null && durationSeconds != null && durationSeconds > 0 && line.startsWith("out_time_us=")) {
                        val seconds = (line.substringAfter('=').toLongOrNull() ?: 0) / 1e6
                        onProgress((seconds / durationSeconds * 100).toFloat().coerceIn(0f, 100f))
                    }
                }
                val exit = process.waitFor()
                stderr.join()
                if (exit != 0) throw FfmpegException(errors.toString().trim().lines().takeLast(3).joinToString(" ").ifBlank { "ffmpeg keluar dengan kode $exit" })
            } finally {
                process.destroy()
            }
        }

    /** Menjalankan ffmpeg dan mengembalikan log stderr (level info), untuk filter analisis seperti silencedetect. */
    suspend fun log(args: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(binary.absolutePath, "-hide_banner", "-nostats") + args).redirectErrorStream(true).start()
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) process.destroy() }
        val text = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw FfmpegException(text.trim().lines().takeLast(3).joinToString(" ").ifBlank { "ffmpeg keluar dengan kode $exit" })
        text
    }

    /**
     * Jeda di rentang klip (padanan `ClipSilence::detect` desktop): ambang senyap dari rata-rata volume klip, lalu
     * silencedetect minimal 0,3 detik. Waktu dalam detik video sumber.
     */
    suspend fun silences(source: File, start: Double, duration: Double): List<Pair<Double, Double>> {
        val input = listOf("-ss", id.clipmi.core.php.Php.numberFormat(start, 3), "-t", id.clipmi.core.php.Php.numberFormat(duration, 3), "-i", source.absolutePath, "-vn")
        val volume = log(input + listOf("-af", "volumedetect", "-f", "null", "-"))
        val mean = Regex("""mean_volume:\s*(-?[\d.]+) dB""").find(volume)?.groupValues?.get(1)?.toDouble() ?: throw FfmpegException("Volume klip tidak terbaca")
        val noise = id.clipmi.core.render.ClipSilence.noiseThreshold(mean)
        val log = log(input + listOf("-af", "silencedetect=noise=${id.clipmi.core.php.Php.numberFormat(noise, 1)}dB:d=${id.clipmi.core.render.ClipSilence.MIN}", "-f", "null", "-"))
        return id.clipmi.core.render.ClipSilence.parse(log, start, start + duration)
    }

    /** Audio mono 16 kHz AAC 48 kbps untuk transkripsi (sama dengan `FFmpeg::extractAudio` desktop). */
    suspend fun extractAudio(source: File, target: File, start: Double, length: Double) {
        target.parentFile?.mkdirs()
        try {
            run(
                listOf(
                    "-y", "-ss", start.toString(), "-t", length.toString(), "-i", source.absolutePath,
                    "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "48k", target.absolutePath,
                ),
            )
            if (!target.isFile) throw FfmpegException("File audio tidak terbentuk")
        } catch (e: FfmpegException) {
            target.delete()
            throw e
        }
    }

    /** Menggabungkan video dan audio hasil yt-dlp tanpa encode ulang (5 detik untuk 177 MB di HP uji). */
    suspend fun mux(video: File, audio: File, target: File) {
        run(listOf("-y", "-i", video.absolutePath, "-i", audio.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", target.absolutePath))
    }
}

/** Informasi video lewat MediaMetadataRetriever (tanpa memanggil proses ffmpeg). */
object MediaInfo {
    /** Lebar dan tinggi tampilan (rotasi sudah diperhitungkan), atau null bila tidak terbaca. */
    fun videoSize(file: File): Pair<Int, Int>? = retrieve(file) {
        val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@retrieve null
        val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@retrieve null
        val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation % 180 != 0) height to width else width to height
    }

    /** Ukuran gambar (lebar, tinggi) tanpa memuat pikselnya. */
    fun imageSize(file: File): Pair<Int, Int>? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    fun hasAudio(file: File): Boolean = retrieve(file) { extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" } ?: true

    /** Frame rate trek video (MediaExtractor), atau null. */
    fun frameRate(file: File): Double? = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.let { format -> runCatching { format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() } }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    fun durationSeconds(file: File): Double? = retrieve(file) {
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
    }

    /** Frame thumbnail pada min(10% durasi, 300 detik), seperti MakeThumbnail desktop; disimpan JPEG lebar ≤640. */
    fun thumbnail(file: File, target: File, durationSeconds: Double?): Boolean = retrieve(file) {
        val at = minOf((durationSeconds ?: 0.0) * 0.1, 300.0)
        val frame = getScaledFrameAtTime((at * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
            ?: getFrameAtTime((at * 1_000_000).toLong())
            ?: return@retrieve false
        target.parentFile?.mkdirs()
        target.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        frame.recycle()
        true
    } ?: false

    private fun <T> retrieve(file: File, block: MediaMetadataRetriever.() -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.block()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
