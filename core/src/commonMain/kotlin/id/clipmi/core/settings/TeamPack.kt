package id.clipmi.core.settings

import id.clipmi.core.php.Php
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class TeamPackException(message: String) : Exception(message)

/** Gambar atau video di paket: ekstensi dan isi base64 (sudah diperiksa). */
data class PackFile(val extension: String, val data: String) {
    fun bytes(): ByteArray = TeamPack.base64(data)
}

/** Intro/outro dari paket. */
data class PackBumper(val kind: String, val name: String, val duration: Double, val video: PackFile)

/**
 * Isi paket yang sudah divalidasi: entri gaya hanya berisi kolom yang dikenal dan yang memang ada di file (kolom yang
 * tidak ada tidak mengubah nilai lokal). Gambar gaya bingkai: kunci ada = diganti (null = dihapus), tidak ada = tetap.
 */
data class ParsedPack(
    val promptTemplate: String?,
    val promptMaxTokens: Long?,
    val captionStyles: List<JsonObject>,
    val frameStyles: List<JsonObject>,
    val frameImages: List<Map<String, PackFile?>>,
    val corrections: List<JsonObject>,
    val bumpers: List<PackBumper>,
    val skipped: Int,
)

data class PackPreview(
    val stylesNew: Int, val stylesUpdated: Int,
    val framesNew: Int, val framesUpdated: Int,
    val correctionsNew: Int, val correctionsUpdated: Int,
    val bumpers: Int, val prompt: Boolean, val skipped: Int,
)

/**
 * Paket Standar Tim, padanan `App\Settings\TeamPack` desktop: template prompt, gaya subtitle, gaya bingkai (dengan
 * gambar), intro/outro, dan kamus koreksi dalam satu file JSON yang sama dengan desktop, sehingga paket dari PC bisa
 * dipakai di HP dan sebaliknya. API key dan folder output tidak ikut.
 */
object TeamPack {
    const val FORMAT = "clipmi-team-pack"
    const val VERSION = 1

    val STYLE_FIELDS = listOf(
        "name", "font_family", "font_size", "uppercase", "primary_color", "outline_color", "highlight_active_word",
        "highlight_style", "highlight_color", "upcoming_color", "keyword_highlight", "keyword_color", "position", "words_per_line", "animation", "effect",
        "line_box", "line_box_color", "line_box_opacity", "is_default",
        // Khusus ClipMI HP (desktop mengabaikannya): kotak sorot kata yang diatur.
        "highlight_radius", "highlight_height", "highlight_width", "highlight_y",
    )

    val FRAME_FIELDS = listOf(
        "name", "aspect", "mode", "background", "blur_strength", "background_color", "background_color_2", "video_height",
        "video_position", "zoom", "corner_radius", "border_width", "border_color", "logo_x", "logo_y", "logo_size", "logo_opacity",
        "watermark_text", "watermark_font", "watermark_size", "watermark_color", "watermark_opacity", "watermark_x", "watermark_y",
        "title_bar", "title_bar_color", "title_text_color", "subtitle_y", "template_area", "is_default",
        // Khusus ClipMI HP (desktop mengabaikannya): crop bawaan gaya dan video penuh di balik template.
        "crop_x", "crop_y", "template_fill",
    )

    const val LOGO_MAX_BYTES = 2 * 1024 * 1024
    const val BACKGROUND_MAX_BYTES = 5 * 1024 * 1024
    const val VIDEO_MAX_BYTES = 20 * 1024 * 1024
    val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
    val VIDEO_EXTENSIONS = listOf("mp4", "mov", "webm")
    val BUMPER_KINDS = listOf("intro", "outro")
    const val BUMPER_MAX_SECONDS = 30

    /** Kunci gambar gaya bingkai di paket → kolom path lokal. */
    val FRAME_IMAGES = linkedMapOf("logo" to LOGO_MAX_BYTES, "background_image" to BACKGROUND_MAX_BYTES, "template_image" to BACKGROUND_MAX_BYTES)

    private val COLOR = Rule.Regex(Regex("^#[0-9A-Fa-f]{6}$"))

