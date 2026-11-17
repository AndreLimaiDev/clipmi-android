package id.clipmi.android.work

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.ClipMiApp
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.domain.MediaInfo
import id.clipmi.core.youtube.SubtitlePicker
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.concurrent.TimeUnit

private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Antrean pekerjaan project. Pemanggil cukup lewat fungsi ini, bukan membuat WorkRequest sendiri. */
object ProjectQueue {
    fun youtube(context: Context, projectId: Long) = WorkQueue.DOWNLOAD.enqueue(
        context,
        OneTimeWorkRequestBuilder<YouTubeWorker>().setInputData(workDataOf(KEY_PROJECT to projectId)).setConstraints(network)
            .addTag(tag(projectId)).build(),
    )

    fun localVideo(context: Context, projectId: Long, uri: Uri) = WorkQueue.DOWNLOAD.enqueue(
        context,
        OneTimeWorkRequestBuilder<ImportLocalVideoWorker>()
            .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_URI to uri.toString())).addTag(tag(projectId)).build(),
    )

    fun transcribe(context: Context, projectId: Long, previousStatus: TranscriptStatus, waits: Int = 0, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_PREVIOUS to previousStatus.value, KEY_WAITS to waits))
            .setConstraints(network).addTag(tag(projectId)).addTag(transcribeTag(projectId))
        if (delaySeconds > 0) {
            // Menunggu batas pemakaian pulih di luar antrean AI, supaya pekerjaan AI lain tidak ikut tertahan.
            request.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            WorkManager.getInstance(context).enqueueUniqueWork("transcribe-resume-$projectId", ExistingWorkPolicy.REPLACE, request.build())
        } else {
            WorkQueue.AI.enqueue(context, request.build())
        }
    }

    fun cancelTranscription(context: Context, projectId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(transcribeTag(projectId))
    }

    fun cancelAll(context: Context, projectId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(projectId))
    }

    fun tag(projectId: Long) = "project-$projectId"
    private fun transcribeTag(projectId: Long) = "transcribe-$projectId"

    const val KEY_PROJECT = "project"
    const val KEY_URI = "uri"
    const val KEY_PREVIOUS = "previous"
    const val KEY_WAITS = "waits"
}

/** Dasar pekerjaan project: akses ke container aplikasi dan notifikasi progres. */
abstract class ProjectWorker(context: Context, params: WorkerParameters, private val queue: WorkQueue) :
    CoroutineWorker(context, params) {
    protected val container = (context.applicationContext as ClipMiApp).container
    protected val projects = container.database.projects()
    protected val projectId = inputData.getLong(ProjectQueue.KEY_PROJECT, 0)
    private var lastNotified = -10

    protected suspend fun notify(title: String, text: String, progress: Int? = null) {
        if (progress != null && progress - lastNotified < 2 && progress != 100) return
        lastNotified = progress ?: lastNotified
        runCatching { setForeground(foregroundInfo(applicationContext, queue, title, text, progress)) }
    }

    protected suspend fun update(change: (ProjectEntity) -> ProjectEntity) = projects.modify(projectId, change)

    /** Transkripsi otomatis untuk video tanpa subtitle, bila diaktifkan dan Transkripsi API sudah diatur. */
    protected suspend fun startTranscriptionAutomatically(): Boolean {
        val project = projects.find(projectId) ?: return false
        if (!container.settings.autoTranscribe() || container.settings.transcription() == null ||
            project.transcript != TranscriptStatus.NeedsTranscript || project.video != VideoStatus.Ready
        ) return false
        container.actions.startTranscription(projectId, "Dimulai otomatis karena video belum punya transkrip. Menunggu giliran…")
        return true
    }

    /** Durasi dan thumbnail setelah video sumber tersedia (padanan PrepareVideo desktop). */
    protected suspend fun prepareVideo(video: File) {
        val duration = MediaInfo.durationSeconds(video)
        val current = update { it.copy(videoStatus = VideoStatus.Processing.value, videoProgress = null, durationSeconds = duration ?: it.durationSeconds) }
        if (current?.thumbnailPath == null || container.workspace.file(current.thumbnailPath)?.isFile != true) {
            val thumb = File(container.workspace.projectDir(projectId), "thumbnail.jpg")
            if (MediaInfo.thumbnail(video, thumb, duration)) update { it.copy(thumbnailPath = container.workspace.relative(thumb)) }
        }
        // ExoPlayer memutar MP4/MOV/MKV H.264/HEVC langsung, jadi pratinjau terpisah (proxy desktop) belum diperlukan.
        update { it.copy(videoStatus = VideoStatus.Ready.value, videoProgress = null) }
    }
}

