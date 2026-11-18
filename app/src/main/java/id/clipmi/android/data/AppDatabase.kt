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
        ClipSeriesEntity::class, ClipEntity::class, AiUsageEntity::class, CaptionStyleEntity::class, FrameStyleEntity::class,
        BumperEntity::class, SocialAccountEntity::class, ScheduleEntity::class, ScheduleAccountEntity::class, PostStatEntity::class,
        PerformanceReportEntity::class,
    ],
    version = 9,
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
    abstract fun bumpers(): BumperDao
    abstract fun styles(): StyleDao
    abstract fun schedules(): ScheduleDao
    abstract fun reports(): ReportDao

    companion object {
        const val NAME = "clipmi.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(Seeds.onCreate)
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


    /**
     * v3 → v4 (Fase 3): tabel caption_styles dan frame_styles (dengan gaya bawaan desktop), kolom editor dan render
     * klip, serta posisi crop project. Tabel clips dibuat ulang karena kunci asing ke tabel gaya tidak bisa ditambah
     * lewat ALTER TABLE.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `projects` ADD COLUMN `crop_position` INTEGER NOT NULL DEFAULT 50")
            Seeds.createStyleTables(db)
            Seeds.insertStyles(db)

            db.execSQL(Seeds.CLIPS_TABLE_V4.replace("`clips`", "`clips_new`"))
            db.execSQL(
                """INSERT INTO `clips_new` (`id`, `project_id`, `clip_series_id`, `part_number`, `title`, `start_time`, `end_time`,
                `score`, `hook`, `caption`, `hashtags`, `keywords`, `reason`, `review_status`, `source`, `subtitle_edited`,
                `show_hook_overlay`, `render_status`, `created_at`, `updated_at`)
                SELECT `id`, `project_id`, `clip_series_id`, `part_number`, `title`, `start_time`, `end_time`, `score`, `hook`,
                `caption`, `hashtags`, `keywords`, `reason`, `review_status`, `source`, 0, 1, 'not_rendered', `created_at`,
                `updated_at` FROM `clips`""",
            )
            db.execSQL("DROP TABLE `clips`")
            db.execSQL("ALTER TABLE `clips_new` RENAME TO `clips`")
            Seeds.CLIPS_INDICES.forEach(db::execSQL)
        }
    }

    /** Fase 3d: pustaka intro/outro (bumpers), sampul klip, intro/outro per klip dan bawaan project. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bumpers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`path` TEXT NOT NULL, `duration` REAL NOT NULL, `thumbnail_path` TEXT, `last_used_at` INTEGER, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bumpers_kind_last_used_at` ON `bumpers` (`kind`, `last_used_at`)")
            val bumper = "INTEGER REFERENCES `bumpers`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
            listOf(
                "`cover_time` REAL", "`cover_path` TEXT", "`cover_title` INTEGER NOT NULL DEFAULT 0", "`cover_in_video` INTEGER NOT NULL DEFAULT 1",
                "`intro_id` $bumper", "`outro_id` $bumper",
            ).forEach { db.execSQL("ALTER TABLE `clips` ADD COLUMN $it") }
            listOf("`intro_id` $bumper", "`outro_id` $bumper").forEach { db.execSQL("ALTER TABLE `projects` ADD COLUMN $it") }
            for (table in listOf("clips", "projects")) {
                for (column in listOf("intro_id", "outro_id")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_$column` ON `$table` (`$column`)")
                }
            }
        }
    }

    /** Fase 4: akun sosmed, jadwal posting, akun tujuan jadwal, dan statistik postingan (tabel baru saja). */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `social_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `platform` TEXT NOT NULL, " +
                    "`handle` TEXT, `color` TEXT NOT NULL, `slots` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clip_id` INTEGER NOT NULL, `scheduled_at` INTEGER NOT NULL, " +
                    "`caption` TEXT, `hashtags` TEXT, `note` TEXT, `notified_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`clip_id`) REFERENCES `clips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_schedules_clip_id` ON `schedules` (`clip_id`)",
                "CREATE INDEX IF NOT EXISTS `index_schedules_scheduled_at` ON `schedules` (`scheduled_at`)",
                "CREATE TABLE IF NOT EXISTS `schedule_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `schedule_id` INTEGER NOT NULL, " +
                    "`social_account_id` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'pending', `url` TEXT, `published_at` INTEGER, `stats_checked_at` INTEGER, " +
                    "`stats_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`schedule_id`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`social_account_id`) REFERENCES `social_accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_schedule_accounts_schedule_id_social_account_id` ON `schedule_accounts` (`schedule_id`, `social_account_id`)",
                "CREATE INDEX IF NOT EXISTS `index_schedule_accounts_social_account_id` ON `schedule_accounts` (`social_account_id`)",
                "CREATE TABLE IF NOT EXISTS `post_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `schedule_account_id` INTEGER NOT NULL, `views` INTEGER, " +
                    "`likes` INTEGER, `comments` INTEGER, `shares` INTEGER, `source` TEXT NOT NULL, `recorded_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, FOREIGN KEY(`schedule_account_id`) REFERENCES `schedule_accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_post_stats_schedule_account_id_recorded_at` ON `post_stats` (`schedule_account_id`, `recorded_at`)",
            ).forEach(db::execSQL)
        }
    }

    /** Fase 5: laporan Analisis Performa AI (tabel baru saja). */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `performance_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `status` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `posts_count` INTEGER NOT NULL, `digest` TEXT NOT NULL, `result` TEXT, `error` TEXT, `provider` TEXT, " +
                    "`model` TEXT, `input_tokens` INTEGER, `output_tokens` INTEGER, `cost` REAL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
            )
        }
    }

    /** Ikuti penceramah: jalur crop per klip. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `clips` ADD COLUMN `speaker_track` TEXT")
        }
    }

    /** Model judul sampul (khusus Android). */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `clips` ADD COLUMN `cover_layout` TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}
