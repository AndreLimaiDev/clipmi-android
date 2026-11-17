package id.clipmi.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Status video; nilai Inggris di database sama dengan desktop, label Indonesia untuk layar. */
enum class VideoStatus(val value: String, val label: String) {
    Pending("pending", "Menunggu"),
    Downloading("downloading", "Mengunduh"),
    Processing("processing", "Memproses"),
    Ready("ready", "Siap"),
    Failed("failed", "Gagal"),
    Deleted("deleted", "Dihapus");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value } ?: Pending
    }
}

enum class TranscriptStatus(val value: String, val label: String) {
    Pending("pending", "Menunggu"),
    Fetching("fetching", "Mengambil"),
    NeedsTranscript("needs_transcript", "Butuh Transkrip"),
    Ready("ready", "Siap"),
    Failed("failed", "Gagal");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value } ?: Pending
    }
}

object SourceType {
    const val YOUTUBE = "youtube"
    const val LOCAL = "local"
}

@Entity(tableName = "projects", indices = [Index("youtube_video_id")])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "youtube_video_id") val youtubeVideoId: String? = null,
    /** Relatif terhadap folder kerja (Workspace), seperti disk "workspace" desktop. */
    @ColumnInfo(name = "video_path") val videoPath: String? = null,
    @ColumnInfo(name = "preview_path") val previewPath: String? = null,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double? = null,
    @ColumnInfo(name = "video_status") val videoStatus: String = VideoStatus.Pending.value,
    @ColumnInfo(name = "video_progress") val videoProgress: Int? = null,
    @ColumnInfo(name = "transcript_status") val transcriptStatus: String = TranscriptStatus.Pending.value,
    @ColumnInfo(name = "transcript_source") val transcriptSource: String? = null,
    @ColumnInfo(name = "transcript_language") val transcriptLanguage: String? = null,
    @ColumnInfo(name = "transcription_message") val transcriptionMessage: String? = null,
    @ColumnInfo(name = "transcription_resume_at") val transcriptionResumeAt: Long? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "source_deleted_at") val sourceDeletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val video: VideoStatus get() = VideoStatus.from(videoStatus)
    val transcript: TranscriptStatus get() = TranscriptStatus.from(transcriptStatus)
    val isYouTube: Boolean get() = sourceType == SourceType.YOUTUBE

    companion object {
        /** Judul sementara sampai metadata YouTube terbaca (sama dengan desktop). */
        fun youtubePlaceholderTitle(videoId: String) = "Video YouTube $videoId"
    }
}

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["project_id", "sequence"], unique = true)],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val sequence: Int,
    @ColumnInfo(name = "start_time") val startTime: Double,
    @ColumnInfo(name = "end_time") val endTime: Double,
    val text: String,
    /** JSON waktu per kata, format sama dengan desktop: [{"text":..,"start":..,"end":..}]. */
    val words: String?,
    @ColumnInfo(name = "words_estimated") val wordsEstimated: Boolean,
)

@Entity(tableName = "correction_entries", indices = [Index(value = ["wrong_text"], unique = true)])
data class CorrectionEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Unik tanpa membedakan huruf besar/kecil (COLLATE NOCASE), sama dengan desktop. */
    @ColumnInfo(name = "wrong_text", collate = ColumnInfo.NOCASE) val wrongText: String,
    @ColumnInfo(name = "correct_text") val correctText: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "app_settings", indices = [Index(value = ["key"], unique = true)])
data class AppSettingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
