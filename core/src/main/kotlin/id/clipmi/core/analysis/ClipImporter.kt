package id.clipmi.core.analysis

import id.clipmi.core.php.Php
import id.clipmi.core.php.Str
import id.clipmi.core.transcript.Timecode
import id.clipmi.core.transcript.Word
import id.clipmi.core.transcript.WordTimer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Pesannya ditampilkan langsung ke pengguna, jadi ditulis dalam bahasa Indonesia. */
class AnalysisResultException(message: String) : Exception(message)

/** Segmen transkrip yang dibutuhkan untuk memetakan ID segmen ke waktu kata. */
data class ImportSegment(val sequence: Int, val startTime: Double, val endTime: Double, val words: List<Word>?) {
    val code: String get() = "S%04d".format(java.util.Locale.ROOT, sequence)
}

/** Calon klip hasil balasan AI yang sudah divalidasi (kolom sama dengan tabel clips desktop). */
data class ClipDraft(
    val title: String,
    val startTime: Double,
    val endTime: Double,
    val score: Int?,
    val hook: String?,
    val caption: String?,
    val hashtags: List<String>,
    val keywords: List<String>?,
    val reason: String?,
    val series: String?,
    val partNumber: Int?,
)

/** Hasil membaca satu balasan: calon klip yang sah dan pesan untuk klip yang ditolak. */
data class ParsedReply(val drafts: List<ClipDraft>, val errors: List<String>)

/**
 * Membaca balasan JSON dari chat AI (padanan `App\Analysis\ClipImporter` desktop). Penyimpanan (lewati rentang yang
 * sudah ada, seri, status kandidat) dilakukan lapisan aplikasi.
 */
object ClipImporter {
    const val MIN_DURATION = 10
    const val MAX_DURATION = 300

    /** Klip yang rentangnya sama (±0,01 detik) dengan klip yang sudah ada dilewati. */
    const val DUPLICATE_TOLERANCE = 0.01

    /** @throws AnalysisResultException bila balasan tidak berisi JSON klip yang bisa dibaca. */
    fun parse(reply: String, segments: List<ImportSegment>): ParsedReply {
        val items = decode(reply)
        val bySequence = segments.associateBy { it.sequence }
        val drafts = mutableListOf<ClipDraft>()
        val errors = mutableListOf<String>()
        items.forEachIndexed { i, item ->
            val title = (item as? JsonObject)?.get("title")?.stringValue()
            val label = "Klip ${i + 1}" + (if (title != null) " (\"${Str.limit(Php.trim(title), 40)}\")" else "")
            try {
                drafts += draft(item, bySequence)
            } catch (e: AnalysisResultException) {
                errors += "$label: ${e.message}"
            }
        }
        return ParsedReply(drafts, errors)
    }

    /**
     * Daftar klip dari balasan, toleran terhadap teks pembuka/penutup, blok kode markdown, dan koma berlebih.
     * @throws AnalysisResultException
     */
    fun decode(reply: String): List<JsonElement> {
        var text = Php.trim(reply)
        FENCE.find(text)?.let { text = it.groupValues[1] }

        val objectStart = text.indexOf('{')
        val arrayStart = text.indexOf('[')
        val useArray = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)
        val start = if (useArray) arrayStart else objectStart
        val end = text.lastIndexOf(if (useArray) ']' else '}')
        if (start < 0 || end < 0 || end < start) {
            throw AnalysisResultException("Balasan tidak berisi JSON. Pastikan seluruh balasan AI ikut tersalin.")
        }

        val json = TRAILING_COMMA.replace(text.substring(start, end + 1), "$1")
        val data = runCatching { Json.parseToJsonElement(json) }.getOrNull()
        if (data !is JsonObject && data !is JsonArray) {
            val reason = if (data == null) "Syntax error" else "No error"
            throw AnalysisResultException("JSON di balasan tidak valid ($reason). Coba minta AI mengulang jawabannya dalam format JSON.")
        }

