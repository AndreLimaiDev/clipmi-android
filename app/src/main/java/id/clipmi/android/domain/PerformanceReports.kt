package id.clipmi.android.domain

import android.content.Context
import id.clipmi.android.data.AiUsageEntity
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.PerformanceReportEntity
import id.clipmi.android.work.PerformanceQueue
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.schedule.DigestPost
import id.clipmi.core.schedule.PerformanceDigest
import id.clipmi.core.schedule.PerformancePrompt
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatPoint
import id.clipmi.core.schedule.TargetStatus
import id.clipmi.core.schedule.build
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Analisis Performa AI (sheet Analisis di halaman Jadwal desktop): angka diolah aplikasi (`PerformanceDigest`), lalu
 * ditafsirkan AI lewat Mode API (antrean AI) atau chat AI (prompt disalin, balasan ditempel). Laporan disimpan bertanggal.
 */
class PerformanceReports(
    private val app: Context,
    private val db: AppDatabase,
    private val renderer: Renderer,
    private val analysis: Analysis,
    private val settings: Settings,
) {
    /** Angka untuk analisis berikutnya, dari postingan terbit yang punya statistik tayangan. */
    suspend fun digest(): JsonObject {
        val dao = db.schedules()
        val accounts = dao.accounts().associateBy { it.id }
        val schedules = dao.schedules().associateBy { it.id }
        val stats = dao.allStats().groupBy { it.scheduleAccountId }
        val clips = HashMap<Long, Pair<id.clipmi.android.data.ClipEntity, id.clipmi.core.render.ClipSpec?>>()
        val rows = dao.allTargets().filter { it.status == TargetStatus.PUBLISHED && it.publishedAt != null }.mapNotNull { row ->
            val schedule = schedules[row.scheduleId] ?: return@mapNotNull null
            val account = accounts[row.socialAccountId] ?: return@mapNotNull null
            val (clip, spec) = clips.getOrPut(schedule.clipId) {
                val clip = db.clips().find(schedule.clipId) ?: return@mapNotNull null
                clip to db.projects().find(clip.projectId)?.let { renderer.spec(clip, it) }
            }
            val project = db.projects().find(clip.projectId)
            val hookModel = clip.hookLayout?.let { runCatching { (Json.parseToJsonElement(it).jsonObject["model"] as? JsonPrimitive)?.contentOrNull }.getOrNull() }
            DigestPost(
                rowId = row.id,
                scheduleId = schedule.id,
                accountId = account.id,
                account = account.name,
                platform = Platforms.label(account.platform),
                publishedAt = Wall.of(row.publishedAt!!).toKotlinLocalDateTime(),
                stats = stats[row.id].orEmpty().sortedBy { it.recordedAt }.map {
                    StatPoint(Wall.of(it.recordedAt), StatNumbers(it.views, it.likes, it.comments, it.shares))
                },
                title = id.clipmi.core.analysis.ClipText.displayTitle(clip.title, clip.partNumber),
                hook = spec?.shownHook(),
                hookModel = hookModel,
                finalDuration = spec?.finalDuration() ?: clip.duration,
                lead = spec?.leadKind(),
                series = clip.clipSeriesId != null,
                keywords = analysis.decodeList(clip.keywords),
                project = project?.title.orEmpty(),
                caption = Schedules.effectiveCaption(schedule.caption, clip.caption),
                hashtags = Schedules.effectiveHashtags(schedule.hashtags?.let(analysis::decodeList), analysis.decodeList(clip.hashtags)).size,
            )
        }
        return PerformanceDigest.build(rows, Wall.now())
    }

    /**
     * Analisis lewat Mode API: angka diolah sekarang dan disimpan bersama laporannya, lalu dikirim di antrean AI.
     * Mengembalikan id laporan, atau pesan kesalahan.
     */
    suspend fun runApi(): Result<Long> {
        if (settings.analysis() == null) return Result.failure(IllegalStateException("Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan, atau pakai mode manual."))
        if (db.reports().all().any { it.isBusy }) return Result.failure(IllegalStateException("Analisis sebelumnya masih berjalan."))
        val digest = digest()
        val count = PerformanceDigest.postCount(digest)
        if (count < PerformanceDigest.MIN_POSTS) return Result.failure(IllegalStateException("Butuh minimal ${PerformanceDigest.MIN_POSTS} postingan yang sudah punya statistik."))
        val id = db.reports().insert(PerformanceReportEntity(status = QUEUED, source = "api", postsCount = count, digest = digest.toString()))
        PerformanceQueue.analyze(app, id)
        return Result.success(id)
    }

    /** Mode manual: balasan chat AI untuk prompt yang disalin dibaca dan disimpan sebagai laporan. */
    suspend fun saveManual(reply: String): Result<Long> {
        if (reply.isBlank()) return Result.failure(AnalysisResultException("Tempel balasan dari chat AI dulu."))
        val digest = digest()
        val result = try {
            PerformancePrompt.parse(reply, digest)
        } catch (e: AnalysisResultException) {
            return Result.failure(e)
        }
        return Result.success(
            db.reports().insert(
                PerformanceReportEntity(status = DONE, source = "manual", postsCount = PerformanceDigest.postCount(digest), digest = digest.toString(), result = result.toString()),
            ),
        )
    }

    suspend fun delete(id: Long) = db.reports().delete(id)

    /** Pemakaian API dicatat di Pemakaian AI (judul sama dengan desktop). */
    suspend fun recordUsage(provider: String, model: String, inputTokens: Int, outputTokens: Int, cost: Double?) {
        if (inputTokens + outputTokens == 0) return
        db.aiUsages().insert(
            AiUsageEntity(purpose = "analysis", projectId = null, projectTitle = USAGE_TITLE, provider = provider, model = model, inputTokens = inputTokens, outputTokens = outputTokens, cost = cost),
        )
    }

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val USAGE_TITLE = "Analisis performa postingan"

        fun parseJson(text: String?): JsonObject? = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    }
}
