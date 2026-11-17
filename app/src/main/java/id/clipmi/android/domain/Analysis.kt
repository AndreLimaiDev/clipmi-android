package id.clipmi.android.domain

import android.content.Context
import androidx.room.withTransaction
import id.clipmi.android.data.AiUsageEntity
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ClipSource
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.work.AnalyzeWorker
import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ImportSegment
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.analysis.PromptSegment
import id.clipmi.core.transcript.Timecode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

data class ImportOutcome(val created: Int, val skipped: Int, val errors: List<String>) {
    fun message(): String = "$created kandidat klip ditambahkan" +
        (if (skipped > 0) ", $skipped dilewati karena sudah ada" else "") +
        (if (errors.isNotEmpty()) ", ${errors.size} ditolak" else "") + "."
}

data class Estimate(val tokens: Int, val cost: Double?)

/**
 * Pemilihan momen dengan AI (padanan halaman Klip, ClipImporter, dan AnalyzeWithAi desktop): prompt yang sama untuk
 * mode manual (salin/bagikan ke aplikasi chat AI) dan Mode API, lalu balasan JSON disimpan sebagai kandidat klip.
 */
class Analysis(private val context: Context, private val db: AppDatabase, private val settings: Settings, private val transcripts: Transcripts) {
    private val stringList = ListSerializer(String.serializer())

    /** Satu prompt, atau beberapa bagian bila transkrip panjang. Kosong bila transkrip belum siap. */
    suspend fun prompts(projectId: Long): List<String> {
        val project = db.projects().find(projectId) ?: return emptyList()
        if (project.transcript != TranscriptStatus.Ready) return emptyList()
        val segments = db.segments().forProject(projectId).map { PromptSegment(it.sequence, it.startTime, it.endTime, it.text) }
        if (segments.isEmpty()) return emptyList()
        return PromptBuilder.build(project.title, project.durationSeconds, segments, settings.promptTemplate(), settings.promptMaxTokens())
    }

    /**
     * Perkiraan biaya Mode API: token masuk dari panjang prompt, token keluar ±3.000 per bagian (balasan JSON belasan
     * klip plus pemikiran model). Saat bisa dilanjutkan, hanya bagian yang belum berhasil yang dihitung.
     */
    fun estimate(prompts: List<String>, config: AiConfig?, onlyParts: List<Int>? = null): Estimate {
        val counted = onlyParts?.mapNotNull { prompts.getOrNull(it) } ?: prompts
        val tokens = counted.sumOf { PromptBuilder.estimateTokens(it) }
        return Estimate(tokens, config?.cost(tokens, 3000 * counted.size))
    }

    /**
     * Balasan JSON dari chat AI disimpan sebagai kandidat. Klip baru selalu ditambahkan (kandidat lama tidak dihapus),
     * jadi hasil beberapa bagian prompt bisa ditempel satu per satu; rentang yang sama dengan klip yang ada dilewati.
     * @throws id.clipmi.core.analysis.AnalysisResultException bila balasan tidak berisi JSON klip yang bisa dibaca.
     */
    suspend fun importReply(projectId: Long, reply: String): ImportOutcome {
        val segments = db.segments().forProject(projectId).map {
            ImportSegment(it.sequence, it.startTime, it.endTime, transcripts.decodeWords(it.words).ifEmpty { null })
        }
        val parsed = ClipImporter.parse(reply, segments)
        var created = 0
        var skipped = 0
        db.withTransaction {
            val existing = db.clips().forProject(projectId).toMutableList()
            for (draft in parsed.drafts) {
                val t = ClipImporter.DUPLICATE_TOLERANCE
                if (existing.any { it.startTime in draft.startTime - t..draft.startTime + t && it.endTime in draft.endTime - t..draft.endTime + t }) {
                    skipped++
                    continue
                }
                val seriesId = draft.series?.let { db.series().firstOrCreate(projectId, it) }
                val clip = ClipEntity(
                    projectId = projectId,
                    clipSeriesId = seriesId,
                    partNumber = draft.partNumber,
                    title = draft.title,
                    startTime = draft.startTime,
                    endTime = draft.endTime,
                    score = draft.score,
                    hook = draft.hook,
                    caption = draft.caption,
                    hashtags = Json.encodeToString(stringList, draft.hashtags),
                    keywords = draft.keywords?.let { Json.encodeToString(stringList, it) },
                    reason = draft.reason,
                    reviewStatus = ReviewStatus.Candidate.value,
                    source = ClipSource.AI,
                )
                db.clips().insert(clip)
                existing += clip
                created++
            }
        }
        return ImportOutcome(created, skipped, parsed.errors)
    }