/**
 * Project dari link YouTube: metadata + subtitle json3 dulu (transkrip langsung bisa dipakai), lalu video H.264 ≤1080p
 * dan audio digabung FFmpeg. Padanan FetchYouTubeTranscript + DownloadYouTubeVideo + PrepareVideo desktop, dalam satu
 * pekerjaan agar ekstraksi YouTube (dan tantangan JS-nya) cukup sekali.
 */
class YouTubeWorker(context: Context, params: WorkerParameters) : ProjectWorker(context, params, WorkQueue.DOWNLOAD) {
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.DOWNLOAD, "Mengambil video YouTube", "Menyiapkan…", null)

    override suspend fun doWork(): Result {
        val project = projects.find(projectId) ?: return Result.success()
        val url = project.sourceUrl ?: return Result.failure()
        val ws = container.workspace
        val youtubeDir = ws.youtubeDir(projectId)
        notify(project.title, "Mengambil judul dan subtitle…")

        if (project.transcript != TranscriptStatus.Ready || !File(youtubeDir, "youtube.info.json").isFile) {
            fetchTranscript(url, youtubeDir)
        }

        val current = projects.find(projectId) ?: return Result.success()
        if (current.video != VideoStatus.Ready || ws.file(current.videoPath)?.isFile != true) {
            downloadVideo(url, youtubeDir)
        }
        startTranscriptionAutomatically()
        return Result.success()
    }

    private suspend fun fetchTranscript(url: String, youtubeDir: File) {
        val keepReady = projects.find(projectId)?.transcript == TranscriptStatus.Ready
        if (!keepReady) update { it.copy(transcriptStatus = TranscriptStatus.Fetching.value) }
        try {
            val info = container.ytDlp.fetchInfoAndSubtitles(url, youtubeDir)
            val title = (info["title"] as? JsonPrimitive)?.contentOrNull
            val duration = (info["duration"] as? JsonPrimitive)?.doubleOrNull
            update {
                it.copy(
                    title = if (title != null && it.title == ProjectEntity.youtubePlaceholderTitle(it.youtubeVideoId.orEmpty())) title else it.title,
                    durationSeconds = duration ?: it.durationSeconds,
                )
            }
            saveThumbnail(youtubeDir)
            if (keepReady) return

            val track = SubtitlePicker.pick(info)
            if (track == null) {
                update { it.copy(transcriptStatus = TranscriptStatus.NeedsTranscript.value) }
                return
            }
            val file = File(youtubeDir, "youtube.${track.language}.json3")
            if (!file.isFile) error("Subtitle tersedia di YouTube tetapi gagal diunduh. Coba lagi beberapa saat lagi.")
            container.transcripts.import(projectId, file.readBytes(), track.source, "id")
            container.analysis.startAutomatically(projectId)
        } catch (e: Throwable) {
            // Error juga ditangkap (misalnya ExceptionInInitializerError): status project harus selalu diperbarui.
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!keepReady) {
                update { it.copy(transcriptStatus = TranscriptStatus.Failed.value, errorMessage = "Gagal mengambil subtitle YouTube. ${e.message}") }
            }
        }
    }

    /** Thumbnail resmi dipindah keluar dari folder youtube/, yang dikosongkan setiap kali metadata diambil ulang. */
    private suspend fun saveThumbnail(youtubeDir: File) {
        for (ext in listOf("webp", "jpg", "png")) {
            val file = File(youtubeDir, "youtube.$ext")
            if (!file.isFile) continue
            val target = File(container.workspace.projectDir(projectId), "thumbnail.$ext")
            file.copyTo(target, overwrite = true)
            file.delete()
            update { it.copy(thumbnailPath = container.workspace.relative(target)) }
            return
        }
    }

    private suspend fun downloadVideo(url: String, youtubeDir: File) {
        val title = projects.find(projectId)?.title.orEmpty()
        update { it.copy(videoStatus = VideoStatus.Downloading.value, videoProgress = 0) }
        val dir = container.workspace.projectDir(projectId)
        var saved = 0f
        try {
            val (video, audio) = container.ytDlp.downloadStreams(url, dir, File(youtubeDir, "youtube.info.json")) { pct ->
                // Disimpan per 2% saja, karena yt-dlp melaporkan progres berkali-kali per detik.
                if (kotlin.math.abs(pct - saved) >= 2) {
                    saved = pct
                    kotlinx.coroutines.runBlocking {
                        update { it.copy(videoProgress = pct.toInt()) }
                        notify(title, "Mengunduh video ${pct.toInt()}%", pct.toInt())
                    }
                }
            }
            update { it.copy(videoStatus = VideoStatus.Processing.value, videoProgress = null) }
            notify(title, "Menggabungkan video dan audio…")
            val source = File(dir, "source.mp4")
            container.ffmpeg.mux(video, audio, source)
            video.delete()
            audio.delete()
            update { it.copy(videoPath = container.workspace.relative(source)) }
            prepareVideo(source)
        } catch (e: Throwable) {
            // Error juga ditangkap (misalnya ExceptionInInitializerError): status project harus selalu diperbarui.
            if (e is kotlinx.coroutines.CancellationException) throw e
            update { it.copy(videoStatus = VideoStatus.Failed.value, videoProgress = null, errorMessage = "Gagal mengunduh video YouTube. ${e.message}") }
        }
    }
}

