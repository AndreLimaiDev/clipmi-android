package id.clipmi.android.domain

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.core.settings.PackPreview
import id.clipmi.core.settings.ParsedPack
import id.clipmi.core.settings.TeamPack
import id.clipmi.core.settings.TeamPack.flag
import id.clipmi.core.settings.TeamPack.number
import id.clipmi.core.settings.TeamPack.text
import id.clipmi.core.settings.TeamPackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Paket Standar Tim di HP (`TeamPack` desktop): ekspor ke file JSON yang sama dengan desktop, baca dan pratinjau paket,
 * lalu terapkan. Entri dengan nama gaya atau kata salah yang sama diperbarui, entri baru ditambahkan, entri lokal lain
 * tidak dihapus. API key tidak ikut.
 */
class TeamPacks(
    private val context: Context,
    private val db: AppDatabase,
    private val workspace: Workspace,
    private val settings: Settings,
    private val media: MediaLibrary,
) {
    /** Hasil penerapan: jumlah intro/outro yang gagal dipasang (misalnya WebM yang tidak bisa diputar HP). */
    data class Applied(val bumpersFailed: Int)

    fun fileName(): String = "ClipMI-paket-tim-${java.time.LocalDate.now()}.json"

    /** Menulis paket ke [uri] (dipilih pengguna lewat pemilih file sistem). */
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), build())
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("File tujuan tidak bisa ditulis.")
    }

    private suspend fun build(): JsonObject {
        val styles = db.styles()
        return buildJsonObject {
            put("format", TeamPack.FORMAT)
            put("version", TeamPack.VERSION)
            put("exported_at", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("prompt_template", settings.promptTemplate())
            put("prompt_max_tokens", settings.promptMaxTokens())
            put("caption_styles", buildJsonArray { styles.captionStyles().forEach { add(captionJson(it)) } })
            put("frame_styles", buildJsonArray { styles.frameStyles().forEach { add(frameJson(it)) } })
            put(
                "bumpers",
                buildJsonArray {
                    db.bumpers().all().forEach { b ->
                        exportFile(b.path, TeamPack.VIDEO_MAX_BYTES)?.let { video ->
                            add(buildJsonObject { put("kind", b.kind); put("name", b.name); put("duration", b.duration); put("video", video) })
                        }
                    }
                },
            )
            put(
                "corrections",
                buildJsonArray {
                    db.corrections().all().forEach { e -> add(buildJsonObject { put("wrong_text", e.wrongText); put("correct_text", e.correctText); put("is_active", e.isActive) }) }
                },
            )
        }
    }

    private fun captionJson(s: CaptionStyleEntity) = buildJsonObject {
        put("name", s.name); put("font_family", s.fontFamily); put("font_size", s.fontSize); put("uppercase", s.uppercase)
        put("primary_color", s.primaryColor); put("outline_color", s.outlineColor); put("highlight_active_word", s.highlightActiveWord)
        put("highlight_style", s.highlightStyle); put("highlight_color", s.highlightColor); put("upcoming_color", s.upcomingColor)
        put("keyword_highlight", s.keywordHighlight); put("keyword_color", s.keywordColor); put("position", s.position)
        put("words_per_line", s.wordsPerLine); put("animation", s.animation); put("effect", s.effect); put("line_box", s.lineBox)
        put("line_box_color", s.lineBoxColor); put("line_box_opacity", s.lineBoxOpacity); put("is_default", s.isDefault)
    }

    private fun frameJson(s: FrameStyleEntity) = buildJsonObject {
        put("name", s.name); put("aspect", s.aspect); put("mode", s.mode); put("background", s.background); put("blur_strength", s.blurStrength)
        put("background_color", s.backgroundColor); put("background_color_2", s.backgroundColor2); put("video_height", s.videoHeight)
        put("video_position", s.videoPosition); put("zoom", s.zoom); put("corner_radius", s.cornerRadius); put("border_width", s.borderWidth)
        put("border_color", s.borderColor); put("logo_x", s.logoX); put("logo_y", s.logoY); put("logo_size", s.logoSize); put("logo_opacity", s.logoOpacity)
        put("watermark_text", s.watermarkText); put("watermark_font", s.watermarkFont); put("watermark_size", s.watermarkSize)
        put("watermark_color", s.watermarkColor); put("watermark_opacity", s.watermarkOpacity); put("watermark_x", s.watermarkX); put("watermark_y", s.watermarkY)
        put("title_bar", s.titleBar); put("title_bar_color", s.titleBarColor); put("title_text_color", s.titleTextColor); put("subtitle_y", s.subtitleY)
        put("template_area", s.templateArea?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
        put("is_default", s.isDefault)
        put("logo", exportFile(s.logoPath, TeamPack.LOGO_MAX_BYTES) ?: JsonNull)
        put("background_image", exportFile(s.backgroundPath, TeamPack.BACKGROUND_MAX_BYTES) ?: JsonNull)
        put("template_image", exportFile(s.templatePath, TeamPack.BACKGROUND_MAX_BYTES) ?: JsonNull)
    }

    private fun exportFile(path: String?, maxBytes: Int): JsonObject? {
        val file = workspace.file(path)?.takeIf { it.isFile && it.length() <= maxBytes } ?: return null
        return buildJsonObject { put("extension", file.extension.lowercase()); put("data", Base64.getEncoder().encodeToString(file.readBytes())) }
    }

    /** Membaca paket dari [uri]. @throws TeamPackException */
    suspend fun read(uri: Uri): ParsedPack = withContext(Dispatchers.IO) {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            ?: throw TeamPackException("File paket tidak bisa dibuka.")
        TeamPack.parse(text)
    }

    suspend fun preview(pack: ParsedPack): PackPreview = TeamPack.preview(
        pack,
        db.styles().captionStyles().map { it.name },
        db.styles().frameStyles().map { it.name },
        db.corrections().all().map { it.wrongText },
    )

    /** Menerapkan paket (`TeamPack::apply`). */
    suspend fun apply(pack: ParsedPack): Applied {
        val now = System.currentTimeMillis()
        val styles = db.styles()
        db.withTransaction {
            if (pack.promptTemplate != null || pack.promptMaxTokens != null) {
                settings.savePrompt(pack.promptTemplate ?: settings.promptTemplate(), pack.promptMaxTokens?.toInt() ?: settings.promptMaxTokens())
            }
            var defaultCaption: Long? = null
            for (data in pack.captionStyles) {
                val name = data.text("name")
                val old = styles.captionByName(name)
                val merged = caption(old ?: CaptionStyleEntity(name = name), data).copy(isDefault = old?.isDefault ?: false, updatedAt = now)
                val id = if (old != null) merged.id.also { styles.update(merged) } else styles.insert(merged)
                if (data.flag("is_default") == true) defaultCaption = id
            }
            defaultCaption?.let { styles.makeCaptionDefault(it) }
            for (data in pack.correctionsFor()) {
                val old = db.corrections().findByWrong(data.text("wrong_text"))
                val entry = CorrectionEntryEntity(
                    id = old?.id ?: 0, wrongText = data.text("wrong_text"), correctText = data.text("correct_text"),
                    isActive = data.flag("is_active") ?: old?.isActive ?: true, createdAt = old?.createdAt ?: now, updatedAt = now,
                )
                if (old != null) db.corrections().update(entry) else db.corrections().insert(entry)
            }
        }

        // Gambar dan video ditulis di luar transaksi (berkas), lalu barisnya diperbarui.
        var defaultFrame: Long? = null
        pack.frameStyles.forEachIndexed { index, data ->
            val name = data.text("name")
            val old = styles.frameByName(name)
            var style = frame(old ?: FrameStyleEntity(name = name, mode = "crop"), data).copy(isDefault = old?.isDefault ?: false, updatedAt = now)
            for ((key, file) in pack.frameImages[index]) {
                val kind = when (key) { "logo" -> MediaLibrary.ImageKind.Logo; "background_image" -> MediaLibrary.ImageKind.Background; else -> MediaLibrary.ImageKind.Template }
                val current = when (key) { "logo" -> style.logoPath; "background_image" -> style.backgroundPath; else -> style.templatePath }
                media.delete(current)
                val path = file?.let { image -> runCatching { withTemp(image.extension, image.bytes()) { media.importImage(Uri.fromFile(it), kind) } }.getOrNull() }
                style = when (key) { "logo" -> style.copy(logoPath = path); "background_image" -> style.copy(backgroundPath = path); else -> style.copy(templatePath = path) }
            }
            val id = if (old != null) style.id.also { styles.update(style) } else styles.insert(style)
            if (data.flag("is_default") == true) defaultFrame = id
        }
        defaultFrame?.let { styles.makeFrameDefault(it) }

        var failed = 0
        for (b in pack.bumpers) {
            val imported = runCatching { withTemp(b.video.extension, b.video.bytes()) { media.importBumper(b.kind, Uri.fromFile(it)) } }.getOrNull()
            if (imported == null) {
                failed++
                continue
            }
            val existing = db.bumpers().byName(b.kind, b.name)
            if (existing != null) {
                // Intro/outro dengan jenis dan nama yang sama diganti videonya (id tetap, jadi klip yang memakainya ikut).
                media.delete(existing.path)
                media.delete(existing.thumbnailPath)
                db.bumpers().update(existing.copy(path = imported.path, thumbnailPath = imported.thumbnailPath, duration = b.duration, updatedAt = now))
                db.bumpers().delete(imported)
            } else {
                db.bumpers().update(imported.copy(name = b.name, duration = b.duration))
            }
        }
        return Applied(failed)
    }

    private fun ParsedPack.correctionsFor() = corrections

    private suspend fun <T> withTemp(extension: String, bytes: ByteArray, block: suspend (File) -> T): T {
        val dir = File(context.cacheDir, "pack").apply { mkdirs() }
        // Nama berkas dipakai sebagai judul sementara; diganti nama dari paket setelahnya.
        val file = File(dir, "paket-${System.nanoTime()}.$extension")
        file.writeBytes(bytes)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun caption(base: CaptionStyleEntity, d: JsonObject) = base.copy(
        name = d.text("name"),
        fontFamily = d.str("font_family") ?: base.fontFamily,
        fontSize = d.number("font_size") ?: base.fontSize,
        uppercase = d.flag("uppercase") ?: base.uppercase,
        primaryColor = d.str("primary_color") ?: base.primaryColor,
        outlineColor = d.str("outline_color") ?: base.outlineColor,
        highlightActiveWord = d.flag("highlight_active_word") ?: base.highlightActiveWord,
        highlightStyle = d.str("highlight_style") ?: base.highlightStyle,
        highlightColor = d.str("highlight_color") ?: base.highlightColor,
        upcomingColor = if ("upcoming_color" in d) d.str("upcoming_color") else base.upcomingColor,
        keywordHighlight = d.flag("keyword_highlight") ?: base.keywordHighlight,
        keywordColor = d.str("keyword_color") ?: base.keywordColor,
        position = d.str("position") ?: base.position,
        wordsPerLine = d.number("words_per_line") ?: base.wordsPerLine,
        animation = d.str("animation") ?: base.animation,
        effect = d.str("effect") ?: base.effect,
        lineBox = d.flag("line_box") ?: base.lineBox,
        lineBoxColor = d.str("line_box_color") ?: base.lineBoxColor,
        lineBoxOpacity = d.number("line_box_opacity") ?: base.lineBoxOpacity,
    )

    private fun frame(base: FrameStyleEntity, d: JsonObject) = base.copy(
        name = d.text("name"),
        aspect = d.str("aspect") ?: base.aspect,
        mode = d.str("mode") ?: base.mode,
        background = d.str("background") ?: base.background,
        blurStrength = d.number("blur_strength") ?: base.blurStrength,
        backgroundColor = d.str("background_color") ?: base.backgroundColor,
        backgroundColor2 = d.str("background_color_2") ?: base.backgroundColor2,
        videoHeight = d.number("video_height") ?: base.videoHeight,
        videoPosition = d.str("video_position") ?: base.videoPosition,
        zoom = d.number("zoom") ?: base.zoom,
        cornerRadius = d.number("corner_radius") ?: base.cornerRadius,
        borderWidth = d.number("border_width") ?: base.borderWidth,
        borderColor = d.str("border_color") ?: base.borderColor,
        logoX = d.number("logo_x") ?: base.logoX,
        logoY = d.number("logo_y") ?: base.logoY,
        logoSize = d.number("logo_size") ?: base.logoSize,
        logoOpacity = d.number("logo_opacity") ?: base.logoOpacity,
        watermarkText = if ("watermark_text" in d) d.str("watermark_text")?.ifEmpty { null } else base.watermarkText,
        watermarkFont = d.str("watermark_font") ?: base.watermarkFont,
        watermarkSize = d.number("watermark_size") ?: base.watermarkSize,
        watermarkColor = d.str("watermark_color") ?: base.watermarkColor,
        watermarkOpacity = d.number("watermark_opacity") ?: base.watermarkOpacity,
        watermarkX = d.number("watermark_x") ?: base.watermarkX,
        watermarkY = d.number("watermark_y") ?: base.watermarkY,
        titleBar = d.flag("title_bar") ?: base.titleBar,
        titleBarColor = d.str("title_bar_color") ?: base.titleBarColor,
        titleTextColor = d.str("title_text_color") ?: base.titleTextColor,
        subtitleY = if ("subtitle_y" in d) d.number("subtitle_y") else base.subtitleY,
        templateArea = if ("template_area" in d) (d["template_area"] as? JsonObject)?.toString() else base.templateArea,
    )

    /** Nilai teks kolom, atau null bila tidak ada / null. */
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    @Suppress("unused")
    private fun JsonElement?.isNullish() = this == null || this is JsonNull
}