    /** Klip yang dipilih sendiri dari transkrip (segmen awal–akhir); langsung dianggap terpilih, seperti desktop. */
    suspend fun createManualClip(projectId: Long, startSequence: Int, endSequence: Int): Long {
        val segments = db.segments().forProject(projectId).associateBy { it.sequence }
        val first = minOf(startSequence, endSequence)
        val last = maxOf(startSequence, endSequence)
        val start = segments.getValue(first)
        val end = segments.getValue(last)
        val (startTime, endTime) = ClipImporter.rangeTimes(
            ImportSegment(start.sequence, start.startTime, start.endTime, transcripts.decodeWords(start.words).ifEmpty { null }),
            ImportSegment(end.sequence, end.startTime, end.endTime, transcripts.decodeWords(end.words).ifEmpty { null }),
        )
        return db.clips().insert(
            ClipEntity(
                projectId = projectId,
                title = "Klip manual ${Timecode.format(startTime)}",
                startTime = startTime,
                endTime = endTime,
                reviewStatus = ReviewStatus.Selected.value,
                source = ClipSource.MANUAL,
            ),
        )
    }

    fun decodeList(json: String?): List<String> = json?.let { runCatching { Json.decodeFromString(stringList, it) }.getOrNull() }.orEmpty()

    fun encodeList(items: List<String>): String = Json.encodeToString(stringList, items)

    // --- Mode API ------------------------------------------------------------------------------------------------

    /** Menandai analisis antre lalu mengirim pekerjaannya ke antrean AI. */
    suspend fun start(projectId: Long, resume: Boolean = false, message: String = "Menunggu giliran…") {
        db.projects().modify(projectId) {
            it.copy(analysisStatus = AnalysisStatus.QUEUED, analysisMessage = message, analysisHeartbeatAt = System.currentTimeMillis())
        }
        AnalyzeWorker.enqueue(context, projectId, resume)
    }

    /**
     * Setiap kali transkrip baru siap: analisis dimulai sendiri hanya bila diaktifkan, Mode API sudah diatur, dan project
     * belum punya kandidat klip (mengganti transkrip project yang sudah dianalisis tidak ditagih ulang tanpa diminta).
     */
    suspend fun startAutomatically(projectId: Long): Boolean {
        val project = db.projects().find(projectId) ?: return false
        if (!settings.autoAnalyze() || settings.analysis() == null || AnalysisStatus.isActive(project.analysisStatus) ||
            db.clips().count(projectId) > 0 || db.segments().count(projectId) == 0
        ) return false
        start(projectId, message = "Dimulai otomatis setelah transkrip siap. Menunggu giliran…")
        return true
    }

    /**
     * Permintaan yang sedang dikirim tidak bisa ditarik, tetapi pekerjaan berhenti setelah balasannya datang dan tidak
     * mengirim bagian berikutnya.
     */
    suspend fun cancel(projectId: Long) {
        db.projects().modify(projectId) {
            if (AnalysisStatus.isActive(it.analysisStatus)) it.copy(analysisStatus = AnalysisStatus.CANCELLED, analysisMessage = "Analisis dibatalkan.") else it
        }
    }

    suspend fun dismiss(projectId: Long) {
        db.projects().modify(projectId) { if (AnalysisStatus.isActive(it.analysisStatus)) it else it.copy(analysisStatus = null, analysisMessage = null) }
    }

    /** Bagian yang sudah selesai di percobaan sebelumnya untuk prompt yang sama (sidik prompt cocok). */
    suspend fun completedParts(projectId: Long, prompts: List<String>): List<Int> {
        val parts = db.projects().find(projectId)?.analysisParts ?: return emptyList()
        val data = runCatching { Json.parseToJsonElement(parts) as JsonObject }.getOrNull() ?: return emptyList()
        if (data["key"]?.jsonPrimitive?.content != fingerprint(prompts)) return emptyList()
        return (data["done"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
    }

    suspend fun saveParts(projectId: Long, prompts: List<String>, done: List<Int>) {
        val json = buildJsonObject {
            put("key", fingerprint(prompts))
            put("total", prompts.size)
            putJsonArray("done") { done.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }.toString()
        db.projects().modify(projectId) { it.copy(analysisParts = json) }
    }

    /** Sidik prompt: transkrip, template, atau batas panjang yang berubah menghasilkan bagian yang berbeda. */
    fun fingerprint(prompts: List<String>): String =
        MessageDigest.getInstance("SHA-1").digest(prompts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun recordUsage(purpose: String, projectId: Long, provider: String, model: String, inputTokens: Int = 0, outputTokens: Int = 0, audioSeconds: Double = 0.0, cost: Double? = null) {
        val project = db.projects().find(projectId)
        db.aiUsages().insert(
            AiUsageEntity(
                purpose = purpose, projectId = project?.id, projectTitle = project?.title.orEmpty(), provider = provider, model = model,
                inputTokens = inputTokens, outputTokens = outputTokens, audioSeconds = audioSeconds, cost = cost,
            ),
        )
    }
}
