package id.clipmi.core.schedule

import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.php.Php
import id.clipmi.core.render.HookLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlin.math.floor

/**
 * Satu postingan terbit untuk Analisis Performa: akun tujuan jadwal beserta isi klipnya. Bagian yang bergantung pada
 * render (durasi akhir, pembuka, hook yang tampil) dihitung pemanggil lewat `ClipSpec`.
 */
data class DigestPost(
    val rowId: Long,
    val scheduleId: Long,
    val accountId: Long,
    val account: String,
    val platform: String,
    val publishedAt: LocalDateTime,
    /** Catatan statistik urut waktu (yang tayangannya diketahui saja dipakai). */
    val stats: List<StatPoint>,
    val title: String,
    /** Hook yang tampil di video, atau null. */
    val hook: String?,
    /** Kunci model hook yang tersimpan di hook_layout klip (null = bawaan). */
    val hookModel: String?,
    val finalDuration: Double,
    /** "intro", "opener", atau null. */
    val lead: String?,
    val series: Boolean,
    val keywords: List<String>,
    val project: String,
    val caption: String,
    val hashtags: Int,
)

/**
 * Angka untuk Analisis Performa AI, padanan `App\Stats\PerformanceDigest`: tiap postingan terukur dibandingkan dengan
 * median akunnya sendiri, lalu dikelompokkan per faktor. Hasilnya JSON dengan kunci yang sama dengan desktop (disimpan
 * di laporan, dibaca prompt dan layar). AI hanya menafsirkan ringkasan ini.
 */
object PerformanceDigest {
    const val MIN_POSTS = 3
    const val ENOUGH_PER_ACCOUNT = 20
    const val MAX_POSTS = 150
    const val COMPARE_DAY = 7

    val FACTORS: Map<String, String> = linkedMapOf(
        "duration" to "Durasi klip",
        "time" to "Jam posting",
        "day" to "Hari posting",
        "platform" to "Platform",
        "hook_model" to "Model hook",
        "opener" to "Pembuka",
        "series" to "Seri",
        "caption" to "Panjang caption",
        "hashtags" to "Jumlah hashtag",
        "project" to "Project / pembicara",
    )

    /** 'Y-m-d H:i' (pengganti DateTimeFormatter yang hanya ada di JVM). */
    private fun minuteText(at: LocalDateTime): String =
        "${at.year.toString().padStart(4, '0')}-${pad2(at.month.number)}-${pad2(at.day)} ${pad2(at.hour)}:${pad2(at.minute)}"

    /**
     * @param rows postingan terbit (punya waktu terbit). Yang punya catatan tayangan dipilih seperti query desktop:
     *   [MAX_POSTS] terbaru, lalu diurutkan dari yang terlama.
     */
    fun build(rows: List<DigestPost>, now: LocalDateTime): JsonObject {
        val sorted = rows.filter { row -> row.stats.any { it.numbers.views != null } }
            .sortedByDescending { it.publishedAt }
            .take(MAX_POSTS)
            .sortedBy { it.publishedAt }
        val raw = sorted.map { post(it, now) }

        val byAccount = raw.groupByOrdered { it.accountId }
        val medians = byAccount.mapValues { (_, items) -> median(items.map { it.metric.toDouble() }) }
        val posts = raw.map { p ->
            val median = medians[p.accountId]
            p.copy(ratio = if (median != null && median != 0.0) Php.round(p.metric / median, 2) else null)
        }

        val accounts = buildJsonArray {
            for ((accountId, items0) in byAccount) {
                val items = posts.filter { it.accountId == accountId }
                val median = medians[accountId]
                add(
                    buildJsonObject {
                        put("name", items.first().account)
                        put("platform", items.first().platform)
                        put("posts", items.size)
                        put("median", median?.let { Php.round(it, 0).toLong() })
                        put("er", median(items.mapNotNull { it.er }))
                        put("enough", items0.size >= ENOUGH_PER_ACCOUNT)
                    },
                )
            }
        }

        val factors = buildJsonObject {
            for (factor in FACTORS.keys) {
                val groups = posts.groupByOrdered { it.factors.getValue(factor) }.map { (label, items) ->
                    val ratio = median(items.mapNotNull { it.ratio })?.let { Php.round(it, 2) }
                    Triple(label, items, ratio)
                }
                    // sortByDesc PHP (stabil); null dibandingkan sebagai false, setara 0 untuk rasio yang tidak negatif.
                    .sortedByDescending { it.third ?: 0.0 }
                put(
                    factor,
                    buildJsonArray {
                        for ((label, items, ratio) in groups) {
                            add(
                                buildJsonObject {
                                    put("label", label)
                                    put("count", items.size)
                                    put("ratio", ratio)
                                    put("er", median(items.mapNotNull { it.er }))
                                },
                            )
                        }
                    },
                )
            }
        }

        return buildJsonObject {
            put("generated_at", minuteText(now))
            put("posts", JsonArray(posts.map { it.json() }))
            put("accounts", accounts)
            put("factors", factors)
        }
    }

