package id.clipmi.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.ClipMiApp
import id.clipmi.android.domain.AiException
import id.clipmi.android.domain.PerformanceReports
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.schedule.PerformancePrompt
import java.util.concurrent.TimeUnit

/** Antrean Analisis Performa lewat Mode API (antrean AI yang sama dengan analisis transkrip). */
object PerformanceQueue {
    const val KEY_REPORT = "report"
    const val KEY_ATTEMPT = "attempt"

    fun analyze(context: Context, reportId: Long, attempt: Int = 1, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<PerformanceWorker>()
            .setInputData(workDataOf(KEY_REPORT to reportId, KEY_ATTEMPT to attempt))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (delaySeconds > 0) {
            request.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            WorkManager.getInstance(context).enqueueUniqueWork("performance-retry-$reportId", ExistingWorkPolicy.REPLACE, request.build())
        } else {
            WorkQueue.AI.enqueue(context, request.build())
        }
    }
}

/**
 * Analisis Performa lewat Mode API (`AnalyzePerformance` desktop): prompt dari angka laporan dikirim ke penyedia AI
 * analisis, hasilnya dibaca dan disimpan. Token yang terpakai dicatat walaupun balasannya tidak terbaca. Server penyedia
 * yang sibuk sesaat dicoba lagi otomatis setelah 30 lalu 90 detik.
 */
class PerformanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as ClipMiApp).container

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.AI, "Analisis performa", "Menunggu balasan AI…", null)

    override suspend fun doWork(): Result {
        val dao = container.database.reports()
        val id = inputData.getLong(PerformanceQueue.KEY_REPORT, 0)
        val attempt = inputData.getInt(PerformanceQueue.KEY_ATTEMPT, 1)
        val report = dao.find(id) ?: return Result.success()
        if (report.status != PerformanceReports.QUEUED) return Result.success()
        fun now() = System.currentTimeMillis()

        val config = container.settings.analysis() ?: run {
            dao.update(report.copy(status = PerformanceReports.FAILED, error = "Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan, atau pakai mode manual.", updatedAt = now()))
            return Result.success()
        }
        val digest = PerformanceReports.parseJson(report.digest) ?: run {
            dao.update(report.copy(status = PerformanceReports.FAILED, error = "Data laporan rusak.", updatedAt = now()))
            return Result.success()
        }
        val running = report.copy(status = PerformanceReports.RUNNING, provider = config.provider.value, model = config.model, updatedAt = now())
        dao.update(running)
        runCatching { setForeground(getForegroundInfo()) }

        val reply = try {
            container.aiClient.complete(config, PerformancePrompt.build(digest))
        } catch (e: AiException) {
            val delay = RETRY_DELAYS.getOrNull(attempt - 1)
            if (e.transient && delay != null) {
                val total = RETRY_DELAYS.size + 1
                dao.update(running.copy(status = PerformanceReports.QUEUED, error = "${e.message} Dicoba lagi otomatis dalam $delay detik (percobaan ${attempt + 1} dari $total).", updatedAt = now()))
                PerformanceQueue.analyze(applicationContext, id, attempt + 1, delay)
                return Result.success()
            }
            dao.update(
                running.copy(
                    status = PerformanceReports.FAILED,
                    error = e.message + if (e.transient) " Sudah dicoba $attempt kali; jalankan lagi nanti, atau ganti model di Pengaturan." else "",
                    updatedAt = now(),
                ),
            )
            return Result.success()
        }

        val cost = config.cost(reply.inputTokens, reply.outputTokens)
        container.performance.recordUsage(config.provider.value, config.model, reply.inputTokens, reply.outputTokens, cost)
        val usage = running.copy(inputTokens = reply.inputTokens, outputTokens = reply.outputTokens, cost = cost, updatedAt = now())
        try {
            dao.update(usage.copy(status = PerformanceReports.DONE, result = PerformancePrompt.parse(reply.text, digest).toString(), error = null))
        } catch (e: AnalysisResultException) {
            dao.update(usage.copy(status = PerformanceReports.FAILED, error = e.message))
        }
        return Result.success()
    }

    companion object {
        val RETRY_DELAYS = listOf(30L, 90L)
    }
}
