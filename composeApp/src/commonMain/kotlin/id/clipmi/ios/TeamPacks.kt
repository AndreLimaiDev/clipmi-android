package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.render.Fonts
import id.clipmi.core.settings.PackPreview
import id.clipmi.core.settings.ParsedPack
import id.clipmi.core.settings.TeamPack
import id.clipmi.core.settings.TeamPack.flag
import id.clipmi.core.settings.TeamPack.number
import id.clipmi.core.settings.TeamPack.text
import id.clipmi.core.settings.TeamPackException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Paket Standar Tim di iPhone (padanan TeamPacks Android, `TeamPack` desktop): ekspor ke file JSON yang sama, baca dan
 * pratinjau paket, lalu terapkan. Entri dengan nama gaya atau kata salah yang sama diperbarui, entri baru ditambahkan,
 * entri lokal lain tidak dihapus. API key tidak ikut.
 */
class IosTeamPacks(
    private val repo: Repository,
    private val renderer: IosRenderer,
    private val settings: IosSettings,
    private val bumpers: IosBumpers,
) {
    private val db get() = repo.db
    private val engine get() = renderer.engine

    data class Applied(val bumpersFailed: Int)

    @OptIn(ExperimentalTime::class)
    private fun today(): String {
        val now = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${now.year}-${pad(now.month.number)}-${pad(now.day)}"
    }

    @OptIn(ExperimentalTime::class)
    private fun exportedAt(): String {
        val instant = Instant.fromEpochMilliseconds(nowMillis() / 1000 * 1000)
        val zone = TimeZone.currentSystemDefault()
        val t = instant.toLocalDateTime(zone)
        val offset = zone.offsetAt(instant).toString().let { if (it == "Z") "+00:00" else it }
        return "${t.year}-${pad(t.month.number)}-${pad(t.day)}T${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}$offset"
    }

    private fun pad(value: Int) = value.toString().padStart(2, '0')

    /** Menulis paket ke file sementara lalu membuka lembar bagikan (simpan ke Files, AirDrop, WhatsApp, dll.). */
    suspend fun export() {
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), build())
        createDirectory(repo.absolute("tmp"))
        val path = repo.absolute("tmp/ClipMI-paket-tim-${today()}.json")
        writeText(path, json)
        engine.shareFile(path)
    }

    private suspend fun build(): JsonObject {
        val styles = db.styles()
        return buildJsonObject {
            put("format", TeamPack.FORMAT)
            put("version", TeamPack.VERSION)
            put("exported_at", exportedAt())
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
        put("name", s.name); put("font_family", s.fontFamily.takeIf { it in Fonts.AVAILABLE } ?: Fonts.DEFAULT)
        put("font_size", s.fontSize); put("uppercase", s.uppercase)
        put("primary_color", s.primaryColor); put("outline_color", s.outlineColor); put("highlight_active_word", s.highlightActiveWord)
        put("highlight_style", s.highlightStyle); put("highlight_color", s.highlightColor); put("upcoming_color", s.upcomingColor)
        put("keyword_highlight", s.keywordHighlight); put("keyword_color", s.keywordColor); put("position", s.position)
        put("words_per_line", s.wordsPerLine); put("animation", s.animation); put("effect", s.effect); put("line_box", s.lineBox)
        put("line_box_color", s.lineBoxColor); put("line_box_opacity", s.lineBoxOpacity); put("is_default", s.isDefault)
        put("highlight_radius", s.highlightRadius); put("highlight_height", s.highlightHeight)
        put("highlight_width", s.highlightWidth); put("highlight_y", s.highlightY)
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
        put("crop_x", s.cropX); put("crop_y", s.cropY); put("template_fill", s.templateFill)
        put("logo", exportFile(s.logoPath, TeamPack.LOGO_MAX_BYTES) ?: JsonNull)
        put("background_image", exportFile(s.backgroundPath, TeamPack.BACKGROUND_MAX_BYTES) ?: JsonNull)
        put("template_image", exportFile(s.templatePath, TeamPack.BACKGROUND_MAX_BYTES) ?: JsonNull)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun exportFile(path: String?, maxBytes: Int): JsonObject? {
        val absolute = path?.let { repo.absolute(it) } ?: return null
        val size = fileSize(absolute)
        if (size <= 0 || size > maxBytes) return null
        val bytes = readBytes(absolute) ?: return null
        return buildJsonObject { put("extension", path.substringAfterLast('.', "").lowercase()); put("data", Base64.Default.encode(bytes)) }
    }

    /** Memilih dan membaca paket; null bila batal. @throws TeamPackException */
    suspend fun pickAndRead(): ParsedPack? {
        createDirectory(repo.absolute("tmp"))
        val path = suspendCancellableCoroutine<String?> { cont ->
            engine.pickTeamPack(repo.absolute("tmp/team-pack.json"), object : PickListener {
                override fun finished(path: String?) {
                    if (cont.isActive) cont.resume(path)
                }
            })
        } ?: return null
        val text = readBytes(path)?.decodeToString() ?: throw TeamPackException("File paket tidak bisa dibuka.")
        removeItem(path)
        return TeamPack.parse(text)
    }

    suspend fun preview(pack: ParsedPack): PackPreview = TeamPack.preview(
        pack,
        db.styles().captionStyles().map { it.name },
        db.styles().frameStyles().map { it.name },
        db.corrections().all().map { it.wrongText },
    )

    /** Menerapkan paket (`TeamPack::apply`). */
    suspend fun apply(pack: ParsedPack): Applied {
        val now = nowMillis()
        val styles = db.styles()
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
        for (data in pack.corrections) {
            val old = db.corrections().findByWrong(data.text("wrong_text"))
            val entry = CorrectionEntryEntity(
                id = old?.id ?: 0, wrongText = data.text("wrong_text"), correctText = data.text("correct_text"),
                isActive = data.flag("is_active") ?: old?.isActive ?: true, createdAt = old?.createdAt ?: now, updatedAt = now,
            )
            if (old != null) db.corrections().update(entry) else db.corrections().insert(entry)
        }

        var defaultFrame: Long? = null
        createDirectory(repo.absolute("branding"))
        pack.frameStyles.forEachIndexed { index, data ->
            val name = data.text("name")
            val old = styles.frameByName(name)
            var style = frame(old ?: FrameStyleEntity(name = name, mode = "crop"), data).copy(isDefault = old?.isDefault ?: false, updatedAt = now)
            for ((key, file) in pack.frameImages[index]) {
                val (prefix, png, maxSide) = when (key) {
                    "logo" -> Triple("frame", true, 1024)
                    "background_image" -> Triple("background", false, 2160)
                    else -> Triple("template", true, 2160)
                }
                val current = when (key) { "logo" -> style.logoPath; "background_image" -> style.backgroundPath; else -> style.templatePath }
                current?.let { removeItem(repo.absolute(it)) }
                val path = file?.let { image -> saveImage(image.extension, image.bytes(), prefix, png, maxSide) }
                style = when (key) { "logo" -> style.copy(logoPath = path); "background_image" -> style.copy(backgroundPath = path); else -> style.copy(templatePath = path) }
            }
            val id = if (old != null) style.id.also { styles.update(style) } else styles.insert(style)
            if (data.flag("is_default") == true) defaultFrame = id
        }
        defaultFrame?.let { styles.makeFrameDefault(it) }

        var failed = 0
        for (b in pack.bumpers) {
            // Dicari sebelum mengimpor, karena video baru memakai nama yang sama.
            val existing = db.bumpers().byName(b.kind, b.name)
            val temp = repo.absolute("tmp/pack-bumper-${nowMillis()}.${b.video.extension}")
            writeBytes(temp, b.video.bytes())
            val imported = runCatching { bumpers.import(b.kind, temp, b.name) }.getOrNull()
            removeItem(temp)
            if (imported == null) {
                failed++
                continue
            }
            if (existing != null) {
                // Intro/outro dengan jenis dan nama yang sama diganti videonya (id tetap, jadi klip yang memakainya ikut).
                removeItem(repo.absolute(existing.path))
                existing.thumbnailPath?.let { removeItem(repo.absolute(it)) }
                db.bumpers().update(existing.copy(path = imported.path, thumbnailPath = imported.thumbnailPath, duration = b.duration, updatedAt = now))
                db.bumpers().delete(imported)
            } else {
                db.bumpers().update(imported.copy(duration = b.duration))
            }
        }
        return Applied(failed)
    }

    /** Gambar dari paket disimpan ulang sebagai PNG/JPEG (WebP dari PC tidak terbaca FFmpeg di iPhone). */
    private fun saveImage(extension: String, bytes: ByteArray, prefix: String, png: Boolean, maxSide: Int): String? {
        val temp = repo.absolute("tmp/pack-image-${nowMillis()}.${extension.ifEmpty { "png" }}")
        writeBytes(temp, bytes)
        val relative = "branding/$prefix-${random()}.${if (png) "png" else "jpg"}"
        val ok = engine.convertImage(temp, repo.absolute(relative), png, maxSide)
        removeItem(temp)
        return relative.takeIf { ok }
    }

    private fun random(length: Int = 6): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
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
        highlightRadius = if ("highlight_radius" in d) d.number("highlight_radius") else base.highlightRadius,
        highlightHeight = if ("highlight_height" in d) d.number("highlight_height") else base.highlightHeight,
        highlightWidth = if ("highlight_width" in d) d.number("highlight_width") else base.highlightWidth,
        highlightY = if ("highlight_y" in d) d.number("highlight_y") else base.highlightY,
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
        cropX = if ("crop_x" in d) d.number("crop_x") else base.cropX,
        cropY = if ("crop_y" in d) d.number("crop_y") else base.cropY,
        templateFill = if ("template_fill" in d) d.str("template_fill") else base.templateFill,
    )

    /** Nilai teks kolom, atau null bila tidak ada / null. */
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

/** Paket Standar Tim (padanan bagian di Data & penyimpanan Android): ekspor dan impor, dengan pratinjau sebelum diterapkan. */
@Composable
fun TeamPackScreen(packs: IosTeamPacks, onBack: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var busy by remember { mutableStateOf<String?>(null) }
    var pack by remember { mutableStateOf<Pair<ParsedPack, PackPreview>?>(null) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Paket Standar Tim", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            busy?.let { text ->
                ClipMiCard {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                    ProgressBar(null)
                }
            }
            ClipMiCard {
                Text("Prompt, gaya, intro/outro, dan kamus · sama dengan ClipMI di PC dan Android", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Ekspor paket", {
                        scope.launch {
                            busy = "Menyiapkan paket…"
                            runCatching { packs.export() }.onFailure { notifier.show("Paket gagal dibuat: ${it.message}") }
                            busy = null
                        }
                    }, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Download, enabled = busy == null)
                    ActionChip("Impor paket", {
                        scope.launch {
                            runCatching { packs.pickAndRead()?.let { it to packs.preview(it) } }
                                .onSuccess { if (it != null) pack = it }
                                .onFailure { notifier.show(it.message ?: "Paket tidak bisa dibaca.") }
                        }
                    }, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Upload, enabled = busy == null)
                }
                Text(
                    "Ekspor membuka lembar bagikan: simpan ke Files, kirim lewat AirDrop, atau WhatsApp ke anggota tim. API key tidak ikut.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
        }
    }

    pack?.let { (parsed, preview) ->
        ClipMiSheet({ pack = null }, title = "Terapkan Paket Standar Tim?", subtitle = "Isi paket yang akan diterapkan") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gaya subtitle: ${preview.stylesNew} baru, ${preview.stylesUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Gaya bingkai: ${preview.framesNew} baru, ${preview.framesUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Kamus koreksi: ${preview.correctionsNew} baru, ${preview.correctionsUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                if (preview.bumpers > 0) Text("Galeri intro & outro: ${preview.bumpers} video", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Template prompt: " + if (preview.prompt) "diganti dengan versi paket" else "tidak berubah", style = MaterialTheme.typography.bodyMedium, color = c.text)
                if (preview.skipped > 0) Text("${preview.skipped} entri tidak valid akan dilewati", style = MaterialTheme.typography.bodyMedium, color = c.warn)
            }
            PrimaryButton("Terapkan", modifier = Modifier.fillMaxWidth(), onClick = {
                pack = null
                scope.launch {
                    busy = "Menerapkan paket…"
                    runCatching { packs.apply(parsed) }
                        .onSuccess { notifier.show("Paket diterapkan." + if (it.bumpersFailed > 0) " ${it.bumpersFailed} intro/outro tidak bisa dipakai di iPhone (pakai MP4 atau MOV)." else "") }
                        .onFailure { notifier.show("Paket gagal diterapkan: ${it.message}") }
                    busy = null
                }
            })
            GhostButton("Batal", { pack = null })
        }
    }
}