    /** Akun yang datanya masih di bawah batas cukup. */
    fun thinAccounts(digest: JsonObject): List<JsonObject> =
        digest["accounts"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it["enough"]?.jsonPrimitive?.booleanOrNull != true }

    fun postCount(digest: JsonObject): Int = digest["posts"]?.jsonArray?.size ?: 0

    /** Postingan di data laporan menurut id-nya ("P12"), untuk menampilkan bukti tiap poin. */
    fun postsById(digest: JsonObject): Map<String, JsonObject> =
        digest["posts"]?.jsonArray.orEmpty().map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }

    /** Label model hook (`HookLayout::MODELS[...]['label']`), atau "Tanpa hook". */
    fun hookModelLabel(shownHook: String?, model: String?): String =
        if (shownHook == null) "Tanpa hook" else HookLayout.MODELS[model ?: "dark"]?.label ?: "Kotak gelap"

    private data class Post(
        val id: String,
        val rowId: Long,
        val scheduleId: Long,
        val accountId: Long,
        val account: String,
        val platform: String,
        val publishedAt: String,
        val ageDays: Long,
        val views: Long?,
        val viewsDay: List<Pair<Int, Long?>>,
        val likes: Long?,
        val comments: Long?,
        val shares: Long?,
        val er: Double?,
        val metric: Long,
        val metricBasis: String,
        val title: String,
        val hook: String?,
        val duration: Int,
        val keywords: List<String>,
        val project: String,
        val captionLength: Int,
        val hashtags: Int,
        val factors: Map<String, String>,
        val ratio: Double? = null,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("id", id)
            put("row_id", rowId)
            put("schedule_id", scheduleId)
            put("account_id", accountId)
            put("account", account)
            put("platform", platform)
            put("published_at", publishedAt)
            put("age_days", ageDays)
            put("views", views)
            put("views_day", buildJsonObject { viewsDay.forEach { (day, v) -> put(day.toString(), v) } })
            put("likes", likes)
            put("comments", comments)
            put("shares", shares)
            put("er", er)
            put("metric", metric)
            put("metric_basis", metricBasis)
            put("title", title)
            put("hook", hook)
            put("duration", duration)
            put("keywords", JsonArray(keywords.map(::JsonPrimitive)))
            put("project", project)
            put("caption_length", captionLength)
            put("hashtags", hashtags)
            put("factors", buildJsonObject { factors.forEach { (k, v) -> put(k, v) } })
            put("ratio", ratio)
        }
    }

    private fun post(row: DigestPost, now: LocalDateTime): Post {
        val stats = row.stats.filter { it.numbers.views != null }
        val latest = stats.last()
        val atCompare = PostStats.statAt(row.publishedAt, stats, COMPARE_DAY)
        // Carbon 3 diffInHours: jam pecahan, lalu floor(/24).
        val age = floor((now.wallMillis() - row.publishedAt.wallMillis()) / 3_600_000.0 / 24).toLong()
        val duration = Php.roundInt(row.finalDuration)
        val caption = row.caption
        val captionLength = Php.mbStrlen(caption)
        val er = latest.numbers.engagementRate
        val hour = row.publishedAt.hour
        return Post(
            id = "P${row.rowId}",
            rowId = row.rowId,
            scheduleId = row.scheduleId,
            accountId = row.accountId,
            account = row.account,
            platform = row.platform,
            publishedAt = minuteText(row.publishedAt),
            ageDays = age,
            views = latest.numbers.views,
            viewsDay = PostStats.AGES.map { it to PostStats.statAt(row.publishedAt, stats, it)?.numbers?.views },
            likes = latest.numbers.likes,
            comments = latest.numbers.comments,
            shares = latest.numbers.shares,
            er = er?.let { Php.round(it * 100, 1) },
            metric = atCompare?.numbers?.views ?: latest.numbers.views!!,
            metricBasis = if (atCompare != null) "hari $COMPARE_DAY" else "umur $age hari",
            title = row.title,
            hook = row.hook?.let(Php::trim),
            duration = duration,
            keywords = row.keywords.take(5),
            project = row.project,
            captionLength = captionLength,
            hashtags = row.hashtags,
            factors = linkedMapOf(
                "duration" to when {
                    duration < 30 -> "< 30 detik"
                    duration < 45 -> "30–44 detik"
                    duration < 60 -> "45–59 detik"
                    duration < 90 -> "60–89 detik"
                    else -> "90 detik atau lebih"
                },
                "time" to when (hour) {
                    in 4..9 -> "Pagi (04–10)"
                    in 10..14 -> "Siang (10–15)"
                    in 15..17 -> "Sore (15–18)"
                    in 18..21 -> "Malam (18–22)"
                    else -> "Larut (22–04)"
                },
                "day" to Platforms.DAYS.getValue(row.publishedAt.dayOfWeek.isoDayNumber),
                "platform" to row.platform,
                "hook_model" to hookModelLabel(row.hook, row.hookModel),
                "opener" to when (row.lead) {
                    "intro" -> "Intro"
                    "opener" -> "Kartu pembuka hook"
                    else -> "Langsung isi"
                },
                "series" to if (row.series) "Bagian seri" else "Klip tunggal",
                "caption" to when {
                    captionLength < 100 -> "< 100 karakter"
                    captionLength < 300 -> "100–299 karakter"
                    else -> "300 karakter atau lebih"
                },
                "hashtags" to when {
                    row.hashtags == 0 -> "Tanpa hashtag"
                    row.hashtags <= 5 -> "1–5"
                    row.hashtags <= 15 -> "6–15"
                    else -> "Lebih dari 15"
                },
                "project" to row.project,
            ),
        )
    }

    /** `round((float) $values->median(), 2)`, atau null bila kosong. */
    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        return Php.round(median, 2)
    }

    private fun <T, K> List<T>.groupByOrdered(key: (T) -> K): LinkedHashMap<K, List<T>> {
        val map = LinkedHashMap<K, MutableList<T>>()
        for (item in this) map.getOrPut(key(item)) { mutableListOf() }.add(item)
        @Suppress("UNCHECKED_CAST")
        return map as LinkedHashMap<K, List<T>>
    }
}

