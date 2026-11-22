package id.clipmi.android.data

/**
 * Gaya bawaan desktop (Bawaan, Karaoke Kuning, Kotak Biru; Crop penuh, Video utuh latar blur/hitam, Video di atas latar
 * gelap) sebagai baris kolom → nilai. Kolom yang tidak disebut memakai nilai bawaan kolom (lihat entity). Android
 * memasukkannya lewat ContentValues (migrasi 3 → 4 dan database baru), iOS lewat SQL saat database dibuat.
 */
object DefaultStyles {
    fun captionStyles(now: Long): List<Map<String, Any?>> {
        val caption = { name: String, size: Int, extra: Map<String, Any?> ->
            linkedMapOf<String, Any?>(
                "name" to name, "font_family" to "Poppins", "font_size" to size, "uppercase" to 0,
                "primary_color" to "#FFFFFF", "outline_color" to "#000000", "highlight_active_word" to 0,
                "highlight_style" to "color", "highlight_color" to "#FFD400", "upcoming_color" to null,
                "keyword_highlight" to 1, "keyword_color" to "#22C55E", "position" to "bottom", "words_per_line" to 4,
                "animation" to "none", "effect" to "outline", "line_box" to 0, "line_box_color" to "#000000",
                "line_box_opacity" to 65, "is_default" to 0, "created_at" to now, "updated_at" to now,
            ).apply { putAll(extra) }
        }
        return listOf(
            caption("Bawaan", 76, mapOf("is_default" to 1)),
            caption("Karaoke Kuning", 80, mapOf("highlight_active_word" to 1, "words_per_line" to 3)),
            caption(
                "Kotak Biru", 120,
                mapOf(
                    "uppercase" to 1, "outline_color" to "#0B1015", "highlight_active_word" to 1, "highlight_style" to "box",
                    "highlight_color" to "#1D4ED8", "upcoming_color" to "#9CA3AF",
                ),
            ),
        )
    }

    fun frameStyles(now: Long): List<Map<String, Any?>> {
        val frame = { name: String, mode: String, background: String, extra: Map<String, Any?> ->
            linkedMapOf<String, Any?>(
                "name" to name, "aspect" to "9:16", "mode" to mode, "background" to background, "blur_strength" to 8,
                "background_color" to "#000000", "background_color_2" to "#1E3A8A", "video_height" to 65,
                "video_position" to "center", "zoom" to 100, "corner_radius" to 0, "border_width" to 0,
                "border_color" to "#FFFFFF", "logo_x" to 100, "logo_y" to 0, "logo_size" to 18, "logo_opacity" to 85,
                "watermark_font" to "Poppins", "watermark_size" to 40, "watermark_color" to "#FFFFFF", "watermark_opacity" to 80,
                "watermark_x" to 50, "watermark_y" to 100, "title_bar" to 0, "title_bar_color" to "#000000",
                "title_text_color" to "#FFFFFF", "is_default" to 0, "created_at" to now, "updated_at" to now,
            ).apply { putAll(extra) }
        }
        return listOf(
            frame("Crop penuh", "crop", "blur", mapOf("is_default" to 1)),
            frame("Video utuh, latar blur", "fit", "blur", emptyMap()),
            frame("Video utuh, latar hitam", "fit", "color", emptyMap()),
            frame("Video di atas, latar gelap", "top", "color", mapOf("background_color" to "#0B1015", "video_position" to "top")),
        )
    }

    /** `INSERT OR IGNORE` untuk satu baris (teks di-escape, angka apa adanya). */
    fun insertSql(table: String, row: Map<String, Any?>): String {
        val columns = row.keys.joinToString(", ") { "`$it`" }
        val values = row.values.joinToString(", ") { value ->
            when (value) {
                null -> "NULL"
                is Number -> value.toString()
                else -> "'" + value.toString().replace("'", "''") + "'"
            }
        }
        return "INSERT OR IGNORE INTO `$table` ($columns) VALUES ($values)"
    }
}
