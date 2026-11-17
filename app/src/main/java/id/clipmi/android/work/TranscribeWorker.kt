package id.clipmi.android.work

import android.content.Context
import androidx.work.WorkerParameters
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.domain.AiException
import id.clipmi.android.domain.AiRateLimitException
import id.clipmi.android.domain.FfmpegException
import id.clipmi.android.domain.Transcriber
import id.clipmi.core.transcript.TranscriptParseException
import id.clipmi.core.youtube.TranscriptSource
import kotlin.math.ceil

/**
 * Transkripsi API untuk video tanpa subtitle, atau untuk mengganti subtitle otomatis YouTube yang buruk (padanan
 * `TranscribeWithApi` desktop). Selama berjalan status transkrip "Mengambil"; bila gagal, status dan transkrip
 * sebelumnya dipulihkan. Batas pemakaian penyedia (misalnya jatah audio per jam Groq gratis) tidak ditunggu di
 * antrean: pekerjaan dijadwalkan ulang setelah batasnya pulih dan melanjutkan dari potongan berikutnya.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : ProjectWorker(context, params, WorkQueue.AI) {
    private val previous = TranscriptStatus.from(inputData.getString(ProjectQueue.KEY_PREVIOUS) ?: TranscriptStatus.NeedsTranscript.value)
    private val waits = inputData.getInt(ProjectQueue.KEY_WAITS, 0)

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.AI, "Transkripsi", "Menyiapkan…", null)

    override suspend fun doWork(): Result {
        val project = projects.find(projectId) ?: return Result.success()
        // Dibatalkan pengguna selama menunggu batas pemakaian pulih.
        if (project.transcript != TranscriptStatus.Fetching) return Result.success()
        update { it.copy(transcriptionResumeAt = null) }

        val config = container.settings.transcription()
        val video = container.workspace.file(project.videoPath)
        if (config == null) return restore("Transkripsi API belum diatur. Pilih penyedia dan isi API key di Pengaturan.")
        if (video == null || !video.isFile) return restore("Video sumber tidak ada di folder kerja. Pulihkan videonya dulu.")

        val duration = project.durationSeconds ?: 0.0
        val workDir = container.workspace.transcribeDir(projectId)
        notify(project.title, "Mentranskripsi audio…")
        try {
            val srt = container.transcriber.transcribe(
                config,
                video,
                if (duration > 0) duration else Transcriber.CHUNK_SECONDS.toDouble(),
                workDir,
                onProgress = { done, total ->
                    val message = if (total > 1) "Mentranskripsi potongan ${minOf(done + 1, total)} dari $total (per 20 menit)…" else "Mentranskripsi audio…"
                    update { it.copy(transcriptionMessage = message) }
                    notify(project.title, message, if (total > 1) done * 100 / total else null)
                },
            )
            val result = container.transcripts.import(projectId, srt.toByteArray(), TranscriptSource.Api, "id")
            update {
                it.copy(
                    transcriptionMessage = "$DONE_PREFIX ${config.provider.label}: ${result.segmentCount} segmen" +
                        (if (result.correctionCount > 0) ", ${result.correctionCount} kata dikoreksi kamus" else "") + ".",
                )
            }
        } catch (e: AiRateLimitException) {
            return waitForQuota(e, duration)
        } catch (e: FfmpegException) {
            return restore("Audio tidak bisa diambil dari video: ${e.message}" + savedNote(duration))
        } catch (e: AiException) {
            return restore((e.message ?: "Transkripsi gagal.") + savedNote(duration))
        } catch (e: TranscriptParseException) {
            return restore((e.message ?: "Transkripsi gagal.") + savedNote(duration))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return restore("Transkripsi gagal: ${e.message ?: e.javaClass.simpleName}" + savedNote(duration))
        }
        return Result.success()
    }

    private suspend fun waitForQuota(e: AiRateLimitException, duration: Double): Result {
        if (waits >= MAX_WAITS) {
            return restore("${e.message} Sudah menunggu $MAX_WAITS kali, jadi transkripsi dihentikan." + savedNote(duration))
        }
        val delay = minOf(3600, e.retryAfter + 5).toLong()
        update {
            it.copy(
                transcriptionResumeAt = System.currentTimeMillis() + delay * 1000,
                transcriptionMessage = "${e.message} Transkripsi dilanjutkan otomatis setelah batasnya pulih." + savedNote(duration),
            )
        }
        ProjectQueue.transcribe(applicationContext, projectId, previous, waits + 1, delay)
        return Result.success()
    }

    /** Misalnya " 6 dari 9 potongan sudah selesai dan tidak dikirim ulang." */
    private fun savedNote(duration: Double): String {
        val done = Transcriber.completedChunks(container.workspace.transcribeDir(projectId))
        val total = maxOf(1, ceil(duration / Transcriber.CHUNK_SECONDS).toInt())
        return if (done > 0) " $done dari $total potongan sudah selesai dan tidak dikirim ulang." else ""
    }

    private suspend fun restore(message: String): Result {
        update { it.copy(transcriptStatus = previous.value, transcriptionMessage = message, transcriptionResumeAt = null) }
        return Result.success()
    }

    companion object {
        /** Paket gratis Groq memberi 2 jam audio per jam dan 8 jam per hari, jadi 12 kali menunggu sudah lebih dari cukup. */
        const val MAX_WAITS = 12

        /** Awal pesan hasil, dipakai layar Transkrip untuk mewarnai pesan berhasil, dibatalkan, atau gagal. */
        const val DONE_PREFIX = "Transkrip dibuat lewat"
        const val CANCELLED_PREFIX = "Transkripsi dibatalkan."
    }
}
