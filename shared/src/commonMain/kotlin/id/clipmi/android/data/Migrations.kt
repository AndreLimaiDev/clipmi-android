package id.clipmi.android.data

/** Versi 10 → 11: kotak sorot kata yang diatur, crop bawaan gaya bingkai, dan video penuh di balik template. */
val MIGRATION_10_11_SQL = listOf(
    "ALTER TABLE `caption_styles` ADD COLUMN `highlight_radius` INTEGER",
    "ALTER TABLE `caption_styles` ADD COLUMN `highlight_height` INTEGER",
    "ALTER TABLE `frame_styles` ADD COLUMN `crop_x` INTEGER",
    "ALTER TABLE `frame_styles` ADD COLUMN `crop_y` INTEGER",
    "ALTER TABLE `frame_styles` ADD COLUMN `template_fill` TEXT",
)

/** Versi 11 → 12: lebar dan posisi tegak kotak sorot kata. */
val MIGRATION_11_12_SQL = listOf(
    "ALTER TABLE `caption_styles` ADD COLUMN `highlight_width` INTEGER",
    "ALTER TABLE `caption_styles` ADD COLUMN `highlight_y` INTEGER",
)
