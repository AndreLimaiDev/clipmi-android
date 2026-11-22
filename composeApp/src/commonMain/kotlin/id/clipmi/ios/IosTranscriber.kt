package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.android.data.AiUsageEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.core.transcript.Srt
import id.clipmi.core.youtube.TranscriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Penyedia transkripsi API (endpoint audio/transcriptions bergaya OpenAI), sama dengan Android dan desktop. */
enum class TranscriptionProvider(val value: String, val label: String, val baseUrl: String?, val defaultModel: String?, val keyPlaceholder: String) {
    Groq("groq", "Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "gsk_…"),
    OpenAI("openai", "OpenAI", "https://api.openai.com/v1", "whisper-1", "sk-…"),
    Custom("custom", "Kompatibel OpenAI (server Whisper lain)", null, null, "Kosongkan jika tidak perlu");

    val requiresKey: Boolean get() = this != Custom

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

data class TranscriptionConfig(val provider: TranscriptionProvider, val baseUrl: String, val model: String, val apiKey: String?) {
    fun endpoint(): String = baseUrl.trimEnd('/')
}

/** Batas laju/jatah penyedia (HTTP 429); dilanjutkan setelah [retryAfter] detik. */
private class RateLimitException(message: String, val retryAfter: Int) : Exception(message)

/**
 * Transkripsi API di iPhone (padanan Transcriber + TranscribeWorker Android): audio dipotong per 20 menit lewat FFmpeg,
 * tiap potongan dikirim ke penyedia, hasilnya disimpan begitu selesai (transkripsi yang terhenti melanjutkan dari
 * potongan berikutnya), lalu SRT gabungannya diimpor seperti subtitle biasa. Berjalan selama aplikasi terbuka.
 */
class IosTranscriber(
    private val repo: Repository,
    private val engine: MediaEngine,
    private val renderer: IosRenderer,
    private val settings: IosSettings,
    private val analysis: IosAnalysis,
    private val scope: CoroutineScope,
) {
    private val projects get() = repo.db.projects()
    var active by mutableStateOf<Long?>(null)
        private set
    private var job: Job? = null

    fun workDir(projectId: Long) = repo.absolute("tmp/transcribe-$projectId")

    suspend fun config(): TranscriptionConfig? = settings.transcription()

    suspend fun start(projectId: Long, message: String = "Menyiapkan…") {
        val project = projects.find(projectId) ?: return
        if (active != null || project.transcript == TranscriptStatus.Fetching) return
        val previous = project.transcript
        projects.modify(projectId) { it.copy(transcriptStatus = TranscriptStatus.Fetching.value, transcriptionMessage = message, transcriptionResumeAt = null) }
        active = projectId
        job = scope.launch {
            try {
                run(projectId, previous)
            } finally {
                active = null
                job = null
            }
        }
    }

    /** Dibatalkan pengguna: FFmpeg/permintaan yang berjalan dihentikan; potongan yang sudah selesai tetap disimpan. */
    suspend fun cancel(projectId: Long) {
        // Ditunggu sampai benar-benar berhenti supaya pembaruan progres terakhir tidak menimpa status di bawah.
        if (active == projectId) job?.cancelAndJoin()
        val restore = if (repo.db.segments().count(projectId) > 0) TranscriptStatus.Ready else TranscriptStatus.NeedsTranscript
        projects.modify(projectId) { it.copy(transcriptStatus = restore.value, transcriptionMessage = CANCELLED_PREFIX, transcriptionResumeAt = null) }
    }

    /** Setelah video tanpa subtitle selesai diunduh, bila diaktifkan dan Transkripsi API sudah diatur (sama dengan Android). */
    suspend fun startAutomatically(projectId: Long): Boolean {
        val project = projects.find(projectId) ?: return false
        if (!settings.autoTranscribe() || settings.transcription() == null ||
            project.transcript != TranscriptStatus.NeedsTranscript || project.video != VideoStatus.Ready
        ) return false
        start(projectId, "Dimulai otomatis karena video belum punya transkrip.")
        return true
    }

    private suspend fun run(projectId: Long, previous: TranscriptStatus) {
        val project = projects.find(projectId) ?: return
        val config = settings.transcription() ?: return restore(projectId, previous, "Transkripsi API belum diatur. Pilih penyedia dan isi API key di Pengaturan.")
        val video = project.videoPath?.let { repo.absolute(it) }
        if (video == null || project.video != VideoStatus.Ready || fileSize(video) <= 0) {
            return restore(projectId, previous, "Video sumber tidak ada. Unduh ulang videonya dulu.")
        }
        val duration = project.durationSeconds?.takeIf { it > 0 } ?: CHUNK_SECONDS.toDouble()
        val dir = workDir(projectId)
        var sent = 0.0
        var waits = 0
        try {
            while (true) {
                try {
                    val srt = transcribe(config, video, duration, dir, onProgress = { done, total ->
                        val message = if (total > 1) "Mentranskripsi potongan ${minOf(done + 1, total)} dari $total (per 20 menit)…" else "Mentranskripsi audio…"
                        projects.modify(projectId) { it.copy(transcriptionMessage = message) }
                    }, onChunkSent = { sent += it })
                    val count = repo.importTranscript(projectId, srt.encodeToByteArray(), TranscriptSource.Api.value, "id")
                    projects.modify(projectId) { it.copy(transcriptionMessage = "$DONE_PREFIX ${config.provider.label}: $count segmen.", transcriptionResumeAt = null) }
                    if (analysis.startAutomatically(projectId)) {
                        projects.modify(projectId) { it.copy(transcriptionMessage = it.transcriptionMessage + " Analisis otomatis dimulai di tab Klip.") }
                    }
                    return
                } catch (e: RateLimitException) {
                    // Jatah per jam/hari (misalnya paket gratis Groq): ditunggu di sini selama aplikasi terbuka.
                    if (waits >= MAX_WAITS) return restore(projectId, previous, "${e.message} Sudah menunggu $MAX_WAITS kali, jadi transkripsi dihentikan." + savedNote(dir, duration))
                    waits++
                    val wait = minOf(3600, e.retryAfter + 5)
                    projects.modify(projectId) {
                        it.copy(
                            transcriptionResumeAt = nowMillis() + wait * 1000L,
                            transcriptionMessage = "${e.message} Dilanjutkan otomatis dalam ${(wait + 59) / 60} menit selama aplikasi tetap terbuka." + savedNote(dir, duration),
                        )
                    }
                    delay(wait * 1000L)
                    projects.modify(projectId) { it.copy(transcriptionResumeAt = null) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            restore(projectId, previous, (e.message ?: "Transkripsi gagal.") + savedNote(dir, duration))
        } finally {
            // Tetap dicatat walau dibatalkan: audio yang sudah terkirim tetap ditagih penyedia.
            if (sent > 0) withContext(NonCancellable) {
                repo.db.aiUsages().insert(
                    AiUsageEntity(
                        purpose = "transcription", projectId = project.id, projectTitle = project.title, provider = config.provider.value, model = config.model,
                        audioSeconds = sent,
                    ),
                )
            }
        }
    }

    private suspend fun restore(projectId: Long, previous: TranscriptStatus, message: String) {
        val status = if (previous == TranscriptStatus.Fetching) TranscriptStatus.NeedsTranscript else previous
        projects.modify(projectId) { it.copy(transcriptStatus = status.value, transcriptionMessage = message, transcriptionResumeAt = null) }
    }

    /** Misalnya " 6 dari 9 potongan sudah selesai dan tidak dikirim ulang." */
    private fun savedNote(dir: String, duration: Double): String {
        val done = completedChunks(dir)
        val total = maxOf(1, ceil(duration / CHUNK_SECONDS).toInt())
        return if (done > 0) " $done dari $total potongan sudah selesai dan tidak dikirim ulang." else ""
    }

    private fun completedChunks(dir: String) = listDirectory(dir).count { it.startsWith("chunk-") && it.endsWith(".json") }

    private suspend fun transcribe(
        config: TranscriptionConfig,
        video: String,
        duration: Double,
        dir: String,
        onProgress: suspend (Int, Int) -> Unit,
        onChunkSent: (Double) -> Unit,
    ): String {
        val chunks = maxOf(1, ceil(duration / CHUNK_SECONDS).toInt())
        prepareWorkDir(dir, video, duration)
        val cues = mutableListOf<Srt.Line>()
        for (i in 0 until chunks) {
            val number = i.toString().padStart(3, '0')
            val saved = "$dir/chunk-$number.json"
            if (readText(saved) == null) {
                onProgress(i, chunks)
                val offset = (i * CHUNK_SECONDS).toDouble()
                val audio = "$dir/audio-$number.m4a"
                extractAudio(video, audio, dir, offset)
                val chunkCues = try {
                    request(config, audio).mapNotNull { segment ->
                        val text = (segment["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        val start = (segment["start"] as? JsonPrimitive)?.doubleOrNull
                        val end = (segment["end"] as? JsonPrimitive)?.doubleOrNull
                        if (text.isEmpty() || start == null || end == null) null else Srt.Line(offset + start, offset + end, text)
                    }
                } finally {
                    removeItem(audio)
                }
                writeText(
                    saved,
                    buildJsonArray {
                        chunkCues.forEach { add(buildJsonArray { add(JsonPrimitive(it.start)); add(JsonPrimitive(it.end)); add(JsonPrimitive(it.text)) }) }
                    }.toString(),
                )
                onChunkSent(maxOf(0.0, minOf(CHUNK_SECONDS.toDouble(), duration - offset)))
            }
            Json.parseToJsonElement(readText(saved) ?: "[]").jsonArray.forEach {
                val row = it.jsonArray
                cues += Srt.Line(row[0].jsonPrimitive.doubleOrNull ?: 0.0, row[1].jsonPrimitive.doubleOrNull ?: 0.0, row[2].jsonPrimitive.content)
            }
        }
        onProgress(chunks, chunks)
        removeItem(dir)
        if (cues.isEmpty()) throw Exception("Penyedia tidak mengembalikan teks apa pun. Pastikan video berisi suara.")
        return Srt.write(cues)
    }

    /** Hasil lama hanya dipakai bila video dan durasinya sama; selain itu folder kerja dikosongkan. */
    private fun prepareWorkDir(dir: String, video: String, duration: Double) {
        val manifest = "$dir/manifest.json"
        val expected = """{"video":"$video","size":${fileSize(video)},"duration":${(duration * 10).roundToLong()},"chunk":$CHUNK_SECONDS}"""
        if (readText(manifest) == expected) return
        removeItem(dir)
        createDirectory(dir)
        writeText(manifest, expected)
    }

    /** AAC mono 16 kHz 48 kbps (sama dengan Android): 20 menit ≈ 7 MB. */
    private suspend fun extractAudio(video: String, audio: String, dir: String, offset: Double) {
        val args = listOf(
            "-y", "-ss", offset.toString(), "-t", CHUNK_SECONDS.toString(), "-i", video,
            "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "48k", audio,
        )
        val (code, log) = renderer.runFfmpeg(args, dir, CHUNK_SECONDS.toDouble()) {}
        if (code != 0 || fileSize(audio) <= 0) {
            removeItem(audio)
            throw Exception("Audio tidak bisa diambil dari video: " + log.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(200))
        }
    }

    private suspend fun upload(config: TranscriptionConfig, audio: String): HttpResult = suspendCancellableCoroutine { cont ->
        val headers = buildMap {
            put("Accept", "application/json")
            config.apiKey?.let { put("Authorization", "Bearer $it") }
        }
        // verbose_json berisi segmen bertimestamp; didukung OpenAI whisper-1, Groq, dan server Whisper lain.
        val fields = buildJsonArray {
            listOf("model" to config.model, "language" to "id", "response_format" to "verbose_json", "timestamp_granularities[]" to "segment").forEach { (k, v) ->
                add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) })
            }
        }
        engine.uploadFile(
            config.endpoint() + "/audio/transcriptions", JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString(), fields.toString(),
            audio, "file", "audio/mp4", REQUEST_TIMEOUT,
            object : HttpListener {
                override fun finished(status: Int, body: String, retryAfter: String?, error: String?) {
                    if (cont.isActive) cont.resume(HttpResult(status, body, retryAfter, error))
                }
            },
        )
    }

    private suspend fun request(config: TranscriptionConfig, audio: String): List<JsonObject> {
        var attempt = 1
        while (true) {
            val response = upload(config, audio)
            if (response.error != null) {
                throw Exception(
                    if (response.error == "timeout") "${config.provider.label} tidak membalas dalam 10 menit, jadi permintaan dihentikan. Server penyedia mungkin sedang sibuk; coba lagi beberapa saat lagi."
                    else "Tidak bisa terhubung ke ${config.provider.label}. Periksa koneksi internet atau alamat API.",
                )
            }
            if (response.status in 200..299) return segments(response.body)
            val retry = response.retryAfter?.toIntOrNull() ?: 0
            val wait = maxOf(2, retry)
            // Batas laju panjang (jatah audio per jam/hari): ditunggu lebih lama oleh pemanggil.
            if (response.status == 429 && (wait > MAX_INLINE_WAIT || attempt >= 2)) {
                throw RateLimitException("Batas pemakaian ${config.provider.label} tercapai.", if (retry > 0) retry else DEFAULT_WAIT)
            }
            if (attempt >= 2 || !(response.status == 429 || response.status >= 500)) throw Exception(errorMessage(response.status, response.body, config.provider))
            delay(minOf(MAX_INLINE_WAIT, wait) * 1000L)
            attempt++
        }
    }

    private fun segments(body: String): List<JsonObject> {
        val segments = runCatching { Json.parseToJsonElement(body).jsonObject["segments"] }.getOrNull() as? JsonArray
            ?: throw Exception("Model ini tidak mengembalikan timestamp per kalimat. Pilih model Whisper (misalnya whisper-1 atau whisper-large-v3-turbo).")
        return segments.mapNotNull { it as? JsonObject }
    }

    private fun errorMessage(code: Int, body: String, provider: TranscriptionProvider): String {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val detailText = ((json?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (json?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: body.trim().take(200)
        val detail = if (detailText.isNotEmpty()) " ($detailText)" else ""
        val message = when {
            code == 403 && detailText.contains("model", ignoreCase = true) -> "Model ini tidak diizinkan untuk akun/project ${provider.label}. Pilih model lain atau izinkan model itu di konsol penyedia."
            code == 401 || code == 403 -> "API key ditolak oleh ${provider.label}. Periksa key di Pengaturan."
            code == 404 -> "Model atau alamat API tidak ditemukan. Periksa nama model di Pengaturan."
            code == 413 -> "Data yang dikirim terlalu besar untuk penyedia ini."
            code == 429 -> "Batas pemakaian atau kuota ${provider.label} tercapai. Coba lagi nanti atau periksa saldo akun."
            code >= 500 -> "Server ${provider.label} sedang bermasalah (HTTP $code). Coba lagi beberapa saat lagi."
            else -> "Permintaan ditolak oleh ${provider.label} (HTTP $code)."
        }
        return message + detail
    }

    companion object {
        /** Panjang tiap potongan audio (sama dengan Android). */
        const val CHUNK_SECONDS = 1200
        private const val REQUEST_TIMEOUT = 600
        private const val MAX_INLINE_WAIT = 60
        private const val DEFAULT_WAIT = 600
        private const val MAX_WAITS = 12

        /** Awal pesan hasil (sama dengan Android), dipakai kartu status untuk mewarnai pesan. */
        const val DONE_PREFIX = "Transkrip dibuat lewat"
        const val CANCELLED_PREFIX = "Transkripsi dibatalkan."
    }
}
