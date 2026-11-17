package id.clipmi.android.domain

import id.clipmi.core.transcript.Srt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

open class AiException(message: String, val transient: Boolean = false) : Exception(message)

/** Batas laju/jatah penyedia (HTTP 429); bisa dilanjutkan setelah [retryAfter] detik. */
class AiRateLimitException(message: String, val retryAfter: Int) : AiException(message)

/**
 * Transkripsi lewat endpoint audio/transcriptions bergaya OpenAI (Groq Whisper, OpenAI Whisper, server Whisper lain),
 * padanan `App\Ai\Transcriber` desktop. Audio dipotong per 20 menit (±7 MB), hasil tiap potongan disimpan di folder
 * kerja begitu selesai, jadi transkripsi yang terhenti melanjutkan dari potongan berikutnya.
 */
class Transcriber(private val ffmpeg: Ffmpeg) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    /**
     * @param onProgress (potongan selesai, jumlah potongan)
     * @param onChunkSent panjang audio tiap potongan yang berhasil ditranskripsi (dan ditagih penyedia)
     * @return isi SRT
     */
    suspend fun transcribe(
        config: TranscriptionConfig,
        video: File,
        duration: Double,
        workDir: File,
        onProgress: suspend (Int, Int) -> Unit,
        onChunkSent: (Double) -> Unit = {},
    ): String {
        val chunks = maxOf(1, ceil(duration / CHUNK_SECONDS).toInt())
        prepareWorkDir(workDir, video, duration)
        val cues = mutableListOf<Srt.Line>()

        for (i in 0 until chunks) {
            val saved = File(workDir, "chunk-%03d.json".format(Locale.ROOT, i))
            if (!saved.isFile) {
                onProgress(i, chunks)
                val offset = (i * CHUNK_SECONDS).toDouble()
                val audio = File(workDir, "audio-%03d.m4a".format(Locale.ROOT, i))
                ffmpeg.extractAudio(video, audio, offset, CHUNK_SECONDS.toDouble())
                val chunkCues = try {
                    request(config, audio).mapNotNull { segment ->
                        val text = (segment["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        val start = (segment["start"] as? JsonPrimitive)?.doubleOrNull
                        val end = (segment["end"] as? JsonPrimitive)?.doubleOrNull
                        if (text.isEmpty() || start == null || end == null) null else Srt.Line(offset + start, offset + end, text)
                    }
                } finally {
                    audio.delete()
                }
                saved.writeText(
                    buildJsonArray {
                        chunkCues.forEach { add(buildJsonArray { add(JsonPrimitive(it.start)); add(JsonPrimitive(it.end)); add(JsonPrimitive(it.text)) }) }
                    }.toString(),
                )
                onChunkSent(maxOf(0.0, minOf(CHUNK_SECONDS.toDouble(), duration - offset)))
            }
            Json.parseToJsonElement(saved.readText()).jsonArray.forEach {
                val row = it.jsonArray
                cues += Srt.Line(row[0].jsonPrimitive.doubleOrNull ?: 0.0, row[1].jsonPrimitive.doubleOrNull ?: 0.0, row[2].jsonPrimitive.content)
            }
        }
        onProgress(chunks, chunks)
        workDir.deleteRecursively()
        if (cues.isEmpty()) throw AiException("Penyedia tidak mengembalikan teks apa pun. Pastikan video berisi suara.")
        return Srt.write(cues)
    }

    /** Hasil lama hanya dipakai bila video dan durasinya sama; selain itu folder kerja dikosongkan. */
    private fun prepareWorkDir(workDir: File, video: File, duration: Double) {
        val manifest = File(workDir, "manifest.json")
        val expected = """{"video":"${video.absolutePath}","size":${video.length()},"duration":"${"%.1f".format(Locale.ROOT, duration)}","chunk":$CHUNK_SECONDS}"""
        if (manifest.isFile && manifest.readText() == expected) return
        workDir.deleteRecursively()
        workDir.mkdirs()
        manifest.writeText(expected)
    }

    private suspend fun request(config: TranscriptionConfig, audio: File): List<JsonObject> = withContext(Dispatchers.IO) {
        var attempt = 1
        while (true) {
            // verbose_json berisi segmen bertimestamp; didukung OpenAI whisper-1, Groq, dan server Whisper lain.
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", config.model)
                .addFormDataPart("language", "id")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build()
            val request = Request.Builder().url(config.endpoint() + "/audio/transcriptions").post(body)
                .header("Accept", "application/json")
                .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (_: InterruptedIOException) {
                throw AiException("${config.provider.label} tidak membalas dalam 10 menit, jadi permintaan dihentikan. Server penyedia mungkin sedang sibuk; coba lagi beberapa saat lagi.")
            } catch (_: IOException) {
                throw AiException("Tidak bisa terhubung ke ${config.provider.label}. Periksa koneksi internet atau alamat API.")
            }

            response.use {
                val text = it.body.string()
                if (it.isSuccessful) return@withContext segments(text)

                val wait = maxOf(2, it.header("retry-after")?.toIntOrNull() ?: 0)
                // Batas laju panjang (jatah audio per jam/hari): diserahkan ke pemanggil untuk dijadwalkan ulang.
                if (it.code == 429 && (wait > MAX_INLINE_WAIT || attempt >= 2)) {
                    val retry = it.header("retry-after")?.toIntOrNull() ?: 0
                    throw AiRateLimitException("Batas pemakaian ${config.provider.label} tercapai.", if (retry > 0) retry else DEFAULT_WAIT)
                }
                if (attempt >= 2 || !(it.code == 429 || it.code >= 500)) throw fromResponse(it, text, config.provider)
                delay(minOf(MAX_INLINE_WAIT, wait) * 1000L)
            }
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

    private fun segments(body: String): List<JsonObject> {
        val segments = runCatching { Json.parseToJsonElement(body).jsonObject["segments"] }.getOrNull() as? JsonArray
            ?: throw AiException("Model ini tidak mengembalikan timestamp per kalimat. Pilih model Whisper (misalnya whisper-1 atau whisper-large-v3-turbo).")
        return segments.mapNotNull { it as? JsonObject }
    }

    private fun fromResponse(response: Response, body: String, provider: TranscriptionProvider): AiException {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val detailText = ((json?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (json?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: body.trim().take(200)
        val detail = if (detailText.isNotEmpty()) " ($detailText)" else ""
        val code = response.code
        val message = when {
            // 403 juga dipakai penyedia untuk model yang diblokir di akun/project (misalnya pengaturan project Groq).
            code == 403 && detailText.contains("model", ignoreCase = true) -> "Model ini tidak diizinkan untuk akun/project ${provider.label}. Pilih model lain atau izinkan model itu di konsol penyedia."
            code == 401 || code == 403 -> "API key ditolak oleh ${provider.label}. Periksa key di Pengaturan."
            code == 404 -> "Model atau alamat API tidak ditemukan. Periksa nama model di Pengaturan."
            code == 413 -> "Data yang dikirim terlalu besar untuk penyedia ini."
            code == 429 -> "Batas pemakaian atau kuota ${provider.label} tercapai. Coba lagi nanti atau periksa saldo akun."
            code >= 500 -> "Server ${provider.label} sedang bermasalah (HTTP $code). Coba lagi beberapa saat lagi."
            else -> "Permintaan ditolak oleh ${provider.label} (HTTP $code)."
        }
        return AiException(message + detail, transient = code >= 500)
    }

    companion object {
        /** Panjang tiap potongan audio. AAC mono 48 kbps × 20 menit ≈ 7 MB. */
        const val CHUNK_SECONDS = 1200

        /** Batas laju lebih lama dari ini (misalnya jatah per jam paket gratis Groq) tidak ditunggu di dalam pekerjaan. */
        private const val MAX_INLINE_WAIT = 60

        /** Bila penyedia tidak memberi header retry-after. */
        const val DEFAULT_WAIT = 600

        fun completedChunks(workDir: File): Int = workDir.listFiles { f -> f.name.startsWith("chunk-") && f.name.endsWith(".json") }?.size ?: 0
    }
}
