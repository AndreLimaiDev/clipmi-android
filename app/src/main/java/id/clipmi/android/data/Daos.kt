package id.clipmi.android.data

import androidx.room.Dao
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

    @Query("SELECT project_id, COUNT(*) AS total FROM clips WHERE review_status != 'rejected' GROUP BY project_id")
    fun observeCounts(): Flow<List<ProjectClipCount>>

    @Insert
    suspend fun insert(clip: ClipEntity): Long

    @Update
    suspend fun update(clip: ClipEntity)

    @Query("UPDATE clips SET review_status = :status, updated_at = :now WHERE id = :id")
    suspend fun setReview(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: Long)

    /** Hasil analisis yang belum disentuh (kandidat dari AI), misalnya sebelum menempel jawaban baru. */
    @Query("DELETE FROM clips WHERE project_id = :projectId AND source = 'ai' AND review_status = 'candidate'")
    suspend fun deleteUnreviewed(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM clips WHERE project_id = :projectId")
    suspend fun count(projectId: Long): Int
}

data class ProjectClipCount(
    @androidx.room.ColumnInfo(name = "project_id") val projectId: Long,
    val total: Int,
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