/** Menyalin video pilihan pengguna ke folder kerja, agar project tetap utuh walau file aslinya dipindah/dihapus. */
class ImportLocalVideoWorker(context: Context, params: WorkerParameters) : ProjectWorker(context, params, WorkQueue.DOWNLOAD) {
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.DOWNLOAD, "Menyalin video", "Menyiapkan…", null)

    override suspend fun doWork(): Result {
        val project = projects.find(projectId) ?: return Result.success()
        val uri = Uri.parse(inputData.getString(ProjectQueue.KEY_URI) ?: return Result.failure())
        val resolver = applicationContext.contentResolver
        update { it.copy(videoStatus = VideoStatus.Processing.value) }
        notify(project.title, "Menyalin video ke folder kerja…")
        try {
            val name = project.sourceUrl.orEmpty()
            val ext = name.substringAfterLast('.', "mp4").lowercase().takeIf { it in EXTENSIONS } ?: "mp4"
            val target = File(container.workspace.projectDir(projectId), "source.$ext")
            val total = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            // Disalin sebagai stream supaya video beberapa GB tidak dimuat ke memori.
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "File video tidak bisa dibuka." }
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) notify(project.title, "Menyalin video…", (copied * 100 / total).toInt())
                    }
                }
            }
            runCatching { resolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            update { it.copy(videoPath = container.workspace.relative(target)) }
            prepareVideo(target)
            startTranscriptionAutomatically()
        } catch (e: Throwable) {
            // Error juga ditangkap (misalnya ExceptionInInitializerError): status project harus selalu diperbarui.
            if (e is kotlinx.coroutines.CancellationException) throw e
            update { it.copy(videoStatus = VideoStatus.Failed.value, errorMessage = "Gagal menyalin video. ${e.message}") }
        }
        return Result.success()
    }

    companion object {
        val EXTENSIONS = listOf("mp4", "mov", "mkv")
    }
}
