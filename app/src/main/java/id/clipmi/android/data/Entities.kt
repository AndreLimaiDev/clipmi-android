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

@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(entity = BumperEntity::class, parentColumns = ["id"], childColumns = ["intro_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = BumperEntity::class, parentColumns = ["id"], childColumns = ["outro_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("youtube_video_id"), Index("intro_id"), Index("outro_id")],
)
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
    /** Posisi crop 0 (kiri) sampai 100 (kanan) untuk klip yang tidak mengatur posisinya sendiri. */
    @ColumnInfo(name = "crop_position", defaultValue = "50") val cropPosition: Int = 50,
    /** Intro/outro bawaan project: dipasang ke klip baru di project ini. */
    @ColumnInfo(name = "intro_id") val introId: Long? = null,
    @ColumnInfo(name = "outro_id") val outroId: Long? = null,
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

/** Status render klip; nilai sama dengan enum RenderStatus desktop. */
enum class RenderStatus(val value: String, val label: String) {
    NotRendered("not_rendered", "Belum dirender"),
    Queued("queued", "Antre"),
    Rendering("rendering", "Merender"),
    Done("done", "Selesai"),
    Failed("failed", "Gagal");

    val isBusy: Boolean get() = this == Queued || this == Rendering

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: NotRendered
    }
}

/**
 * Kandidat dan hasil klip. hashtags, keywords, cuts, subtitle_lines, dan hook_layout berupa JSON dengan format yang
 * sama dengan desktop. output_path = URI MediaStore file hasil di Movies/ClipMI.
 */
@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ClipSeriesEntity::class, parentColumns = ["id"], childColumns = ["clip_series_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CaptionStyleEntity::class, parentColumns = ["id"], childColumns = ["caption_style_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = FrameStyleEntity::class, parentColumns = ["id"], childColumns = ["frame_style_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = BumperEntity::class, parentColumns = ["id"], childColumns = ["intro_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = BumperEntity::class, parentColumns = ["id"], childColumns = ["outro_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["project_id", "review_status"]), Index("clip_series_id"), Index("caption_style_id"), Index("frame_style_id"),
        Index("intro_id"), Index("outro_id"),
    ],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "clip_series_id") val clipSeriesId: Long? = null,
    @ColumnInfo(name = "caption_style_id") val captionStyleId: Long? = null,
    @ColumnInfo(name = "frame_style_id") val frameStyleId: Long? = null,
    @ColumnInfo(name = "crop_position") val cropPosition: Int? = null,
    @ColumnInfo(name = "crop_zoom") val cropZoom: Int? = null,
    @ColumnInfo(name = "crop_y") val cropY: Int? = null,
    val cuts: String? = null,
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
    @ColumnInfo(name = "subtitle_lines") val subtitleLines: String? = null,
    @ColumnInfo(name = "subtitle_edited") val subtitleEdited: Boolean = false,
    @ColumnInfo(name = "show_hook_overlay") val showHookOverlay: Boolean = true,
    @ColumnInfo(name = "hook_layout") val hookLayout: String? = null,
    @ColumnInfo(name = "render_status") val renderStatus: String = RenderStatus.NotRendered.value,
    @ColumnInfo(name = "render_progress") val renderProgress: Int? = null,
    @ColumnInfo(name = "render_error") val renderError: String? = null,
    @ColumnInfo(name = "output_path") val outputPath: String? = null,
    @ColumnInfo(name = "rendered_at") val renderedAt: Long? = null,
    @ColumnInfo(name = "edited_at") val editedAt: Long? = null,
    /** Sampul: frame pada detik video sumber ini, atau gambar dari galeri (cover_path, relatif workspace). */
    @ColumnInfo(name = "cover_time") val coverTime: Double? = null,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "cover_title", defaultValue = "0") val coverTitle: Boolean = false,
    @ColumnInfo(name = "cover_in_video", defaultValue = "1") val coverInVideo: Boolean = true,
    @ColumnInfo(name = "intro_id") val introId: Long? = null,
    @ColumnInfo(name = "outro_id") val outroId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val review: ReviewStatus get() = ReviewStatus.from(reviewStatus)
    val duration: Double get() = endTime - startTime
    val render: RenderStatus get() = RenderStatus.from(renderStatus)

    /** Klip punya sampul: frame pilihan atau gambar dari galeri. */
    val hasCover: Boolean get() = coverTime != null || coverPath != null

    /** Tampilan klip diubah setelah render terakhir: video hasil belum mengikuti perubahan itu. */
    val changedAfterRender: Boolean
        get() = render == RenderStatus.Done && renderedAt != null && editedAt != null && editedAt > renderedAt
}