/**
 * Prompt Analisis Performa dari ringkasan angka dan pembaca balasannya, padanan `App\Stats\PerformancePrompt`. Prompt
 * yang sama dipakai Mode API dan mode manual lewat chat AI.
 */
object PerformancePrompt {
    val SECTIONS: Map<String, String> = linkedMapOf(
        "strengths" to "Kekuatan",
        "weaknesses" to "Kelemahan",
        "recommendations" to "Rekomendasi strategi",
        "experiments" to "Eksperimen berikutnya",
    )

    const val EXPECTED_OUTPUT_TOKENS = 2500
    private const val MAX_ITEMS = 8

    fun build(digest: JsonObject): String {
        val lines = mutableListOf<String>()
        lines += "Kamu adalah analis konten media sosial untuk akun dakwah yang memposting klip vertikal pendek dari kajian dan ceramah berbahasa Indonesia."
        lines += "Tugasmu: dari data performa postingan di bawah, temukan kekuatan, kelemahan, rekomendasi strategi, dan eksperimen berikutnya."
        lines += ""
        lines += "Cara membaca data:"
        lines += "- Angka sudah diolah. \"rasio\" = tayangan pembanding dibagi median tayangan akunnya sendiri (1,0 = sama dengan biasanya; 2,0 = dua kali lipat)."
        lines += "- Tayangan pembanding = tayangan hari ke-${PerformanceDigest.COMPARE_DAY} setelah terbit; postingan yang lebih muda memakai angka terakhirnya (lihat \"dasar\")."
        lines += "- ER = (suka + komentar + share) / tayangan, dalam persen."
        lines += "- Bandingkan postingan hanya dengan akunnya sendiri, karena ukuran tiap akun dan platform berbeda."
        lines += ""

        lines += "## Akun"
        for (a in digest.getValue("accounts").jsonArray.map { it.jsonObject }) {
            lines += "- ${a.str("name")} (${a.str("platform")}): ${a.getValue("posts").jsonPrimitive.int} postingan terukur, " +
                "median tayangan ${number(a.num("median"))}, median ER ${percent(a.num("er"))}" +
                if (a["enough"]?.jsonPrimitive?.booleanOrNull == true) "" else " (data masih sedikit)"
        }
        lines += ""

        lines += "## Per faktor (median rasio dan median ER tiap kelompok)"
        val factors = digest.getValue("factors").jsonObject
        for ((factor, label) in PerformanceDigest.FACTORS) {
            val groups = factors[factor]?.jsonArray?.map { it.jsonObject }.orEmpty()
            if (groups.size < 2) continue
            lines += label + ": " + groups.joinToString("; ") { g ->
                "${g.str("label")} n=${g.getValue("count").jsonPrimitive.int} rasio ${decimal(g.num("ratio"))} ER ${percent(g.num("er"))}"
            }
        }
        lines += ""

        lines += "## Postingan"
        lines += "id | akun | terbit | dasar | tayangan pembanding | rasio | tayangan hari 1/7/30 | suka | komentar | share | ER | durasi | judul | hook | kata kunci | project | caption (karakter) | hashtag"
        for (p in digest.getValue("posts").jsonArray.map { it.jsonObject }) {
            val f = p.getValue("factors").jsonObject
            lines += listOf(
                p.str("id"),
                p.str("account") + " (" + p.str("platform") + ")",
                p.str("published_at") + " " + f.str("day"),
                p.str("metric_basis"),
                number(p.num("metric")),
                decimal(p.num("ratio")),
                p.getValue("views_day").jsonObject.values.joinToString("/") { number((it as? JsonPrimitive)?.doubleOrNull) },
                number(p.num("likes")),
                number(p.num("comments")),
                number(p.num("shares")),
                percent(p.num("er")),
                p.getValue("duration").jsonPrimitive.int.toString() + " dtk, " + f.str("opener") + ", " + f.str("hook_model"),
                clean(p.str("title")),
                clean(p["hook"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "-"),
                p.getValue("keywords").jsonArray.joinToString(", ") { it.jsonPrimitive.content }.ifEmpty { "-" },
                clean(p.str("project")),
                p.getValue("caption_length").jsonPrimitive.int.toString(),
                p.getValue("hashtags").jsonPrimitive.int.toString(),
            ).joinToString(" | ")
        }
        lines += ""

        lines += "Aturan jawaban:"
        lines += "- Setiap poin wajib menyebut bukti berupa id postingan (misalnya \"P12\") dari tabel di atas. Jangan mengarang angka atau postingan."
        lines += "- Bedakan pola yang kuat (banyak postingan) dari dugaan (sedikit postingan); sebutkan bila datanya belum cukup."
        lines += "- Rekomendasi konkret dan bisa langsung dicoba: durasi, jam posting, gaya hook, topik, caption, dan hashtag."
        lines += "- Eksperimen: perubahan kecil yang bisa diuji beberapa minggu, dengan cara mengukurnya."
        lines += "- Maksimal $MAX_ITEMS poin per bagian. Bahasa Indonesia yang ringkas."
        lines += ""
        lines += "Balas HANYA dengan JSON berikut, tanpa teks lain:"
        lines += "{\"summary\": \"ringkasan 2-4 kalimat\", \"strengths\": [{\"point\": \"...\", \"evidence\": [\"P1\"]}], \"weaknesses\": [{\"point\": \"...\", \"evidence\": [\"P2\"]}], \"recommendations\": [{\"point\": \"...\", \"evidence\": [\"P3\"]}], \"experiments\": [{\"point\": \"...\", \"how\": \"cara menguji dan mengukurnya\", \"evidence\": []}], \"data_notes\": \"catatan tentang cukup tidaknya data\"}"
        return lines.joinToString("\n")
    }

    fun estimateTokens(prompt: String): Int = PromptBuilder.estimateTokens(prompt)

    private val FENCE = Regex("```(?:json)?\\s*(.*?)```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    /**
     * Membaca balasan AI. Bukti yang tidak ada di data dibuang, supaya laporan tidak merujuk postingan karangan.
     * @throws AnalysisResultException
     */
    fun parse(reply: String, digest: JsonObject): JsonObject {
        var text = Php.trim(reply)
        FENCE.find(text)?.let { text = it.groupValues[1] }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0 || end < start) throw AnalysisResultException("Balasan tidak berisi JSON. Pastikan seluruh balasan AI ikut tersalin.")

        val candidate = TRAILING_COMMA.replace(text.substring(start, end + 1), "$1")
        PhpJsonCheck.error(candidate)?.let { reason ->
            throw AnalysisResultException("JSON di balasan tidak valid ($reason). Minta AI mengulang jawabannya dalam format JSON.")
        }
        val element = try {
            Json.parseToJsonElement(candidate)
        } catch (e: Exception) {
            throw AnalysisResultException("JSON di balasan tidak valid (Syntax error). Minta AI mengulang jawabannya dalam format JSON.")
        }
        val data = element as? JsonObject ?: JsonObject(emptyMap())

        val known = PerformanceDigest.postsById(digest).keys
        fun items(list: JsonElement?): JsonArray = buildJsonArray {
            (list as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { item -> (item["point"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Php.trim(it).isNotEmpty() } == true }
                .take(MAX_ITEMS)
                .forEach { item ->
                    add(
                        buildJsonObject {
                            put("point", Php.trim(item.getValue("point").jsonPrimitive.content))
                            (item["how"] as? JsonPrimitive)?.takeIf { it.isString && Php.trim(it.content).isNotEmpty() }?.let { put("how", Php.trim(it.content)) }
                            put(
                                "evidence",
                                JsonArray(
                                    (item["evidence"] as? JsonArray).orEmpty()
                                        .map { asciiUpper(Php.trim(phpString(it))) }
                                        .filter { it in known }
                                        .distinct()
                                        .map(::JsonPrimitive),
                                ),
                            )
                        },
                    )
                }
        }

        val summary = (data["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.let { Php.trim(it.content) } ?: ""
        val result = buildJsonObject {
            put("summary", summary)
            put("strengths", items(data["strengths"]))
            put("weaknesses", items(data["weaknesses"]))
            put("recommendations", items(data["recommendations"]))
            put("experiments", items(data["experiments"]))
            put("data_notes", (data["data_notes"] as? JsonPrimitive)?.takeIf { it.isString && Php.trim(it.content).isNotEmpty() }?.let { JsonPrimitive(Php.trim(it.content)) } ?: JsonNull)
        }
        if (summary.isEmpty() && listOf("strengths", "weaknesses", "recommendations").all { result.getValue(it).jsonArray.isEmpty() }) {
            throw AnalysisResultException("JSON tidak berisi ringkasan, kekuatan, kelemahan, atau rekomendasi.")
        }
        return result
    }

    /** `(string) $value` untuk nilai JSON (angka, boolean, null). */
    private fun phpString(value: JsonElement): String = when {
        value is JsonPrimitive && value.isString -> value.content
        value is JsonNull -> ""
        value is JsonPrimitive && value.booleanOrNull != null -> if (value.booleanOrNull == true) "1" else ""
        value is JsonPrimitive -> value.longOrNull?.toString()
            ?: value.doubleOrNull?.let { d -> if (d == floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
            ?: value.content
        else -> "Array"
    }

    /** `strtoupper()` PHP 8: hanya huruf ASCII. */
    private fun asciiUpper(s: String): String = buildString { s.forEach { append(if (it in 'a'..'z') it - 32 else it) } }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** `number_format($v, 0, ',', '.')`. */
    fun number(value: Double?): String = value?.let { PostStats.number(Php.round(it, 0).toLong()) } ?: "-"

    /** `number_format($v, 2, ',', '.')`. */
    fun decimal(value: Double?): String = value?.let { formatId(it, 2) } ?: "-"

    /** `number_format($v, 1, ',', '.') . '%'`. */
    fun percent(value: Double?): String = value?.let { formatId(it, 1) + "%" } ?: "-"

    private fun formatId(value: Double, decimals: Int): String {
        val text = Php.numberFormat(value, decimals)
        val negative = text.startsWith("-")
        val digits = text.trimStart('-')
        val whole = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
        return (if (negative) "-" else "") + grouped + if (fraction.isNotEmpty()) ",$fraction" else ""
    }

    /** Teks satu baris tanpa pemisah kolom (spasi ASCII seperti `\s` PCRE tanpa UCP). */
    private fun clean(text: String): String = Php.trim(Regex("[ \\t\\n\\x0B\\f\\r]+").replace(text.replace("|", "/"), " "))
}

/**
 * Pemeriksa sintaks JSON yang meniru urutan galat `json_decode()` PHP 8.3 (`json_last_error_msg()`): karakter kontrol
 * mentah di string, kurung penutup yang salah jenis ("State mismatch"), atau galat sintaks lain. Null bila sah.
 */
internal object PhpJsonCheck {
    private const val SYNTAX = "Syntax error"
    private const val MISMATCH = "State mismatch (invalid or malformed JSON)"
    private const val CTRL = "Control character error, possibly incorrectly encoded"

    private class Failure(val reason: String) : Exception(reason)

    fun error(text: String): String? = try {
        var i = value(text, ws(text, 0))
        i = ws(text, i)
        if (i != text.length) throw Failure(SYNTAX)
        null
    } catch (f: Failure) {
        f.reason
    }

    private fun ws(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in " \t\n\r") i++
        return i
    }

    private fun value(s: String, i: Int): Int {
        if (i >= s.length) throw Failure(SYNTAX)
        return when (val c = s[i]) {
            '{' -> container(s, i, '}', ']', key = true)
            '[' -> container(s, i, ']', '}', key = false)
            '"' -> string(s, i)
            't' -> literal(s, i, "true")
            'f' -> literal(s, i, "false")
            'n' -> literal(s, i, "null")
            else -> if (c == '-' || c in '0'..'9') number(s, i) else throw Failure(SYNTAX)
        }
    }

    /** Objek atau larik; penutup jenis lain setelah isi yang lengkap = state mismatch (aturan object_end/array_end). */
    private fun container(s: String, open: Int, close: Char, wrong: Char, key: Boolean): Int {
        var i = ws(s, open + 1)
        if (i < s.length && s[i] == close) return i + 1
        if (i < s.length && s[i] == wrong) throw Failure(MISMATCH)
        while (true) {
            if (key) {
                if (i >= s.length || s[i] != '"') throw Failure(SYNTAX)
                i = ws(s, string(s, i))
                if (i >= s.length || s[i] != ':') throw Failure(SYNTAX)
                i = ws(s, i + 1)
            }
            i = ws(s, value(s, i))
            if (i >= s.length) throw Failure(SYNTAX)
            when (s[i]) {
                ',' -> i = ws(s, i + 1)
                close -> return i + 1
                wrong -> throw Failure(MISMATCH)
                else -> throw Failure(SYNTAX)
            }
        }
    }

    private fun string(s: String, open: Int): Int {
        var i = open + 1
        while (true) {
            if (i >= s.length) throw Failure(SYNTAX)
            val c = s[i]
            when {
                c == '"' -> return i + 1
                c < ' ' -> throw Failure(CTRL)
                c == '\\' -> {
                    val e = s.getOrNull(i + 1) ?: throw Failure(SYNTAX)
                    when (e) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i += 2
                        'u' -> {
                            val hex = s.substring(minOf(i + 2, s.length), minOf(i + 6, s.length))
                            if (hex.length != 4 || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) throw Failure(SYNTAX)
                            i += 6
                        }
                        else -> throw Failure(SYNTAX)
                    }
                }
                else -> i++
            }
        }
    }

    private fun literal(s: String, i: Int, word: String): Int =
        if (s.startsWith(word, i)) i + word.length else throw Failure(SYNTAX)

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun number(s: String, i: Int): Int = NUMBER.matchAt(s, i)?.let { i + it.value.length } ?: throw Failure(SYNTAX)
}
