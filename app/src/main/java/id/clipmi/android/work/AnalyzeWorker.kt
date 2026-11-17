package id.clipmi.android.work

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.domain.AiException
import id.clipmi.core.analysis.AnalysisResultException
import java.text.NumberFormat
import java.util.Locale

/**
 * Mode API: prompt yang sama dengan mode manual dikirim ke penyedia AI, balasannya disimpan lewat validasi yang sama
 * dengan "Tempel hasil" (padanan AnalyzeWithAi desktop). Transkrip panjang dikirim per bagian; bagian yang sudah
 * berhasil dicatat sehingga analisis yang terhenti bisa dilanjutkan tanpa membayar ulang.
 */
class AnalyzeWorker(context: Context, params: WorkerParameters) : ProjectWorker(context, params, WorkQueue.AI) {
    private val resume = inputData.getBoolean(KEY_RESUME, false)
    private val analysis get() = container.analysis

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.AI, "Analisis AI", "Menyiapkan…", null)

    override suspend fun doWork(): Result {
        val project = projects.find(projectId) ?: return Result.success()
        // Dibatalkan pengguna selagi antre. RUNNING berarti pekerjaan sebelumnya terputus (proses dimatikan sistem):
        // dilanjutkan dari bagian yang belum selesai.
        if (!AnalysisStatus.isActive(project.analysisStatus)) return Result.success()
        val interrupted = project.analysisStatus == AnalysisStatus.RUNNING

        val config = container.settings.analysis()
            ?: return status(AnalysisStatus.FAILED, "Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan.")

        val prompts = analysis.prompts(projectId)
        if (prompts.isEmpty()) return status(AnalysisStatus.FAILED, "Transkrip belum siap, jadi belum ada yang bisa dianalisis.")
        val total = prompts.size
        val done = (if (resume || interrupted) analysis.completedParts(projectId, prompts) else emptyList()).toMutableList()
        var created = 0
        var skipped = 0
        var inputTokens = 0
        var outputTokens = 0
        val problems = mutableListOf<String>()
        fun label(i: Int) = if (total > 1) "Bagian ${i + 1} dari $total" else null

        // Token yang sudah dibalas tetap ditagih penyedia walau analisis berhenti di tengah, jadi dicatat di setiap
        // jalan keluar (selesai, gagal, dibatalkan).
        try {
            for ((i, prompt) in prompts.withIndex()) {
                if (i in done) continue
                if (cancelled()) break

                val waiting = "Menunggu balasan ${config.provider.label}" + (label(i)?.let { " (${it.replaceFirstChar(Char::lowercase)})" } ?: "") + "…"
                status(AnalysisStatus.RUNNING, waiting)
                notify(project.title, waiting, if (total > 1) i * 100 / total else null)

                val reply = try {
                    container.aiClient.complete(config, prompt)
                } catch (e: AiException) {
                    // Kesalahan API (key, kuota, server) kemungkinan terulang di bagian berikutnya: berhenti di sini.
                    analysis.saveParts(projectId, prompts, done)
                    if (cancelled()) return Result.success()
                    return status(
                        AnalysisStatus.FAILED,
                        listOfNotNull(
                            (label(i)?.let { "$it: " } ?: "") + e.message,
                            if (done.isNotEmpty()) "${done.size} dari $total bagian sudah selesai dan tidak perlu diulang." else null,
                            if (created > 0) "$created kandidat baru sudah tersimpan." else null,
                        ).joinToString(" "),
                    )
                }

                inputTokens += reply.inputTokens
                outputTokens += reply.outputTokens
                try {
                    val result = analysis.importReply(projectId, reply.text)
                    created += result.created
                    skipped += result.skipped
                    problems += result.errors
                    done += i
                } catch (e: AnalysisResultException) {
                    // Balasan yang tidak bisa dibaca: bagian ini tidak dihitung selesai, supaya bisa diulang.
                    problems += (label(i)?.let { "$it: " } ?: "") + e.message
                }
                analysis.saveParts(projectId, prompts, done)
            }

            val remaining = total - done.size
            val cost = config.cost(inputTokens, outputTokens)

            if (cancelled()) {
                return status(
                    AnalysisStatus.CANCELLED,
                    "Analisis dibatalkan." +
                        (if (created > 0) " $created kandidat dari balasan yang sudah diterima tetap disimpan." else "") +
                        (if (done.isNotEmpty() && remaining > 0) " ${done.size} dari $total bagian sudah selesai; sisanya bisa dilanjutkan." else ""),
                )
            }

            val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id"))
            return status(
                if (remaining == 0) AnalysisStatus.DONE else AnalysisStatus.FAILED,
                listOfNotNull(
                    "$created kandidat klip ditambahkan" + (if (skipped > 0) ", $skipped dilewati karena sudah ada" else "") + ".",
                    if (problems.isNotEmpty()) "${problems.size} ditolak: ${problems.take(3).joinToString("; ")}${if (problems.size > 3) "; …" else ""}." else null,
                    if (remaining > 0) "$remaining dari $total bagian belum berhasil; ketuk Lanjutkan untuk mengulang bagian itu saja." else null,
                    "${number.format(inputTokens)} token masuk, ${number.format(outputTokens)} token keluar" +
                        (cost?.let { " (sekitar $" + "%.${if (it < 0.1) 4 else 2}f".format(Locale.ROOT, it) + ")" } ?: "") + ".",
                ).joinToString(" "),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return status(AnalysisStatus.FAILED, "Analisis gagal: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (inputTokens > 0 || outputTokens > 0) {
                analysis.recordUsage("analysis", projectId, config.provider.value, config.model, inputTokens, outputTokens, cost = config.cost(inputTokens, outputTokens))
            }
        }
    }

    /** Dibaca langsung dari database: tombol Batalkan mengubahnya selagi pekerjaan berjalan. */
    private suspend fun cancelled() = projects.find(projectId)?.analysisStatus == AnalysisStatus.CANCELLED

    private suspend fun status(status: String, message: String): Result {
        update { it.copy(analysisStatus = status, analysisMessage = message, analysisHeartbeatAt = System.currentTimeMillis()) }
        return Result.success()
    }

    companion object {
        private const val KEY_RESUME = "resume"

        fun enqueue(context: Context, projectId: Long, resume: Boolean) = WorkQueue.AI.enqueue(
            context,
            OneTimeWorkRequestBuilder<AnalyzeWorker>()
                .setInputData(workDataOf(ProjectQueue.KEY_PROJECT to projectId, KEY_RESUME to resume))
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .addTag(ProjectQueue.tag(projectId))
                .build(),
        )
    }
}
