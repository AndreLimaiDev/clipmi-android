package id.clipmi.android.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.SourceType
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.work.ProjectQueue
import id.clipmi.android.work.TranscribeWorker
import id.clipmi.core.youtube.TranscriptSource
import id.clipmi.core.youtube.YouTubeUrl

/** Hasil membuat project dari teks berisi link: project baru dan link yang dilewati (beserta alasannya). */
data class CreateResult(val created: List<Long>, val skipped: List<String>)

/** Aksi pengguna terhadap project (dipanggil ViewModel/layar); pekerjaan berat diserahkan ke antrean WorkManager. */
class ProjectActions(
    private val context: Context,
    private val db: AppDatabase,
    private val workspace: Workspace,
    private val transcripts: Transcripts,
) {
    /**
     * Membuat project dari satu atau beberapa link YouTube (dipisah spasi/baris baru, maksimal 30), khusus konten yang
     * sudah mendapat izin dari pemiliknya. Link yang sudah menjadi project dilewati.
     */
    suspend fun createFromYouTube(text: String): CreateResult {
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val ids = LinkedHashSet<String>()
        val skipped = mutableListOf<String>()
        for (token in tokens) {
            val id = YouTubeUrl.videoId(token)
            when {
                id != null -> ids += id
                YouTubeUrl.playlistId(token) != null -> skipped += "$token (playlist belum didukung di HP; tempel link videonya)"
                token.startsWith("http") -> skipped += "$token (bukan link video YouTube)"
            }
        }
        val created = mutableListOf<Long>()
        for (id in ids.take(YouTubeUrl.MAX_BATCH)) {
            val existing = db.projects().findByYouTubeId(id)
            if (existing != null) {
                skipped += "${existing.title} (sudah ada)"
                continue
            }
            val projectId = db.projects().insert(
                ProjectEntity(
                    title = ProjectEntity.youtubePlaceholderTitle(id),
                    sourceType = SourceType.YOUTUBE,
                    sourceUrl = YouTubeUrl.canonical(id),
                    youtubeVideoId = id,
                ),
            )
            ProjectQueue.youtube(context, projectId)
            created += projectId
        }
        if (ids.size > YouTubeUrl.MAX_BATCH) skipped += "${ids.size - YouTubeUrl.MAX_BATCH} link lain (maksimal ${YouTubeUrl.MAX_BATCH} per kali)"
        return CreateResult(created, skipped)
    }

    /** Project dari video di galeri/file manager (MP4/MOV/MKV); videonya disalin ke folder kerja di latar. */
    suspend fun createFromLocal(uri: Uri): Long {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video.mp4"
        val projectId = db.projects().insert(
            ProjectEntity(
                title = name.substringBeforeLast('.').ifBlank { name },
                sourceType = SourceType.LOCAL,
                sourceUrl = name,
                transcriptStatus = TranscriptStatus.NeedsTranscript.value,
            ),
        )
        ProjectQueue.localVideo(context, projectId, uri)
        return projectId
    }

    /**
     * Project YouTube yang statusnya masih "berjalan" tetapi tidak punya pekerjaan aktif (misalnya pekerjaan terhenti
     * oleh error tak terduga) diantrekan ulang. Dipanggil saat aplikasi dibuka.
     */
    suspend fun resumeInterrupted(): Int {
        val manager = androidx.work.WorkManager.getInstance(context)
        var resumed = 0
        for (project in db.projects().all()) {
            if (!project.isYouTube) continue
            val busy = project.video in listOf(VideoStatus.Pending, VideoStatus.Downloading, VideoStatus.Processing) ||
                (project.transcript == TranscriptStatus.Fetching && project.transcriptionMessage == null)
            if (!busy) continue
            val active = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                manager.getWorkInfosByTag(ProjectQueue.tag(project.id)).get()
            }.any { !it.state.isFinished }
            if (active) continue
            db.projects().modify(project.id) {
                it.copy(
                    transcriptStatus = if (it.transcript == TranscriptStatus.Fetching) TranscriptStatus.Pending.value else it.transcriptStatus,
                    videoStatus = if (it.video == VideoStatus.Ready) it.videoStatus else VideoStatus.Pending.value,
                    videoProgress = null,
                )
            }
            ProjectQueue.youtube(context, project.id)
            resumed++
        }
        return resumed
    }

    /** Mengulang unduhan/salin yang gagal (YouTube saja; video lokal perlu dipilih ulang). */
    suspend fun retry(projectId: Long) {
        val project = db.projects().find(projectId) ?: return
        if (project.isYouTube) {
            db.projects().modify(projectId) { it.copy(errorMessage = null) }
            ProjectQueue.youtube(context, projectId)
        }
    }

    /** Impor SRT/VTT (misalnya hasil TurboScribe), menggantikan transkrip lama. */
    suspend fun importSubtitle(projectId: Long, uri: Uri): ImportResult {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "subtitle.srt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File tidak bisa dibaca.")
        return transcripts.import(projectId, bytes, TranscriptSource.fromImportedFilename(name), "id")
    }

    /** Menandai transkrip "Mengambil" lalu mengirim pekerjaannya ke antrean AI; status lama disimpan untuk dipulihkan. */
    suspend fun startTranscription(projectId: Long, message: String = "Menunggu giliran…") {
        val project = db.projects().find(projectId) ?: return
        if (project.transcript == TranscriptStatus.Fetching) return
        db.projects().modify(projectId) { it.copy(transcriptStatus = TranscriptStatus.Fetching.value, transcriptionMessage = message) }
        ProjectQueue.transcribe(context, projectId, project.transcript)
    }

    suspend fun cancelTranscription(projectId: Long, restoreTo: TranscriptStatus) {
        ProjectQueue.cancelTranscription(context, projectId)
        db.projects().modify(projectId) {
            it.copy(transcriptStatus = restoreTo.value, transcriptionMessage = TranscribeWorker.CANCELLED_PREFIX, transcriptionResumeAt = null)
        }
    }

    suspend fun delete(projectId: Long) {
        ProjectQueue.cancelAll(context, projectId)
        db.projects().delete(projectId)
        workspace.deleteProject(projectId)
        workspace.transcribeDir(projectId).deleteRecursively()
    }

    // --- Video sumber (Penyimpanan & perawatan) -----------------------------------------------------------------

    /**
     * Menghapus video sumber dan pratinjaunya untuk menghemat ruang (`ManagesSourceVideo::deleteSourceVideo`).
     * Transkrip, klip, gambar kecil, dan hasil render tetap ada. Mengembalikan jumlah byte yang dibebaskan.
     */
    suspend fun deleteSource(projectId: Long): Long {
        val project = db.projects().find(projectId) ?: return 0
        if (project.video != VideoStatus.Ready) return 0
        val busy = db.clips().forProject(projectId).any { it.render == id.clipmi.android.data.RenderStatus.Queued || it.render == id.clipmi.android.data.RenderStatus.Rendering }
        if (busy) return 0
        val files = listOfNotNull(project.videoPath, project.previewPath).mapNotNull(workspace::file).filter { it.isFile }
        val freed = files.sumOf { it.length() }
        files.forEach { it.delete() }
        db.projects().modify(projectId) {
            it.copy(videoStatus = VideoStatus.Deleted.value, videoPath = null, previewPath = null, sourceDeletedAt = System.currentTimeMillis())
        }
        return freed
    }

    /**
     * Menyiapkan ulang video sumber yang dihapus, gagal, atau filenya hilang (`recoverVideo`): project YouTube diunduh
     * ulang (transkrip yang sudah ada dipertahankan), project file lokal memakai video [uri] yang dipilih ulang.
     */
    suspend fun recoverVideo(projectId: Long, uri: Uri? = null) {
        val project = db.projects().find(projectId) ?: return
        if (!project.isYouTube && uri == null) return
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        db.projects().modify(projectId) {
            it.copy(
                videoStatus = VideoStatus.Pending.value, videoProgress = null, videoPath = null, previewPath = null, sourceDeletedAt = null,
                errorMessage = if (it.transcript == TranscriptStatus.Failed) it.errorMessage else null,
            )
        }
        if (project.isYouTube) ProjectQueue.youtube(context, projectId) else ProjectQueue.localVideo(context, projectId, uri!!)
    }

    // --- Kamus koreksi -------------------------------------------------------------------------------------------

    /** Menambah atau mengubah pasangan koreksi; teks salah unik tanpa membedakan huruf besar/kecil. */
    suspend fun saveCorrection(id: Long?, wrong: String, correct: String, active: Boolean): String? {
        val w = wrong.trim()
        val c = correct.trim()
        if (w.isEmpty() || c.isEmpty()) return "Teks salah dan teks benar wajib diisi."
        val duplicate = db.corrections().findByWrong(w)
        if (duplicate != null && duplicate.id != id) return "\"$w\" sudah ada di kamus."
        if (id == null) {
            db.corrections().insert(CorrectionEntryEntity(wrongText = w, correctText = c, isActive = active))
        } else {
            val existing = db.corrections().find(id) ?: return "Entri kamus tidak ditemukan."
            db.corrections().update(existing.copy(wrongText = w, correctText = c, isActive = active, updatedAt = System.currentTimeMillis()))
        }
        return null
    }
}