/** Gaya subtitle (tabel caption_styles, kolom sama dengan desktop). */
@Entity(tableName = "caption_styles", indices = [Index(value = ["name"], unique = true)])
data class CaptionStyleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "font_family") val fontFamily: String = "Poppins",
    @ColumnInfo(name = "font_size") val fontSize: Int = 76,
    val uppercase: Boolean = false,
    @ColumnInfo(name = "primary_color") val primaryColor: String = "#FFFFFF",
    @ColumnInfo(name = "outline_color") val outlineColor: String = "#000000",
    @ColumnInfo(name = "highlight_active_word") val highlightActiveWord: Boolean = false,
    @ColumnInfo(name = "highlight_style") val highlightStyle: String = "color",
    @ColumnInfo(name = "highlight_color") val highlightColor: String = "#FFD400",
    @ColumnInfo(name = "upcoming_color") val upcomingColor: String? = null,
    @ColumnInfo(name = "keyword_highlight") val keywordHighlight: Boolean = true,
    @ColumnInfo(name = "keyword_color") val keywordColor: String = "#22C55E",
    val position: String = "bottom",
    @ColumnInfo(name = "words_per_line") val wordsPerLine: Int = 4,
    val animation: String = "none",
    val effect: String = "outline",
    @ColumnInfo(name = "line_box") val lineBox: Boolean = false,
    @ColumnInfo(name = "line_box_color") val lineBoxColor: String = "#000000",
    @ColumnInfo(name = "line_box_opacity") val lineBoxOpacity: Int = 65,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** Gaya bingkai (tabel frame_styles, kolom sama dengan desktop). template_area = JSON {"x","y","width","height"}. */
@Entity(tableName = "frame_styles", indices = [Index(value = ["name"], unique = true)])
data class FrameStyleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val aspect: String = "9:16",
    val mode: String,
    val background: String = "blur",
    @ColumnInfo(name = "blur_strength") val blurStrength: Int = 8,
    @ColumnInfo(name = "background_color") val backgroundColor: String = "#000000",
    @ColumnInfo(name = "background_color_2") val backgroundColor2: String = "#1E3A8A",
    @ColumnInfo(name = "background_path") val backgroundPath: String? = null,
    @ColumnInfo(name = "template_path") val templatePath: String? = null,
    @ColumnInfo(name = "template_area") val templateArea: String? = null,
    @ColumnInfo(name = "video_height") val videoHeight: Int = 65,
    @ColumnInfo(name = "video_position") val videoPosition: String = "center",
    val zoom: Int = 100,
    @ColumnInfo(name = "corner_radius") val cornerRadius: Int = 0,
    @ColumnInfo(name = "border_width") val borderWidth: Int = 0,
    @ColumnInfo(name = "border_color") val borderColor: String = "#FFFFFF",
    @ColumnInfo(name = "logo_path") val logoPath: String? = null,
    @ColumnInfo(name = "logo_x") val logoX: Int = 100,
    @ColumnInfo(name = "logo_y") val logoY: Int = 0,
    @ColumnInfo(name = "logo_size") val logoSize: Int = 18,
    @ColumnInfo(name = "logo_opacity") val logoOpacity: Int = 85,
    @ColumnInfo(name = "watermark_text") val watermarkText: String? = null,
    @ColumnInfo(name = "watermark_font") val watermarkFont: String = "Poppins",
    @ColumnInfo(name = "watermark_size") val watermarkSize: Int = 40,
    @ColumnInfo(name = "watermark_color") val watermarkColor: String = "#FFFFFF",
    @ColumnInfo(name = "watermark_opacity") val watermarkOpacity: Int = 80,
    @ColumnInfo(name = "watermark_x") val watermarkX: Int = 50,
    @ColumnInfo(name = "watermark_y") val watermarkY: Int = 100,
    @ColumnInfo(name = "title_bar") val titleBar: Boolean = false,
    @ColumnInfo(name = "title_bar_color") val titleBarColor: String = "#000000",
    @ColumnInfo(name = "title_text_color") val titleTextColor: String = "#FFFFFF",
    @ColumnInfo(name = "subtitle_y") val subtitleY: Int? = null,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

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

/**
 * Video intro (pembuka) atau outro (penutup) di pustaka (padanan `Bumper` desktop), dipilih per klip di editor dan
 * disambung saat render. path dan thumbnail_path relatif terhadap workspace.
 */
@Entity(tableName = "bumpers", indices = [Index(value = ["kind", "last_used_at"])])
data class BumperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "intro" atau "outro". */
    val kind: String,
    val name: String,
    val path: String,
    val duration: Double,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        val KINDS = linkedMapOf("intro" to "Intro", "outro" to "Outro")

        /** Intro/outro yang lebih panjang membuat klip pendek terasa lambat dibuka. */
        const val MAX_SECONDS = 30
    }
}
