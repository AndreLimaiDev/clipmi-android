package id.clipmi.core.schedule

import id.clipmi.core.php.Php
import id.clipmi.core.youtube.YouTubeUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.LocalDateTime
import kotlin.math.roundToLong

/** Angka statistik satu pengambilan; null bila platform tidak menampilkannya. */
data class StatNumbers(val views: Long?, val likes: Long?, val comments: Long?, val shares: Long?) {
    val isEmpty: Boolean get() = views == null && likes == null && comments == null

    /**
     * Engagement rate: (suka + komentar + share) / tayangan. Null bila tayangan tidak diketahui atau nol, atau bila
     * suka, komentar, dan share semuanya tidak diketahui (`PostStat::engagementRate`).
     */
    val engagementRate: Double?
        get() {
            if (views == null || views == 0L || (likes == null && comments == null && shares == null)) return null
            return ((likes ?: 0) + (comments ?: 0) + (shares ?: 0)).toDouble() / views
        }
}

/** Satu catatan statistik bertanggal. */
data class StatPoint(val recordedAt: LocalDateTime, val numbers: StatNumbers)

class StatsException(message: String) : Exception(message)

/** Sumber statistik (`PostStat::SOURCES`). */
object StatSource {
    const val YOUTUBE_API = "youtube_api"
    const val YTDLP = "ytdlp"
    const val MANUAL = "manual"

    val LABELS = mapOf(YOUTUBE_API to "YouTube API", YTDLP to "yt-dlp", MANUAL to "manual")
}

/**
 * Statistik postingan, padanan `PostStatsFetcher`, `StatsRefresher`, `ScheduleAccount::statAt`, dan ringkasan di
 * tampilan Statistik desktop.
 */
object PostStats {
    const val PREFIX = "clipmi-stats"

    /** Umur postingan (hari) untuk membandingkan klip pada umur yang sama. */
    val AGES = listOf(1, 7, 30)

    /** Setelah umur ini (hari) statistik tidak diambil lagi otomatis. */
    const val MAX_AGE_DAYS = 31L

    /** Jarak antar pengambilan menurut umur postingan: [umur kurang dari (jam), jarak (jam)]. */
    private val INTERVALS = listOf(48.0 to 3L, 8 * 24.0 to 12L, Double.MAX_VALUE to 48L)

    /** Argumen yt-dlp untuk statistik publik tanpa mengunduh video (`YtDlp::stats`). */
    fun ytDlpArgs(base: List<String>, url: String): List<String> =
        base + listOf("--skip-download", "--no-warnings", "--print", "$PREFIX %(view_count)s|%(like_count)s|%(comment_count)s|%(repost_count)s", "--", url)

    private val STATS_LINE = Regex("$PREFIX ([^|\\r\\n]*)\\|([^|\\r\\n]*)\\|([^|\\r\\n]*)\\|([^|\\r\\n]*)")
    private val DIGITS = Regex("^[0-9]+$")

    fun parseYtDlp(output: String): StatNumbers? {
        val m = STATS_LINE.find(output) ?: return null
        fun number(value: String) = value.trim().takeIf { DIGITS.matches(it) }?.toLongOrNull()
        return StatNumbers(number(m.groupValues[1]), number(m.groupValues[2]), number(m.groupValues[3]), number(m.groupValues[4]))
    }

    /** URL YouTube Data API untuk satu video (key ditambahkan pemanggil di header atau query). */
    fun youtubeApiVideoId(url: String): String? = YouTubeUrl.videoId(url)

