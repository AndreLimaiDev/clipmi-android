package id.clipmi.ios

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.SourceType
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resume

/**
 * Project dari video di iPhone (padanan createFromLocal + ImportLocalVideoWorker Android): video dari Photos atau Files
 * dipindah ke folder project (`projects/<id>/source.<ext>`), durasinya dibaca, lalu menunggu transkrip (impor SRT/VTT
 * atau Transkripsi API). Hanya MP4/MOV: pratinjau AVPlayer tidak bisa memutar MKV.
 */
class LocalVideos(private val repo: Repository, private val renderer: IosRenderer) {
    /** Sedang memilih atau menyalin video (indikator di daftar project). */
    var busy by mutableStateOf(false)
        private set

    private data class Picked(val path: String?, val name: String?, val error: String?)

    private suspend fun pick(fromFiles: Boolean): Picked = suspendCancellableCoroutine { cont ->
        createDirectory(repo.absolute("tmp"))
        renderer.engine.pickSourceVideo(repo.absolute("tmp/source-pick"), fromFiles, object : ResultListener {
            override fun finished(resultJson: String) {
                val o = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull()
                fun str(key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull
                if (cont.isActive) cont.resume(Picked(str("path"), str("name"), str("error")))
            }
        })
    }

    /** @return id project baru, null bila batal; galat berisi pesan untuk pengguna. */
    suspend fun create(fromFiles: Boolean): Result<Long?> {
        if (busy) return Result.success(null)
        busy = true
        try {
            val picked = pick(fromFiles)
            val path = picked.path ?: return picked.error?.let { Result.failure(Exception(it)) } ?: Result.success(null)
            val ext = path.substringAfterLast('.', "mp4").lowercase()
            val info = renderer.probe(path)
            if (ext !in EXTENSIONS || info == null || (info.duration ?: 0.0) <= 0) {
                removeItem(path)
                return Result.failure(Exception("Video ini tidak bisa dibaca di iPhone. Pakai MP4 atau MOV."))
            }
            val name = picked.name?.takeIf { it.isNotBlank() } ?: "Video"
            val id = repo.db.projects().insert(
                ProjectEntity(
                    title = name,
                    sourceType = SourceType.LOCAL,
                    sourceUrl = "$name.$ext",
                    videoStatus = VideoStatus.Processing.value,
                    transcriptStatus = TranscriptStatus.NeedsTranscript.value,
                ),
            )
            val dir = repo.projectDir(id)
            createDirectory(repo.absolute(dir))
            val relative = "$dir/source.$ext"
            moveItem(path, repo.absolute(relative))
            if (fileSize(repo.absolute(relative)) <= 0) {
                repo.db.projects().modify(id) { it.copy(videoStatus = VideoStatus.Failed.value, errorMessage = "Gagal menyalin video ke folder project.") }
                return Result.success(id)
            }
            repo.db.projects().modify(id) {
                it.copy(videoPath = relative, videoStatus = VideoStatus.Ready.value, videoProgress = null, durationSeconds = info.duration)
            }
            return Result.success(id)
        } finally {
            busy = false
        }
    }

    /** Video sumber project galeri yang dihapus atau gagal dipilih ulang; false bila batal. */
    suspend fun recover(projectId: Long, fromFiles: Boolean): Result<Boolean> {
        if (busy) return Result.success(false)
        busy = true
        try {
            val picked = pick(fromFiles)
            val path = picked.path ?: return picked.error?.let { Result.failure(Exception(it)) } ?: Result.success(false)
            val ext = path.substringAfterLast('.', "mp4").lowercase()
            val info = renderer.probe(path)
            if (ext !in EXTENSIONS || info == null || (info.duration ?: 0.0) <= 0) {
                removeItem(path)
                return Result.failure(Exception("Video ini tidak bisa dibaca di iPhone. Pakai MP4 atau MOV."))
            }
            val dir = repo.projectDir(projectId)
            createDirectory(repo.absolute(dir))
            val relative = "$dir/source.$ext"
            moveItem(path, repo.absolute(relative))
            repo.db.projects().modify(projectId) {
                it.copy(
                    videoPath = relative, videoStatus = VideoStatus.Ready.value, videoProgress = null, sourceDeletedAt = null,
                    durationSeconds = it.durationSeconds ?: info.duration, errorMessage = null,
                )
            }
            return Result.success(true)
        } finally {
            busy = false
        }
    }

    companion object {
        val EXTENSIONS = listOf("mp4", "mov", "m4v")
    }
}

/** Pilih asal video: Foto atau aplikasi Files (sama dengan kartu "Dari galeri" Android). */
@Composable
fun LocalVideoSheet(onDismiss: () -> Unit, onPick: (fromFiles: Boolean) -> Unit) {
    ClipMiSheet(onDismiss, title = "Video dari iPhone", subtitle = "MP4 atau MOV. Videonya disalin ke ClipMI, jadi aman walau file aslinya dihapus.") {
        PrimaryButton("Dari Foto", icon = ClipMiIcons.Image, modifier = Modifier.fillMaxWidth(), onClick = { onPick(false) })
        SecondaryButton("Dari aplikasi Files", icon = ClipMiIcons.Film, modifier = Modifier.fillMaxWidth(), onClick = { onPick(true) })
        Text(
            "Video tanpa subtitle butuh transkrip: impor SRT/VTT atau jalankan Transkripsi API di tab Transkrip. Video panjang butuh beberapa saat untuk disalin.",
            style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted,
        )
    }
}
