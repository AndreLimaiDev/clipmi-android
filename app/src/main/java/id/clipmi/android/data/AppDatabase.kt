package id.clipmi.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database lokal. Nama tabel dan kolom sama dengan ClipMI desktop (snake_case), supaya Paket Standar Tim dan logika
 * inti bisa dipakai bersama. Skema diekspor ke app/schemas; setiap kenaikan [version] wajib punya Migration teruji
 * (tanpa fallbackToDestructiveMigration), karena data pengguna hanya ada di HP ini.
 */
@Database(
    entities = [
        ProjectEntity::class, TranscriptSegmentEntity::class, CorrectionEntryEntity::class, AppSettingEntity::class,
        ClipSeriesEntity::class, ClipEntity::class, AiUsageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun segments(): SegmentDao
    abstract fun corrections(): CorrectionDao
    abstract fun settings(): SettingDao
    abstract fun clips(): ClipDao
    abstract fun series(): SeriesDao
    abstract fun aiUsages(): AiUsageDao

    companion object {
        const val NAME = "clipmi.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

object Migrations {
    /**
     * v1 (kerangka Fase 0) → v2 (Fase 1): tabel projects memakai nama kolom desktop dan kolom baru; tabel
     * transcript_segments, correction_entries, dan app_settings dibuat.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `projects_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL, `source_type` TEXT NOT NULL, `source_url` TEXT, `youtube_video_id` TEXT,
                `video_path` TEXT, `preview_path` TEXT, `thumbnail_path` TEXT, `duration_seconds` REAL,
                `video_status` TEXT NOT NULL, `video_progress` INTEGER, `transcript_status` TEXT NOT NULL,
                `transcript_source` TEXT, `transcript_language` TEXT, `transcription_message` TEXT,
                `transcription_resume_at` INTEGER, `error_message` TEXT, `source_deleted_at` INTEGER,
                `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """INSERT INTO `projects_new` (`id`, `title`, `source_type`, `source_url`, `video_path`, `thumbnail_path`,
                `duration_seconds`, `video_status`, `transcript_status`, `created_at`, `updated_at`)
                SELECT `id`, `title`, `sourceType`, `sourceUrl`, `videoPath`, `thumbnailPath`, `durationSeconds`,
                `videoStatus`, `transcriptStatus`, `createdAt`, `createdAt` FROM `projects`""",
            )
            db.execSQL("DROP TABLE `projects`")
            db.execSQL("ALTER TABLE `projects_new` RENAME TO `projects`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_youtube_video_id` ON `projects` (`youtube_video_id`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `transcript_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `project_id` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, `start_time` REAL NOT NULL,
                `end_time` REAL NOT NULL, `text` TEXT NOT NULL, `words` TEXT, `words_estimated` INTEGER NOT NULL,
                FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_segments_project_id_sequence` " +
                    "ON `transcript_segments` (`project_id`, `sequence`)",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `correction_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `wrong_text` TEXT NOT NULL COLLATE NOCASE, `correct_text` TEXT NOT NULL, `is_active` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_correction_entries_wrong_text` ON `correction_entries` (`wrong_text`)",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `key` TEXT NOT NULL, `value` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_app_settings_key` ON `app_settings` (`key`)")
        }
    }

    /** v2 → v3 (Fase 2): status analisis di projects, tabel clip_series, clips, dan ai_usages. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (column in listOf("`analysis_status` TEXT", "`analysis_message` TEXT", "`analysis_parts` TEXT", "`analysis_heartbeat_at` INTEGER")) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN $column")
            }
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `clip_series` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `project_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_clip_series_project_id_title` ON `clip_series` (`project_id`, `title`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `clips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `project_id` INTEGER NOT NULL,
                `clip_series_id` INTEGER, `part_number` INTEGER, `title` TEXT NOT NULL, `start_time` REAL NOT NULL,
                `end_time` REAL NOT NULL, `score` INTEGER, `hook` TEXT, `caption` TEXT, `hashtags` TEXT, `keywords` TEXT,
                `reason` TEXT, `review_status` TEXT NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                FOREIGN KEY(`clip_series_id`) REFERENCES `clip_series`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_project_id_review_status` ON `clips` (`project_id`, `review_status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_clip_series_id` ON `clips` (`clip_series_id`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ai_usages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `purpose` TEXT NOT NULL,
                `project_id` INTEGER, `project_title` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL,
                `input_tokens` INTEGER NOT NULL, `output_tokens` INTEGER NOT NULL, `audio_seconds` REAL NOT NULL, `cost` REAL,
                `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_usages_created_at` ON `ai_usages` (`created_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_usages_project_id` ON `ai_usages` (`project_id`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