        // json_decode(..., true): objek menjadi array asosiatif; {} kosong dianggap list kosong oleh array_is_list.
        val clips: List<JsonElement>? = when (data) {
            is JsonArray -> data
            is JsonObject -> if (data.isEmpty()) emptyList() else when (val c = data["clips"]) {
                is JsonArray -> c
                is JsonObject -> c.values.toList()
                else -> null
            }
            else -> null
        }
        if (clips.isNullOrEmpty()) throw AnalysisResultException("JSON tidak berisi daftar \"clips\".")
        return clips
    }

    private fun draft(item: JsonElement, segments: Map<Int, ImportSegment>): ClipDraft {
        if (item !is JsonObject && item !is JsonArray) throw AnalysisResultException("format tidak dikenali.")
        val obj = item as? JsonObject ?: JsonObject(emptyMap())

        val title = obj["title"]?.stringValue()?.let { Str.limit(Php.trim(it), 250, "") }.orEmpty()
        if (title.isEmpty()) throw AnalysisResultException("judul kosong.")

        val start = segment(obj["start_segment"], segments, "start_segment")
        val end = segment(obj["end_segment"], segments, "end_segment")
        if (start.sequence > end.sequence) {
            throw AnalysisResultException("segmen awal (${start.code}) berada setelah segmen akhir (${end.code}).")
        }

        val (startTime, endTime) = rangeTimes(start, end)
        val duration = endTime - startTime
        if (duration < MIN_DURATION || duration > MAX_DURATION) {
            throw AnalysisResultException(
                "durasi ${Timecode.format(duration)} di luar rentang $MIN_DURATION detik sampai ${MAX_DURATION / 60} menit.",
            )
        }

        val series = obj["series"]?.stringValue()?.takeIf { Php.trim(it).isNotEmpty() }?.let { Str.limit(Php.trim(it), 250, "") }
        val part = obj["part"]?.numericValue()?.let { phpInt(it) }?.takeIf { it >= 1 }

        return ClipDraft(
            title = title,
            startTime = Php.round(startTime, 3),
            endTime = Php.round(endTime, 3),
            score = obj["score"]?.numericValue()?.let { Php.round(it, 0).toLong().coerceIn(1, 100).toInt() },
            hook = text(obj["hook"]),
            caption = text(obj["caption"]),
            hashtags = hashtags(obj["hashtags"]),
            keywords = Keywords.clean(obj["keywords"]).ifEmpty { null },
            reason = text(obj["reason"]),
            series = series,
            partNumber = if (series != null) part else null,
        )
    }

    /** Waktu kata pertama segmen awal dan kata terakhir segmen akhir, supaya potongan jatuh di batas kata. */
    fun rangeTimes(start: ImportSegment, end: ImportSegment): Pair<Double, Double> = Pair(
        Php.round(start.words?.firstOrNull()?.start ?: start.startTime, 3),
        Php.round(end.words?.lastOrNull()?.end ?: end.endTime, 3),
    )

    private fun segment(id: JsonElement?, segments: Map<Int, ImportSegment>, field: String): ImportSegment {
        val primitive = id as? JsonPrimitive
        val raw = when {
            primitive == null || primitive is JsonNull -> null
            primitive.isString -> primitive.content
            primitive.longOrNull != null && !primitive.content.contains('.') && !primitive.content.contains('e', true) -> primitive.content
            else -> null
        }
        val m = raw?.let { SEGMENT_ID.find(Php.trim(it)) }
            ?: throw AnalysisResultException("$field tidak valid (harus berupa ID seperti \"S0012\").")
        val sequence = m.groupValues[1].trimStart('0').ifEmpty { "0" }.toBigInteger()
        return sequence.takeIf { it <= Int.MAX_VALUE.toBigInteger() }?.let { segments[it.toInt()] }
            ?: throw AnalysisResultException("$field $raw tidak ada di transkrip.")
    }

    private fun text(value: JsonElement?): String? = value?.stringValue()?.let(Php::trim)?.takeIf { it.isNotEmpty() }

    /** Menerima array atau string ("#a #b", "a, b"); hasilnya selalu berawalan "#" tanpa spasi, unik. */
    fun hashtags(value: JsonElement?): List<String> {
        val items: List<String?> = when (value) {
            is JsonArray -> value.map { it.stringValue() }
            is JsonPrimitive -> value.stringValue()?.split(HASHTAG_SPLIT) ?: emptyList()
            else -> emptyList()
        }
        val seen = HashSet<String>()
        return items.filterNotNull()
            .map { "#" + HASHTAG_STRIP.replace(it, "") }
            .filter { it != "#" }
            .filter { seen.add(Php.mbStrtolower(it)) }
    }

    /** String JSON saja (is_string PHP); angka, boolean, dan null bukan string. */
    internal fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** is_numeric PHP: angka JSON, atau string angka (boleh diawali/diakhiri spasi). */
    internal fun JsonElement.numericValue(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) return if (p.booleanOrNull != null) null else p.doubleOrNull
        return if (NUMERIC.matches(p.content)) p.content.trim().toDouble() else null
    }

    /** (int) PHP: dipotong ke arah nol. */
    private fun phpInt(value: Double): Int = value.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private val FENCE = Regex("""```(?:json)?\s*(.*?)```""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TRAILING_COMMA = Regex(""",\s*([}\]])""")
    private val SEGMENT_ID = Regex("""^S?0*(\d+)$""", RegexOption.IGNORE_CASE)
    private val HASHTAG_SPLIT = Regex("[${WordTimer.SPACE_CLASS},]+")
    private val HASHTAG_STRIP = Regex("[${WordTimer.SPACE_CLASS}#]+")
    private val NUMERIC = Regex("""^[ \t\n\r\u000B\f]*[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?[ \t\n\r\u000B\f]*$""")
}

/** Kata kunci klip (padanan `App\Render\Keywords`): diwarnai di subtitle bila gaya mengaktifkannya. */
object Keywords {
    const val MAX_WORDS = 3
    const val MAX_KEYWORDS = 8
    private const val TRIM_CHARS = " \t\n\r\u0000\u000B\"'.,;:!?"

    /** Menerima array atau string ("sabar, ajal"); hasilnya rapi, unik, dan dibatasi jumlahnya. */
    fun clean(value: JsonElement?): List<String> {
        val items: List<String?> = when (value) {
            is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> if (value.isString) value.content.split(',') else emptyList()
            else -> emptyList()
        }
        return clean(items.filterNotNull())
    }

    fun clean(items: List<String>): List<String> {
        val seen = HashSet<String>()
        return items
            .map { Str.squish(it.trim { c -> c in TRIM_CHARS }) }
            .filter { it.isNotEmpty() && words(it).isNotEmpty() && words(it).size <= MAX_WORDS && Php.mbStrlen(it) <= 60 }
            .filter { seen.add(words(it).joinToString(" ")) }
            .take(MAX_KEYWORDS)
    }

    /** Kata dalam bentuk pembanding: huruf kecil tanpa tanda baca. */
    fun normalize(word: String): String = Php.mbStrtolower(NON_ALNUM.replace(word, ""))

    /** array_filter PHP membuang "" dan juga "0". */
    fun words(text: String): List<String> = WordTimer.split(text).map(::normalize).filter { it.isNotEmpty() && it != "0" }

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
}

/** Judul dan teks unggahan klip (padanan `Clip::displayTitle` dan `Clip::shareText` desktop). */
object ClipText {
    /** Misalnya "Kisah Nabi Yusuf (Bagian 2)". */
    fun displayTitle(title: String, partNumber: Int?): String = if (partNumber != null) "$title (Bagian $partNumber)" else title

    /** Judul, caption, lalu hashtag, dipisah baris kosong; bagian kosong dilewati. */
    fun shareText(title: String, partNumber: Int?, caption: String?, hashtags: List<String>?): String =
        listOf(Php.trim(displayTitle(title, partNumber)), Php.trim(caption.orEmpty()), hashtags.orEmpty().joinToString(" "))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}