    /**
     * Membaca jawaban `videos?part=statistics`. [status] adalah kode HTTP; [body] JSON jawaban (null bila bukan JSON).
     */
    fun parseYouTubeApi(status: Int, body: JsonObject?): StatNumbers {
        if (status !in 200..299) {
            val error = body?.get("error")?.jsonObject
            val reason = runCatching { error?.get("errors")?.jsonArray?.get(0)?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
            throw StatsException(
                when {
                    reason == "keyInvalid" || reason == "badRequest" || status == 400 -> "API key YouTube ditolak. Periksa key di Pengaturan."
                    "quota" in reason || reason == "rateLimitExceeded" -> "Kuota YouTube Data API hari ini habis. Coba lagi besok."
                    else -> "YouTube Data API gagal ($status). " + (error?.get("message")?.jsonPrimitive?.contentOrNull ?: "")
                },
            )
        }
        val statistics = runCatching { body?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("statistics")?.jsonObject }.getOrNull()
            ?: throw StatsException("Video tidak ditemukan atau tidak publik.")
        fun number(field: String) = statistics[field]?.jsonPrimitive?.contentOrNull?.takeIf { DIGITS.matches(it) }?.toLongOrNull()
        return StatNumbers(number("viewCount"), number("likeCount"), number("commentCount"), null)
    }

    /** Pesan kegagalan yt-dlp dalam bahasa pengguna (`PostStatsFetcher::explain`). */
    fun explain(url: String, error: String): String {
        // Host dari link (pengganti java.net.URI): bagian sesudah "skema://" sampai "/", "?", "#", atau ":".
        val host = Regex("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^@/?#]*@)?([^/?#:]*)").find(url)?.groupValues?.get(1).orEmpty().lowercase()
        return when {
            "instagram.com" in host -> "Instagram tidak mengizinkan statistik dibaca tanpa login. Isi angkanya manual."
            "facebook.com" in host || "fb.watch" in host -> "Facebook tidak mengizinkan statistik dibaca tanpa login. Isi angkanya manual."
            "unsupported url" in error.lowercase() -> "Link ini tidak dikenali. Pastikan link postingannya, bukan link profil."
            else -> "Statistik tidak bisa dibaca: $error"
        }
    }

    const val NO_NUMBERS = "Link terbaca, tetapi platformnya tidak menampilkan angka statistik. Isi manual."

    /**
     * Statistik pada umur [days] hari sejak terbit: catatan terakhir sampai umur itu, asalkan diambil tidak jauh
     * sebelumnya (minimal 3/4 umurnya), supaya angka hari ke-7 tidak diisi angka hari ke-2.
     * @param stats catatan urut waktu.
     */
    fun statAt(publishedAt: LocalDateTime?, stats: List<StatPoint>, days: Int): StatPoint? {
        publishedAt ?: return null
        val until = publishedAt.plusDays(days.toLong())
        val from = publishedAt.plusMinutes((days * 24 * 60 * 0.75).roundToLong())
        return stats.lastOrNull { it.recordedAt >= from && it.recordedAt <= until }
    }

    /**
     * Postingan yang sudah waktunya diambil lagi (`StatsRefresher::due`): makin muda, makin sering, supaya angka hari
     * ke-1, ke-7, dan ke-30 tertangkap. Pemanggil sudah memilih yang terbit, punya link, dan berumur ≤ 31 hari.
     */
    fun isDue(publishedAt: LocalDateTime, checkedAt: LocalDateTime?, now: LocalDateTime): Boolean {
        checkedAt ?: return true
        // Carbon 3 diffInHours: selisih pecahan jam.
        val age = (now.wallMillis() - publishedAt.wallMillis()) / 3_600_000.0
        val interval = INTERVALS.first { age < it.first }.second
        return checkedAt <= now.plusMinutes(-interval * 60 + 5)
    }

    /** Batas umur untuk pembaruan otomatis: terbit sejak waktu ini. */
    fun refreshFrom(now: LocalDateTime): LocalDateTime = now.plusDays(-MAX_AGE_DAYS)

    /** Ringkasan satu akun dari tayangan terakhir tiap postingannya: total, median (pembanding "×"), dan terbaik. */
    fun summary(latestViews: List<Long?>): AccountSummary {
        val views = latestViews.filterNotNull().sorted()
        val median = when {
            views.isEmpty() -> null
            views.size % 2 == 1 -> views[views.size / 2]
            // Laravel median: rata-rata dua nilai tengah, lalu (int) dipotong.
            else -> ((views[views.size / 2 - 1] + views[views.size / 2]) / 2.0).toLong()
        }
        return AccountSummary(
            count = latestViews.size,
            total = views.takeIf { it.isNotEmpty() }?.sum(),
            median = median,
            best = views.maxOrNull(),
            measured = views.size,
        )
    }

    /** Jumlah postingan terukur minimal agar tayangan dibandingkan dengan median akunnya. */
    const val MIN_MEASURED = 3

    /** Tayangan dibanding median akun ("1,5×"), atau null bila belum cukup postingan terukur. */
    fun ratio(views: Long?, summary: AccountSummary): Double? {
        val median = summary.median?.takeIf { summary.measured >= MIN_MEASURED && it != 0L } ?: return null
        return views?.let { it.toDouble() / median }
    }

    /** Angka dengan titik ribuan; "–" bila tidak diketahui (`number_format($v, 0, ',', '.')`). */
    fun number(value: Long?): String = value?.let { (if (it < 0) "-" else "") + group(kotlin.math.abs(it).toString()) } ?: "–"

    /**
     * Titik tiap tiga angka, ditulis sendiri: format bawaan dengan Locale.ROOT tidak memberi pemisah ribuan di Android
     * (ICU), walaupun di JVM memberi.
     */
    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(".").reversed()

    /** Persen satu desimal dengan koma ("10,0%"). */
    fun rate(value: Double?): String = value?.let { decimal(it * 100) + "%" } ?: "–"

    /** Satu desimal dengan koma, dibulatkan seperti number_format PHP (setengah menjauhi nol). */
    fun decimal(value: Double): String {
        // Pembulatan PHP (Php.numberFormat, setengah menjauhi nol), lalu titik ribuan dan koma desimal.
        val text = Php.numberFormat(value, 1)
        val negative = text.startsWith("-")
        val digits = text.removePrefix("-")
        return (if (negative) "-" else "") + group(digits.substringBefore('.')) + "," + digits.substringAfter('.')
    }
}

data class AccountSummary(val count: Int, val total: Long?, val median: Long?, val best: Long?, val measured: Int)
