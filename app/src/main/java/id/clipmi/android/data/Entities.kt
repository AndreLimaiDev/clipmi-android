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
    @ColumnInfo(name = "analysis_status") val analysisStatus: String? = null,
    @ColumnInfo(name = "analysis_message") val analysisMessage: String? = null,
    /** JSON {"key": sidik prompt, "total": n, "done": [indeks bagian]}: bagian yang tidak perlu dikirim ulang. */
    @ColumnInfo(name = "analysis_parts") val analysisParts: String? = null,
    @ColumnInfo(name = "analysis_heartbeat_at") val analysisHeartbeatAt: Long? = null,
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

/** Status tinjauan kandidat; nilai sama dengan desktop. */
enum class ReviewStatus(val value: String, val label: String) {
    Candidate("candidate", "Kandidat"),
    Selected("selected", "Terpilih"),
    Rejected("rejected", "Dibuang");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value } ?: Candidate
    }
}

object ClipSource {
    const val AI = "ai"
    const val MANUAL = "manual"
}

/** Status analisis Mode API di project (sama dengan konstanta AnalyzeWithAi desktop). */
object AnalysisStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun isActive(status: String?) = status == QUEUED || status == RUNNING
}

@Entity(
    tableName = "clip_series",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["project_id", "title"], unique = true)],
)
data class ClipSeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Kandidat dan hasil klip. Kolom Fase 2 (pemilihan momen); kolom editor dan render menyusul di Fase 3 lewat migrasi.
 * hashtags dan keywords berupa JSON array, sama dengan desktop.
 */
@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ClipSeriesEntity::class, parentColumns = ["id"], childColumns = ["clip_series_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["project_id", "review_status"]), Index("clip_series_id")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "clip_series_id") val clipSeriesId: Long? = null,
    @ColumnInfo(name = "part_number") val partNumber: Int? = null,
    val title: String,
    @ColumnInfo(name = "start_time") val startTime: Double,
    @ColumnInfo(name = "end_time") val endTime: Double,
    val score: Int? = null,
    val hook: String? = null,
    val caption: String? = null,
    val hashtags: String? = null,
    val keywords: String? = null,
    val reason: String? = null,
    @ColumnInfo(name = "review_status") val reviewStatus: String = ReviewStatus.Candidate.value,
    val source: String = ClipSource.AI,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val review: ReviewStatus get() = ReviewStatus.from(reviewStatus)
    val duration: Double get() = endTime - startTime
}

/** Catatan pemakaian API AI per analisis/transkripsi, untuk halaman Pemakaian AI (sama dengan desktop). */
@Entity(
    tableName = "ai_usages",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("created_at"), Index("project_id")],
)
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** analysis, transcription, performance */
    val purpose: String,
    @ColumnInfo(name = "project_id") val projectId: Long?,
    @ColumnInfo(name = "project_title") val projectTitle: String,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int = 0,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int = 0,
    @ColumnInfo(name = "audio_seconds") val audioSeconds: Double = 0.0,
    val cost: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
