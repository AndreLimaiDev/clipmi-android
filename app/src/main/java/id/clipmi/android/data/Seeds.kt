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
    // SQL dari skema Room (app/schemas/.../4.json), supaya hasil migrasi sama persis dengan database baru.
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

    /** Gaya bawaan desktop; kolom yang tidak disebut memakai nilai bawaan kolom desktop (lihat entity). */
    fun insertStyles(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val caption = { name: String, size: Int, extra: ContentValues.() -> Unit ->
            ContentValues().apply {
                put("name", name); put("font_family", "Poppins"); put("font_size", size); put("uppercase", 0)
                put("primary_color", "#FFFFFF"); put("outline_color", "#000000"); put("highlight_active_word", 0)
                put("highlight_style", "color"); put("highlight_color", "#FFD400"); putNull("upcoming_color")
                put("keyword_highlight", 1); put("keyword_color", "#22C55E"); put("position", "bottom"); put("words_per_line", 4)
                put("animation", "none"); put("effect", "outline"); put("line_box", 0); put("line_box_color", "#000000")
                put("line_box_opacity", 65); put("is_default", 0); put("created_at", now); put("updated_at", now)
                extra()
            }
        }
        listOf(
            caption("Bawaan", 76) { put("is_default", 1) },
            caption("Karaoke Kuning", 80) { put("highlight_active_word", 1); put("words_per_line", 3) },
            caption("Kotak Biru", 120) {
                put("uppercase", 1); put("outline_color", "#0B1015"); put("highlight_active_word", 1); put("highlight_style", "box")
                put("highlight_color", "#1D4ED8"); put("upcoming_color", "#9CA3AF")
            },
        ).forEach { db.insert("caption_styles", SQLiteDatabase.CONFLICT_IGNORE, it) }

        val frame = { name: String, mode: String, background: String, extra: ContentValues.() -> Unit ->
            ContentValues().apply {
                put("name", name); put("aspect", "9:16"); put("mode", mode); put("background", background); put("blur_strength", 8)
                put("background_color", "#000000"); put("background_color_2", "#1E3A8A"); put("video_height", 65)
                put("video_position", "center"); put("zoom", 100); put("corner_radius", 0); put("border_width", 0)
                put("border_color", "#FFFFFF"); put("logo_x", 100); put("logo_y", 0); put("logo_size", 18); put("logo_opacity", 85)
                put("watermark_font", "Poppins"); put("watermark_size", 40); put("watermark_color", "#FFFFFF"); put("watermark_opacity", 80)
                put("watermark_x", 50); put("watermark_y", 100); put("title_bar", 0); put("title_bar_color", "#000000")
                put("title_text_color", "#FFFFFF"); put("is_default", 0); put("created_at", now); put("updated_at", now)
                extra()
            }
        }
        listOf(
            frame("Crop penuh", "crop", "blur") { put("is_default", 1) },
            frame("Video utuh, latar blur", "fit", "blur") {},
            frame("Video utuh, latar hitam", "fit", "color") {},
            frame("Video di atas, latar gelap", "top", "color") { put("background_color", "#0B1015"); put("video_position", "top") },
        ).forEach { db.insert("frame_styles", SQLiteDatabase.CONFLICT_IGNORE, it) }
    }

    /** Database baru (tanpa migrasi) juga mendapat gaya bawaan. */
    val onCreate = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = insertStyles(db)
    }
}