    private val STYLE_RULES: Map<String, List<Rule>> = linkedMapOf(
        "name" to listOf(Rule.Required, Rule.Str, Rule.Max(60)),
        "font_family" to listOf(Rule.Required, Rule.In(Fonts.AVAILABLE.keys)),
        "font_size" to listOf(Rule.Required, Rule.Integer, Rule.Between(40.0, 140.0)),
        "primary_color" to listOf(Rule.Required, COLOR),
        "outline_color" to listOf(Rule.Required, COLOR),
        "uppercase" to listOf(Rule.Sometimes, Rule.Bool),
        "highlight_active_word" to listOf(Rule.Bool),
        "highlight_style" to listOf(Rule.Sometimes, Rule.In(CaptionStyle.HIGHLIGHT_STYLES.keys)),
        "highlight_color" to listOf(Rule.Required, COLOR),
        "upcoming_color" to listOf(Rule.Nullable, COLOR),
        "keyword_highlight" to listOf(Rule.Sometimes, Rule.Bool),
        "keyword_color" to listOf(Rule.Sometimes, COLOR),
        "position" to listOf(Rule.Required, Rule.In(CaptionStyle.POSITIONS.keys)),
        "words_per_line" to listOf(Rule.Required, Rule.Integer, Rule.Between(1.0, 8.0)),
        "animation" to listOf(Rule.Sometimes, Rule.In(CaptionStyle.ANIMATIONS.keys)),
        "effect" to listOf(Rule.Sometimes, Rule.In(CaptionStyle.EFFECTS.keys)),
        "line_box" to listOf(Rule.Sometimes, Rule.Bool),
        "line_box_color" to listOf(Rule.Sometimes, COLOR),
        "line_box_opacity" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(10.0, 100.0)),
        "is_default" to listOf(Rule.Bool),
        "highlight_radius" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(0.0, 80.0)),
        "highlight_height" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(10.0, 150.0)),
        "highlight_width" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(50.0, 200.0)),
        "highlight_y" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(0.0, 100.0)),
    )

    private val FRAME_RULES: Map<String, List<Rule>> = linkedMapOf(
        "name" to listOf(Rule.Required, Rule.Str, Rule.Max(60)),
        "aspect" to listOf(Rule.Sometimes, Rule.In(FrameStyle.ASPECTS.keys)),
        "mode" to listOf(Rule.Required, Rule.In(FrameStyle.MODES.keys)),
        "background" to listOf(Rule.Required, Rule.In(FrameStyle.BACKGROUNDS.keys)),
        "blur_strength" to listOf(Rule.Required, Rule.Integer, Rule.Between(1.0, 30.0)),
        "background_color" to listOf(Rule.Required, COLOR),
        "background_color_2" to listOf(Rule.Sometimes, COLOR),
        "video_height" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(30.0, 85.0)),
        "video_position" to listOf(Rule.Sometimes, Rule.In(FrameStyle.VIDEO_POSITIONS.keys)),
        "zoom" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(100.0, 200.0)),
        "corner_radius" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 80.0)),
        "border_width" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 24.0)),
        "border_color" to listOf(Rule.Sometimes, COLOR),
        "logo_position" to listOf(Rule.Sometimes, Rule.In(listOf("top-left", "top-right", "bottom-left", "bottom-right"))),
        "logo_x" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 100.0)),
        "logo_y" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 100.0)),
        "watermark_text" to listOf(Rule.Nullable, Rule.Str, Rule.Max(80)),
        "watermark_font" to listOf(Rule.Sometimes, Rule.In(Fonts.AVAILABLE.keys)),
        "watermark_size" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(16.0, 120.0)),
        "watermark_color" to listOf(Rule.Sometimes, COLOR),
        "watermark_opacity" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(10.0, 100.0)),
        "watermark_x" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 100.0)),
        "watermark_y" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(0.0, 100.0)),
        "subtitle_y" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(5.0, 95.0)),
        "template_area" to listOf(Rule.Nullable, Rule.ArrayKeys(setOf("x", "y", "width", "height"))),
        "logo_size" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(5.0, 40.0)),
        "logo_opacity" to listOf(Rule.Sometimes, Rule.Integer, Rule.Between(10.0, 100.0)),
        "title_bar" to listOf(Rule.Sometimes, Rule.Bool),
        "title_bar_color" to listOf(Rule.Sometimes, COLOR),
        "title_text_color" to listOf(Rule.Sometimes, COLOR),
        "is_default" to listOf(Rule.Bool),
        "crop_x" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(0.0, 100.0)),
        "crop_y" to listOf(Rule.Nullable, Rule.Integer, Rule.Between(0.0, 100.0)),
        "template_fill" to listOf(Rule.Nullable, Rule.In(listOf("area", "full"))),
    )

    private val CORRECTION_RULES: Map<String, List<Rule>> = linkedMapOf(
        "wrong_text" to listOf(Rule.Required, Rule.Str, Rule.Max(255)),
        "correct_text" to listOf(Rule.Required, Rule.Str, Rule.Max(255)),
        "is_active" to listOf(Rule.Bool),
    )

    /**
     * Membaca dan memvalidasi file paket (`TeamPack::parse`). Entri yang tidak valid dilewati dan dihitung.
     * @throws TeamPackException
     */
    fun parse(json: String): ParsedPack {
        val data = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
        if (data == null || (data["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != FORMAT) {
            throw TeamPackException("File ini bukan Paket Standar Tim ClipMI.")
        }
        val version = (data["version"] as? JsonPrimitive)?.doubleOrNull
        if (version != null && version > VERSION) throw TeamPackException("Paket ini dibuat oleh ClipMI versi yang lebih baru. Perbarui aplikasi dulu.")

        var skipped = 0
        fun valid(items: JsonElement?, rules: Map<String, List<Rule>>, extra: (JsonObject) -> Boolean = { true }): List<JsonObject> =
            listItems(items).filter { item ->
                val ok = item is JsonObject && Validator.passes(item, rules) && extra(item)
                if (!ok) skipped++
                ok
            }.map { it as JsonObject }

        val styles = valid(data["caption_styles"], STYLE_RULES)
        // template_area.* numeric|between:0,1
        val frames = valid(data["frame_styles"], FRAME_RULES) { item ->
            val area = item["template_area"] as? JsonObject
            area == null || area.values.all { Validator.numeric(it) && Validator.size(it)!!.let { v -> v in 0.0..1.0 } }
        }
        val corrections = valid(data["corrections"], CORRECTION_RULES)

        val bumpers = mutableListOf<PackBumper>()
        for (bumper in listItems(data["bumpers"])) {
            val o = bumper as? JsonObject
            val video = o?.let { parseVideo(it["video"]) }
            val kind = (o?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val name = (o?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val duration = o?.get("duration")?.let { if (Validator.numeric(it)) Validator.size(it) else null }
            if (video != null && kind in BUMPER_KINDS && name != null && Php.trim(name).isNotEmpty() && Php.mbStrlen(name) <= 60 &&
                duration != null && duration > 0 && duration <= BUMPER_MAX_SECONDS
            ) {
                bumpers += PackBumper(kind!!, Php.trim(name), duration, video)
            } else {
                skipped++
            }
        }

        return ParsedPack(
            promptTemplate = (data["prompt_template"] as? JsonPrimitive)?.takeIf { it.isString && Php.trim(it.content).isNotEmpty() }?.content,
            promptMaxTokens = (data["prompt_max_tokens"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { p -> p.longOrNull?.takeIf { !p.content.contains('.') && !p.content.contains('e', true) } },
            captionStyles = styles.map { JsonObject(only(it, STYLE_FIELDS)) },
            frameStyles = frames.map { style -> JsonObject(legacyLogoPosition(style) + only(style, FRAME_FIELDS)) },
            frameImages = frames.map { style ->
                FRAME_IMAGES.filterKeys { it in style }.mapValues { (key, max) -> parseImage(style[key], max) }
            },
            corrections = corrections,
            bumpers = bumpers,
            skipped = skipped,
        )
    }

    /** Ringkasan perubahan sebelum paket diterapkan; nama dibandingkan tanpa peduli huruf besar/kecil. */
    fun preview(pack: ParsedPack, styleNames: Collection<String>, frameNames: Collection<String>, correctionWords: Collection<String>): PackPreview {
        fun count(items: List<JsonObject>, key: String, existing: Collection<String>): Int {
            val set = existing.map(Php::mbStrtolower).toSet()
            return items.count { Php.mbStrtolower(it.text(key)) in set }
        }
        val styles = count(pack.captionStyles, "name", styleNames)
        val frames = count(pack.frameStyles, "name", frameNames)
        val corrections = count(pack.corrections, "wrong_text", correctionWords)
        return PackPreview(
            pack.captionStyles.size - styles, styles, pack.frameStyles.size - frames, frames,
            pack.corrections.size - corrections, corrections, pack.bumpers.size, pack.promptTemplate != null, pack.skipped,
        )
    }

    /** Teks sebuah kolom (`(string)` PHP untuk angka). */
    fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    /** Nilai boolean kolom gaya (`boolean` Laravel: true/false/1/0/"1"/"0"). */
    fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { p ->
        p.bool ?: p.content.let { if (it == "1") true else if (it == "0") false else null }
    }

    /** Nilai bilangan bulat kolom gaya. */
    fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.let { p -> p.bool?.let { if (it) 1 else 0 } ?: p.content.trim().toDoubleOrNull()?.toInt() }

    private fun listItems(items: JsonElement?): List<JsonElement> = when (items) {
        is JsonArray -> items
        is JsonObject -> items.values.toList()
        else -> emptyList()
    }

    private fun only(item: JsonObject, fields: List<String>): Map<String, JsonElement> =
        LinkedHashMap<String, JsonElement>().apply { fields.forEach { f -> item[f]?.let { put(f, it) } } }

    /** Paket lama menyimpan logo di salah satu dari empat pojok; kini posisinya x/y dalam persen. */
    private fun legacyLogoPosition(style: JsonObject): Map<String, JsonElement> {
        val position = (style["logo_position"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (position == null || (style["logo_x"] != null && style["logo_x"] !is JsonNull)) return emptyMap()
        return linkedMapOf(
            "logo_x" to JsonPrimitive(if (position.endsWith("left")) 0 else 100),
            "logo_y" to JsonPrimitive(if (position.startsWith("top")) 0 else 100),
        )
    }

    private fun decode(data: String): ByteArray? = runCatching { base64(data) }.getOrNull()

    /** Dekoder base64 dasar seperti `java.util.Base64.getDecoder()`: tanpa spasi/baris baru, "=" boleh tidak ada. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun base64(data: String): ByteArray = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(data)

    /** Teks ISO-8859-1 dari [length] bait mulai [offset] (tanda tangan berkas). */
    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        CharArray(length) { (bytes[offset + it].toInt() and 0xFF).toChar() }.concatToString()

    /** Gambar yang rusak, terlalu besar, atau bukan gambar diabaikan (gaya bingkai tetap diimpor tanpa gambar itu). */
    fun parseImage(image: JsonElement?, maxBytes: Int): PackFile? {
        val o = image as? JsonObject ?: return null
        val extension = (o["extension"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in IMAGE_EXTENSIONS } ?: return null
        val data = (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val bytes = decode(data) ?: return null
        if (bytes.size > maxBytes || !isImage(bytes)) return null
        return PackFile(extension, data)
    }

    /** Video intro/outro yang rusak, terlalu besar, atau berekstensi lain diabaikan. */
    fun parseVideo(video: JsonElement?): PackFile? {
        val o = video as? JsonObject ?: return null
        val extension = (o["extension"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in VIDEO_EXTENSIONS } ?: return null
        val data = (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val bytes = decode(data) ?: return null
        return if (bytes.isNotEmpty() && bytes.size <= VIDEO_MAX_BYTES) PackFile(extension, data) else null
    }

    /** Pengganti `getimagesizefromstring()`: tanda tangan PNG, JPEG, GIF, atau WebP. */
    fun isImage(bytes: ByteArray): Boolean {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        val png = bytes.size >= 24 && at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code
        val jpeg = bytes.size >= 4 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val gif = bytes.size >= 10 && ascii(bytes, 0, 3) == "GIF"
        val webp = bytes.size >= 30 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP"
        return png || jpeg || gif || webp
    }
}

/** Aturan validasi Laravel yang dipakai paket. */
internal sealed class Rule {
    object Required : Rule()
    object Sometimes : Rule()
    object Nullable : Rule()
    object Str : Rule()
    object Bool : Rule()
    object Integer : Rule()
    data class Max(val length: Int) : Rule()
    data class Between(val min: Double, val max: Double) : Rule()
    data class In(val values: Collection<String>) : Rule()
    data class Regex(val pattern: kotlin.text.Regex) : Rule()
    data class ArrayKeys(val keys: Set<String>) : Rule()
}

/** Tiruan `Validator::make($item, $rules)->fails()` Laravel untuk aturan di atas. */
internal object Validator {
    fun passes(item: JsonObject, rules: Map<String, List<Rule>>): Boolean = rules.all { (field, list) -> field(item, field, list) }

    private fun field(item: JsonObject, name: String, rules: List<Rule>): Boolean {
        val present = item.containsKey(name)
        val value = item[name]
        if (Rule.Sometimes in rules && !present) return true
        val nullable = Rule.Nullable in rules
        for (rule in rules) {
            when (rule) {
                Rule.Sometimes, Rule.Nullable -> continue
                Rule.Required -> if (!required(value)) return false
                else -> {
                    // Aturan biasa dilewati bila kolomnya tidak ada; nilai null dilewati bila "nullable".
                    if (!present) continue
                    if (value is JsonNull && nullable) continue
                    if (!check(rule, value!!, rules)) return false
                }
            }
        }
        return true
    }

    private fun required(value: JsonElement?): Boolean = when (value) {
        null, is JsonNull -> false
        is JsonPrimitive -> !(value.isString && Php.trim(value.content).isEmpty())
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    private fun check(rule: Rule, value: JsonElement, rules: List<Rule>): Boolean = when (rule) {
        Rule.Str -> value is JsonPrimitive && value.isString
        Rule.Bool -> value is JsonPrimitive && (value.bool != null || (!value.isString && value.content in setOf("0", "1")) || (value.isString && value.content in setOf("0", "1")))
        Rule.Integer -> integer(value)
        is Rule.Max -> size(value, rules)?.let { it <= rule.length } ?: false
        is Rule.Between -> size(value, rules)?.let { it >= rule.min && it <= rule.max } ?: false
        is Rule.In -> value !is JsonArray && value !is JsonObject && phpString(value) in rule.values
        is Rule.Regex -> value is JsonPrimitive && (value.isString || numeric(value)) && rule.pattern.containsMatchIn(phpString(value))
        is Rule.ArrayKeys -> value is JsonObject && value.keys.all { it in rule.keys } || value is JsonArray && value.isEmpty()
        else -> true
    }

    /** `filter_var($v, FILTER_VALIDATE_INT)`: bilangan bulat, string bilangan bulat (spasi di tepi boleh), atau float utuh. */
    private fun integer(value: JsonElement): Boolean {
        val p = value as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        p.bool?.let { return it }
        val text = if (p.isString) p.content.trim { it in " \t\n\r\u000B\u000C" } else p.content
        if (Regex("^[+-]?(0|[1-9][0-9]*)$").matches(text)) return text.trimStart('+', '-').length < 19
        if (!p.isString) p.doubleOrNull?.let { d -> return d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.2e18 && !d.toString().contains('E') }
        return false
    }

    fun numeric(value: JsonElement): Boolean = value is JsonPrimitive && value !is JsonNull && value.bool == null &&
        (if (value.isString) Regex("^[ \\t\\n\\r\\u000B\\u000C]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\u000B\\u000C]*$").matches(value.content) else value.doubleOrNull != null)

    fun size(value: JsonElement): Double? = (value as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()

    /** `getSize()`: angka bila ada aturan numerik dan nilainya numerik; panjang string; jumlah elemen larik. */
    private fun size(value: JsonElement, rules: List<Rule>): Double? = when {
        Rule.Integer in rules && numeric(value) -> size(value)
        value is JsonArray -> value.size.toDouble()
        value is JsonObject -> value.size.toDouble()
        value is JsonPrimitive && value.isString -> Php.mbStrlen(value.content).toDouble()
        value is JsonPrimitive && value.bool == null -> Php.mbStrlen(value.content).toDouble()
        value is JsonPrimitive -> (if (value.bool == true) 1 else 0).toDouble()
        else -> null
    }

    private fun phpString(value: JsonElement): String = when {
        value is JsonPrimitive && value.isString -> value.content
        value is JsonNull -> ""
        value is JsonPrimitive && value.bool != null -> if (value.bool == true) "1" else ""
        value is JsonPrimitive -> value.longOrNull?.toString() ?: value.content
        else -> "Array"
    }
}

/** Boolean JSON sungguhan (bukan string "true"), seperti tipe bool PHP hasil json_decode. */
internal val JsonPrimitive.bool: Boolean? get() = if (isString) null else booleanOrNull
