package id.clipmi.android.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: Long): ProjectEntity?

    /** Gambar kecil project terbaru: contoh frame untuk pratinjau gaya. */
    @Query("SELECT thumbnail_path FROM projects WHERE thumbnail_path IS NOT NULL ORDER BY updated_at DESC LIMIT 5")
    suspend fun recentThumbnails(): List<String>

    /** Intro/outro bawaan project: dipasang ke klip baru di project ini. */
    @Query("UPDATE projects SET intro_id = :introId, outro_id = :outroId, updated_at = :now WHERE id = :id")
    suspend fun setBumpers(id: Long, introId: Long?, outroId: Long?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM projects")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE youtube_video_id = :videoId LIMIT 1")
    suspend fun findByYouTubeId(videoId: String): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    /** Mengubah project terbaru dari database (bukan salinan lama), supaya pekerjaan latar tidak saling menimpa. */
    @Transaction
    suspend fun modify(id: Long, change: (ProjectEntity) -> ProjectEntity): ProjectEntity? {
        val current = find(id) ?: return null
        return change(current).copy(updatedAt = System.currentTimeMillis()).also { update(it) }
    }
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM transcript_segments WHERE project_id = :projectId ORDER BY sequence")
    fun observe(projectId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE project_id = :projectId ORDER BY sequence")
    suspend fun forProject(projectId: Long): List<TranscriptSegmentEntity>

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE project_id = :projectId")
    suspend fun count(projectId: Long): Int

    @Query("DELETE FROM transcript_segments WHERE project_id = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Insert
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Update
    suspend fun updateAll(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE id = :id")
    suspend fun find(id: Long): TranscriptSegmentEntity?

    @Update
    suspend fun update(segment: TranscriptSegmentEntity)

    /** Transkrip baru menggantikan yang lama dalam satu transaksi (seperti TranscriptImporter desktop). */
    @Transaction
    suspend fun replace(projectId: Long, segments: List<TranscriptSegmentEntity>) {
        deleteForProject(projectId)
        insertAll(segments)
    }
}

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM correction_entries ORDER BY wrong_text COLLATE NOCASE")
    suspend fun all(): List<CorrectionEntryEntity>

    @Query("SELECT * FROM correction_entries ORDER BY wrong_text COLLATE NOCASE")
    fun observeAll(): Flow<List<CorrectionEntryEntity>>

    /** Urutan id sama dengan pluck() desktop, menentukan aturan yang dipakai bila panjangnya sama. */
    @Query("SELECT * FROM correction_entries WHERE is_active = 1 ORDER BY id")
    suspend fun active(): List<CorrectionEntryEntity>

    @Query("SELECT * FROM correction_entries WHERE id = :id")
    suspend fun find(id: Long): CorrectionEntryEntity?

    @Query("SELECT * FROM correction_entries WHERE wrong_text = :wrong LIMIT 1")
    suspend fun findByWrong(wrong: String): CorrectionEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CorrectionEntryEntity): Long

    @Update
    suspend fun update(entry: CorrectionEntryEntity)

    @Query("DELETE FROM correction_entries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("UPDATE app_settings SET value = :value, updated_at = :now WHERE `key` = :key")
    suspend fun updateValue(key: String, value: String?, now: Long): Int

    @Insert
    suspend fun insert(setting: AppSettingEntity)

    /** Tanpa "ON CONFLICT DO UPDATE": Android 10 masih memakai SQLite 3.22 yang belum mengenalnya. */
    @Transaction
    suspend fun put(key: String, value: String?) {
        val now = System.currentTimeMillis()
        if (updateValue(key, value, now) == 0) insert(AppSettingEntity(key = key, value = value, createdAt = now, updatedAt = now))
    }
}

@Dao
interface ClipDao {
    /** Skor tertinggi lebih dulu; klip manual (tanpa skor) di akhir (urutan sama dengan desktop). */
    @Query("SELECT * FROM clips WHERE project_id = :projectId ORDER BY score IS NULL, score DESC, start_time")
    fun observe(projectId: Long): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE project_id = :projectId")
    suspend fun forProject(projectId: Long): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun find(id: Long): ClipEntity?

    @Query(
        "SELECT project_id, COUNT(*) AS total, SUM(review_status = 'selected') AS selected, SUM(render_status = 'done') AS rendered " +
            "FROM clips WHERE review_status != 'rejected' GROUP BY project_id",
    )
    fun observeCounts(): Flow<List<ProjectClipCount>>

    @Insert
    suspend fun insert(clip: ClipEntity): Long

    @Update
    suspend fun update(clip: ClipEntity)

    @Query("UPDATE clips SET review_status = :status, updated_at = :now WHERE id = :id")
    suspend fun setReview(id: Long, status: String, now: Long = System.currentTimeMillis())

    /** Teks transkrip di rentang ini berubah: subtitle klip yang tumpang tindih ikut berubah (hasil render jadi lama). */
    @Query("UPDATE clips SET edited_at = :now, updated_at = :now WHERE project_id = :projectId AND start_time < :end AND end_time > :start")
    suspend fun touchRange(projectId: Long, start: Double, end: Double, now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: Long)

    /** Hasil analisis yang belum disentuh (kandidat dari AI), misalnya sebelum menempel jawaban baru. */
    @Query("DELETE FROM clips WHERE project_id = :projectId AND source = 'ai' AND review_status = 'candidate'")
    suspend fun deleteUnreviewed(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM clips WHERE project_id = :projectId")
    suspend fun count(projectId: Long): Int

    @Query("SELECT * FROM clips WHERE id = :id")
    fun observeOne(id: Long): Flow<ClipEntity?>

    /** Semua kolom tampilan dari editor; edited_at menandai hasil render lama. */
    @Query(
        """UPDATE clips SET start_time = :start, end_time = :end, cuts = :cuts, subtitle_lines = :subtitleLines,
        subtitle_edited = :subtitleEdited, caption_style_id = :captionStyleId, frame_style_id = :frameStyleId,
        crop_position = :cropPosition, crop_zoom = :cropZoom, crop_y = :cropY, hook = :hook,
        show_hook_overlay = :showHookOverlay, hook_layout = :hookLayout, keywords = :keywords,
        cover_time = :coverTime, cover_path = :coverPath, cover_title = :coverTitle, cover_in_video = :coverInVideo, cover_layout = :coverLayout,
        intro_id = :introId, outro_id = :outroId, edited_at = :now, updated_at = :now WHERE id = :id""",
    )
    suspend fun updateEditor(
        id: Long, start: Double, end: Double, cuts: String?, subtitleLines: String?, subtitleEdited: Boolean,
        captionStyleId: Long?, frameStyleId: Long?, cropPosition: Int?, cropZoom: Int?, cropY: Int?, hook: String?,
        showHookOverlay: Boolean, hookLayout: String?, keywords: String?, coverTime: Double?, coverPath: String?,
        coverTitle: Boolean, coverInVideo: Boolean, coverLayout: String?, introId: Long?, outroId: Long?, now: Long = System.currentTimeMillis(),
    )

    /** Jalur "ikuti pembicara" (JSON [id.clipmi.core.render.SpeakerTrackData]); null = belum dideteksi. */
    @Query("UPDATE clips SET speaker_track = :track, updated_at = :now WHERE id = :id")
    suspend fun setSpeakerTrack(id: Long, track: String?, now: Long = System.currentTimeMillis())

    /** Hanya kolom teks, supaya status render yang berubah di latar tidak tertimpa salinan lama. */
    @Query(
        """UPDATE clips SET title = :title, hook = :hook, caption = :caption, hashtags = :hashtags, keywords = :keywords,
        edited_at = CASE WHEN :changesRender THEN :now ELSE edited_at END, updated_at = :now WHERE id = :id""",
    )
    suspend fun updateText(id: Long, title: String, hook: String?, caption: String?, hashtags: String?, keywords: String?, changesRender: Boolean, now: Long = System.currentTimeMillis())

    /** Klip yang sudah atau sedang dirender, terbaru lebih dulu (layar Hasil). */
    @Query("SELECT * FROM clips WHERE render_status != 'not_rendered' ORDER BY COALESCE(rendered_at, updated_at) DESC")
    fun observeRendered(): Flow<List<ClipEntity>>

    /** Klip untuk halaman Jadwal: terpilih, sudah dirender, atau sudah dijadwalkan. */
    @Query("SELECT * FROM clips WHERE review_status = 'selected' OR render_status = 'done' OR id IN (SELECT clip_id FROM schedules)")
    fun observeSchedulable(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE review_status = 'selected' OR render_status = 'done' OR id IN (SELECT clip_id FROM schedules)")
    suspend fun schedulable(): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE render_status IN ('queued', 'rendering')")
    suspend fun busyRenders(): List<ClipEntity>

    @Query("UPDATE clips SET render_status = :status, render_progress = :progress, render_error = :error, updated_at = :now WHERE id = :id")
    suspend fun setRender(id: Long, status: String, progress: Int?, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE clips SET render_status = 'done', render_progress = 100, render_error = NULL, output_path = :output, rendered_at = :now, updated_at = :now WHERE id = :id")
    suspend fun renderDone(id: Long, output: String, now: Long = System.currentTimeMillis())
}

data class ProjectClipCount(
    @androidx.room.ColumnInfo(name = "project_id") val projectId: Long,
    val total: Int,
    val selected: Int = 0,
    val rendered: Int = 0,
)

@Dao
interface SeriesDao {
    @Query("SELECT * FROM clip_series WHERE project_id = :projectId")
    fun observe(projectId: Long): Flow<List<ClipSeriesEntity>>

    @Query("SELECT * FROM clip_series WHERE project_id = :projectId AND title = :title LIMIT 1")
    suspend fun find(projectId: Long, title: String): ClipSeriesEntity?

    @Insert
    suspend fun insert(series: ClipSeriesEntity): Long

    /** firstOrCreate desktop: seri dengan judul yang sama di project yang sama dipakai ulang. */
    @Transaction
    suspend fun firstOrCreate(projectId: Long, title: String): Long =
        find(projectId, title)?.id ?: insert(ClipSeriesEntity(projectId = projectId, title = title))
}

@Dao
interface AiUsageDao {
    @Query("SELECT * FROM ai_usages ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<AiUsageEntity>>

    @Insert
    suspend fun insert(usage: AiUsageEntity)
}

@Dao
interface StyleDao {
    @Query("SELECT * FROM caption_styles ORDER BY name COLLATE NOCASE")
    suspend fun captionStyles(): List<CaptionStyleEntity>

    @Query("SELECT * FROM frame_styles ORDER BY name COLLATE NOCASE")
    suspend fun frameStyles(): List<FrameStyleEntity>

    @Query("SELECT * FROM caption_styles WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun captionByName(name: String): CaptionStyleEntity?

    @Query("SELECT * FROM frame_styles WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun frameByName(name: String): FrameStyleEntity?

    @Query("SELECT * FROM caption_styles ORDER BY is_default DESC, name")
    fun observeCaptionStyles(): Flow<List<CaptionStyleEntity>>

    @Query("SELECT * FROM frame_styles ORDER BY is_default DESC, name")
    fun observeFrameStyles(): Flow<List<FrameStyleEntity>>

    @Query("SELECT * FROM caption_styles WHERE id = :id")
    suspend fun captionStyle(id: Long): CaptionStyleEntity?

    @Query("SELECT * FROM frame_styles WHERE id = :id")
    suspend fun frameStyle(id: Long): FrameStyleEntity?

    /** `CaptionStyle::resolveDefault` desktop: gaya bawaan, atau gaya pertama. */
    @Query("SELECT * FROM caption_styles ORDER BY is_default DESC, id LIMIT 1")
    suspend fun defaultCaptionStyle(): CaptionStyleEntity?

    @Query("SELECT * FROM frame_styles ORDER BY is_default DESC, id LIMIT 1")
    suspend fun defaultFrameStyle(): FrameStyleEntity?

    @Insert
    suspend fun insert(style: CaptionStyleEntity): Long

    @Insert
    suspend fun insert(style: FrameStyleEntity): Long

    @Update
    suspend fun update(style: CaptionStyleEntity)

    @Update
    suspend fun update(style: FrameStyleEntity)

    @Delete
    suspend fun delete(style: CaptionStyleEntity)

    @Delete
    suspend fun delete(style: FrameStyleEntity)

    @Query("SELECT COUNT(*) FROM caption_styles WHERE name = :name COLLATE NOCASE AND id != :except")
    suspend fun captionNameTaken(name: String, except: Long): Int

    @Query("SELECT COUNT(*) FROM frame_styles WHERE name = :name COLLATE NOCASE AND id != :except")
    suspend fun frameNameTaken(name: String, except: Long): Int

    /** Gaya bawaan dipakai klip tanpa pilihan gaya; hanya satu yang bawaan. */
    @Transaction
    suspend fun makeCaptionDefault(id: Long) {
        clearCaptionDefault()
        setCaptionDefault(id)
    }

    @Transaction
    suspend fun makeFrameDefault(id: Long) {
        clearFrameDefault()
        setFrameDefault(id)
    }

    @Query("UPDATE caption_styles SET is_default = 0")
    suspend fun clearCaptionDefault()

    @Query("UPDATE caption_styles SET is_default = 1 WHERE id = :id")
    suspend fun setCaptionDefault(id: Long)

    @Query("UPDATE frame_styles SET is_default = 0")
    suspend fun clearFrameDefault()

    @Query("UPDATE frame_styles SET is_default = 1 WHERE id = :id")
    suspend fun setFrameDefault(id: Long)

    /** Klip yang memakai gaya ini, termasuk klip tanpa pilihan gaya bila ini gaya bawaan (`usage()` desktop). */
    @Query("SELECT COUNT(*) FROM clips WHERE caption_style_id = :id OR (:isDefault AND caption_style_id IS NULL)")
    suspend fun captionUsage(id: Long, isDefault: Boolean): Int

    @Query("SELECT COUNT(*) FROM clips WHERE frame_style_id = :id OR (:isDefault AND frame_style_id IS NULL)")
    suspend fun frameUsage(id: Long, isDefault: Boolean): Int
}

@Dao
interface BumperDao {
    @Query("SELECT * FROM bumpers ORDER BY kind, name")
    suspend fun all(): List<BumperEntity>

    @Query("SELECT * FROM bumpers WHERE kind = :kind AND name = :name LIMIT 1")
    suspend fun byName(kind: String, name: String): BumperEntity?

    /** Terakhir dipakai di atas, lalu terbaru. */
    @Query("SELECT * FROM bumpers ORDER BY last_used_at IS NULL, last_used_at DESC, id DESC")
    fun observeAll(): Flow<List<BumperEntity>>

    @Query("SELECT * FROM bumpers WHERE id = :id")
    suspend fun find(id: Long): BumperEntity?

    @Insert
    suspend fun insert(bumper: BumperEntity): Long

    @Update
    suspend fun update(bumper: BumperEntity)

    @Delete
    suspend fun delete(bumper: BumperEntity)

    @Query("UPDATE bumpers SET last_used_at = :now WHERE id = :id")
    suspend fun touchUsed(id: Long, now: Long = System.currentTimeMillis())
}

/** Akun sosmed, jadwal posting, akun tujuan, dan statistik postingan (tabel sama dengan desktop). */
@Dao
interface ScheduleDao {
    @Query("SELECT * FROM social_accounts ORDER BY name COLLATE NOCASE, id")
    fun observeAccounts(): Flow<List<SocialAccountEntity>>

    @Query("SELECT * FROM social_accounts ORDER BY name COLLATE NOCASE, id")
    suspend fun accounts(): List<SocialAccountEntity>

    @Query("SELECT * FROM social_accounts WHERE id = :id")
    suspend fun account(id: Long): SocialAccountEntity?

    @Insert
    suspend fun insertAccount(account: SocialAccountEntity): Long

    @Update
    suspend fun updateAccount(account: SocialAccountEntity)

    @Query("SELECT DISTINCT schedule_id FROM schedule_accounts WHERE social_account_id = :id")
    suspend fun scheduleIdsFor(id: Long): List<Long>

    @Query("DELETE FROM social_accounts WHERE id = :id")
    suspend fun deleteAccountRow(id: Long)

    @Query("DELETE FROM schedules WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM schedule_accounts WHERE schedule_id = schedules.id)")
    suspend fun deleteEmptySchedules(ids: List<Long>)

    /** Akun dihapus: dilepas dari semua jadwal; jadwal yang jadi tanpa akun dihapus (klipnya kembali ke antrean). */
    @Transaction
    suspend fun deleteAccount(id: Long) {
        val schedules = scheduleIdsFor(id)
        deleteAccountRow(id)
        if (schedules.isNotEmpty()) deleteEmptySchedules(schedules)
    }

    @Query("SELECT * FROM schedules ORDER BY scheduled_at, id")
    fun observeSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY scheduled_at, id")
    suspend fun schedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun schedule(id: Long): ScheduleEntity?

    @Insert
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    @Query("UPDATE schedules SET notified_at = :now WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>, now: Long)

    /** Jadwal yang perlu diingatkan: waktunya tiba, belum diingatkan, dan masih ada akun yang menunggu. */
    @Query(
        """SELECT * FROM schedules WHERE notified_at IS NULL AND scheduled_at <= :now
        AND EXISTS (SELECT 1 FROM schedule_accounts WHERE schedule_id = schedules.id AND status = 'pending') ORDER BY scheduled_at, id""",
    )
    suspend fun dueReminders(now: Long): List<ScheduleEntity>

    /** Waktu pengingat berikutnya (untuk alarm), atau null bila tidak ada. */
    @Query(
        """SELECT MIN(scheduled_at) FROM schedules WHERE notified_at IS NULL AND scheduled_at > :now
        AND EXISTS (SELECT 1 FROM schedule_accounts WHERE schedule_id = schedules.id AND status = 'pending')""",
    )
    suspend fun nextReminderAt(now: Long): Long?

    @Query("SELECT * FROM schedule_accounts ORDER BY id")
    fun observeTargets(): Flow<List<ScheduleAccountEntity>>

    @Query("SELECT * FROM schedule_accounts ORDER BY id")
    suspend fun allTargets(): List<ScheduleAccountEntity>

    @Query("SELECT * FROM schedule_accounts WHERE schedule_id = :scheduleId ORDER BY id")
    suspend fun targets(scheduleId: Long): List<ScheduleAccountEntity>

    @Query("SELECT * FROM schedule_accounts WHERE id = :id")
    suspend fun target(id: Long): ScheduleAccountEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertTargets(targets: List<ScheduleAccountEntity>)

    @Update
    suspend fun updateTarget(target: ScheduleAccountEntity)

    @Query("DELETE FROM schedule_accounts WHERE schedule_id = :scheduleId AND social_account_id NOT IN (:keep)")
    suspend fun deleteTargetsExcept(scheduleId: Long, keep: List<Long>)

    @Query("UPDATE schedule_accounts SET schedule_id = :to, updated_at = :now WHERE schedule_id = :from AND social_account_id IN (:accounts)")
    suspend fun moveTargets(from: Long, to: Long, accounts: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE schedule_accounts SET stats_checked_at = :at, stats_error = :error, updated_at = :at WHERE id = :id")
    suspend fun setStatsChecked(id: Long, at: Long, error: String?)

    /** Postingan terbit yang punya link dan terbit sejak [from] (kandidat pembaruan statistik otomatis). */
    @Query("SELECT * FROM schedule_accounts WHERE status = 'published' AND url IS NOT NULL AND published_at >= :from")
    suspend fun refreshCandidates(from: Long): List<ScheduleAccountEntity>

    @Query("SELECT id FROM schedule_accounts WHERE status = 'published' AND url IS NOT NULL")
    suspend fun publishedWithLink(): List<Long>

    @Query("SELECT * FROM post_stats ORDER BY recorded_at, id")
    fun observeStats(): Flow<List<PostStatEntity>>

    @Query("SELECT * FROM post_stats ORDER BY recorded_at, id")
    suspend fun allStats(): List<PostStatEntity>

    @Query("SELECT * FROM post_stats WHERE schedule_account_id = :targetId ORDER BY recorded_at DESC, id DESC LIMIT 1")
    suspend fun latestStat(targetId: Long): PostStatEntity?

    @Insert
    suspend fun insertStat(stat: PostStatEntity): Long
}

/** Laporan Analisis Performa AI. */
@Dao
interface ReportDao {
    @Query("SELECT * FROM performance_reports ORDER BY id DESC LIMIT 20")
    fun observeRecent(): Flow<List<PerformanceReportEntity>>

    @Query("SELECT * FROM performance_reports WHERE id = :id")
    fun observe(id: Long): Flow<PerformanceReportEntity?>

    @Query("SELECT * FROM performance_reports WHERE id = :id")
    suspend fun find(id: Long): PerformanceReportEntity?

    @Query("SELECT * FROM performance_reports ORDER BY id DESC")
    suspend fun all(): List<PerformanceReportEntity>

    @Insert
    suspend fun insert(report: PerformanceReportEntity): Long

    @Update
    suspend fun update(report: PerformanceReportEntity)

    @Query("DELETE FROM performance_reports WHERE id = :id")
    suspend fun delete(id: Long)
}
