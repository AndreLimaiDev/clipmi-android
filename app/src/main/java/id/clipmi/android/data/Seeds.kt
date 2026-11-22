package id.clipmi.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Tabel gaya dan gaya bawaan, sama dengan yang dibuat migrasi desktop (Bawaan, Karaoke Kuning, Kotak Biru; Crop penuh,
 * Video utuh latar blur/hitam, Video di atas latar gelap). Dipakai migrasi 3 → 4 dan pembuatan database baru.
 */
object Seeds {
    // SQL dari skema Room (shared/schemas/.../4.json), supaya hasil migrasi sama persis dengan database baru.
    private const val CAPTION_STYLES_TABLE = "CREATE TABLE IF NOT EXISTS `caption_styles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `font_family` TEXT NOT NULL, `font_size` INTEGER NOT NULL, `uppercase` INTEGER NOT NULL, `primary_color` TEXT NOT NULL, `outline_color` TEXT NOT NULL, `highlight_active_word` INTEGER NOT NULL, `highlight_style` TEXT NOT NULL, `highlight_color` TEXT NOT NULL, `upcoming_color` TEXT, `keyword_highlight` INTEGER NOT NULL, `keyword_color` TEXT NOT NULL, `position` TEXT NOT NULL, `words_per_line` INTEGER NOT NULL, `animation` TEXT NOT NULL, `effect` TEXT NOT NULL, `line_box` INTEGER NOT NULL, `line_box_color` TEXT NOT NULL, `line_box_opacity` INTEGER NOT NULL, `is_default` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"

    private const val FRAME_STYLES_TABLE = "CREATE TABLE IF NOT EXISTS `frame_styles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `aspect` TEXT NOT NULL, `mode` TEXT NOT NULL, `background` TEXT NOT NULL, `blur_strength` INTEGER NOT NULL, `background_color` TEXT NOT NULL, `background_color_2` TEXT NOT NULL, `background_path` TEXT, `template_path` TEXT, `template_area` TEXT, `video_height` INTEGER NOT NULL, `video_position` TEXT NOT NULL, `zoom` INTEGER NOT NULL, `corner_radius` INTEGER NOT NULL, `border_width` INTEGER NOT NULL, `border_color` TEXT NOT NULL, `logo_path` TEXT, `logo_x` INTEGER NOT NULL, `logo_y` INTEGER NOT NULL, `logo_size` INTEGER NOT NULL, `logo_opacity` INTEGER NOT NULL, `watermark_text` TEXT, `watermark_font` TEXT NOT NULL, `watermark_size` INTEGER NOT NULL, `watermark_color` TEXT NOT NULL, `watermark_opacity` INTEGER NOT NULL, `watermark_x` INTEGER NOT NULL, `watermark_y` INTEGER NOT NULL, `title_bar` INTEGER NOT NULL, `title_bar_color` TEXT NOT NULL, `title_text_color` TEXT NOT NULL, `subtitle_y` INTEGER, `is_default` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"

    const val CLIPS_TABLE_V4 = "CREATE TABLE IF NOT EXISTS `clips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `project_id` INTEGER NOT NULL, `clip_series_id` INTEGER, `caption_style_id` INTEGER, `frame_style_id` INTEGER, `crop_position` INTEGER, `crop_zoom` INTEGER, `crop_y` INTEGER, `cuts` TEXT, `part_number` INTEGER, `title` TEXT NOT NULL, `start_time` REAL NOT NULL, `end_time` REAL NOT NULL, `score` INTEGER, `hook` TEXT, `caption` TEXT, `hashtags` TEXT, `keywords` TEXT, `reason` TEXT, `review_status` TEXT NOT NULL, `source` TEXT NOT NULL, `subtitle_lines` TEXT, `subtitle_edited` INTEGER NOT NULL, `show_hook_overlay` INTEGER NOT NULL, `hook_layout` TEXT, `render_status` TEXT NOT NULL, `render_progress` INTEGER, `render_error` TEXT, `output_path` TEXT, `rendered_at` INTEGER, `edited_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`clip_series_id`) REFERENCES `clip_series`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`caption_style_id`) REFERENCES `caption_styles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`frame_style_id`) REFERENCES `frame_styles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"

    val CLIPS_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS `index_clips_project_id_review_status` ON `clips` (`project_id`, `review_status`)",
        "CREATE INDEX IF NOT EXISTS `index_clips_clip_series_id` ON `clips` (`clip_series_id`)",
        "CREATE INDEX IF NOT EXISTS `index_clips_caption_style_id` ON `clips` (`caption_style_id`)",
        "CREATE INDEX IF NOT EXISTS `index_clips_frame_style_id` ON `clips` (`frame_style_id`)",
    )

    fun createStyleTables(db: SupportSQLiteDatabase) {
        db.execSQL(CAPTION_STYLES_TABLE)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caption_styles_name` ON `caption_styles` (`name`)")
        db.execSQL(FRAME_STYLES_TABLE)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_frame_styles_name` ON `frame_styles` (`name`)")
    }

    /** Gaya bawaan desktop ([DefaultStyles], sama dengan iOS). */
    fun insertStyles(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        DefaultStyles.captionStyles(now).forEach { db.insert("caption_styles", SQLiteDatabase.CONFLICT_IGNORE, it.toContentValues()) }
        DefaultStyles.frameStyles(now).forEach { db.insert("frame_styles", SQLiteDatabase.CONFLICT_IGNORE, it.toContentValues()) }
    }

    private fun Map<String, Any?>.toContentValues() = ContentValues().also { values ->
        for ((column, value) in this) {
            when (value) {
                null -> values.putNull(column)
                is Int -> values.put(column, value)
                is Long -> values.put(column, value)
                is String -> values.put(column, value)
                else -> error("Tipe nilai $column tidak didukung: $value")
            }
        }
    }

    /** Database baru (tanpa migrasi) juga mendapat gaya bawaan. */
    val onCreate = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = insertStyles(db)
    }
}
