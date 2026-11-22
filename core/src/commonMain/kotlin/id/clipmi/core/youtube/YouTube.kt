package id.clipmi.core.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sumber transkrip; nilai sama dengan enum `TranscriptSource` desktop (disimpan di database). */
enum class TranscriptSource(val value: String, val label: String) {
    YouTubeAuto("youtube_auto", "YouTube (Otomatis)"),
    YouTubeManual("youtube_manual", "YouTube (Manual)"),
    ImportSrt("import_srt", "Impor SRT"),
    ImportVtt("import_vtt", "Impor VTT"),
    Groq("groq", "Groq Whisper"),
    Api("api", "Transkripsi API");

    companion object {
        fun from(value: String?): TranscriptSource? = entries.firstOrNull { it.value == value }

        /** Sama dengan `fromImportedFilename` desktop: .vtt → VTT, lainnya SRT. */
        fun fromImportedFilename(filename: String): TranscriptSource =
            if (filename.substringAfterLast('.', "").equals("vtt", ignoreCase = true)) ImportVtt else ImportSrt
    }
}

/** Memilih subtitle bahasa Indonesia terbaik dari info.json yt-dlp (padanan `SubtitlePicker`). */
object SubtitlePicker {
    data class Track(val language: String, val source: TranscriptSource)

    /**
     * Urutan: manual "id", lalu otomatis asli "id-orig", lalu otomatis "id". Otomatis "id" tanpa "-orig" bisa berupa
     * terjemahan mesin bila bahasa asli video bukan Indonesia, jadi paling akhir.
     */
    fun pick(info: JsonObject): Track? {
        val manual = info["subtitles"] as? JsonObject
        val automatic = info["automatic_captions"] as? JsonObject
        return when {
            hasJson3(manual, "id") -> Track("id", TranscriptSource.YouTubeManual)
            hasJson3(automatic, "id-orig") -> Track("id-orig", TranscriptSource.YouTubeAuto)
            hasJson3(automatic, "id") -> Track("id", TranscriptSource.YouTubeAuto)
            else -> null
        }
    }

    private fun hasJson3(tracks: JsonObject?, language: String): Boolean =
        (tracks?.get(language) as? JsonArray).orEmpty().any {
            ((it as? JsonObject)?.get("ext") as? JsonPrimitive)?.content == "json3"
        }
}

/** Padanan `App\YouTube\YouTubeUrl`. */
object YouTubeUrl {
    /** Batas video per kali buat project, dari beberapa link atau satu playlist. */
    const val MAX_BATCH = 30

    private val HOST = Regex("""^(?:https?://)?(?:www\.|m\.|music\.)?(youtube\.com|youtu\.be)(/\S*)?$""", RegexOption.IGNORE_CASE)
    private val SHORT = Regex("""^/([A-Za-z0-9_-]{11})(?:[/?&#]|$)""")
    private val WATCH = Regex("""[?&]v=([A-Za-z0-9_-]{11})(?:[&#]|$)""")
    private val PATH = Regex("""^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})(?:[/?&#]|$)""")
    private val PLAYLIST = Regex("""^(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/\S*[?&]list=([A-Za-z0-9_-]{10,})(?:[&#]|$)""", RegexOption.IGNORE_CASE)

    /** ID video (11 karakter) dari watch?v=, youtu.be/, /shorts/, /live/, /embed/, termasuk m. dan music. */
    fun videoId(url: String): String? {
        val m = HOST.find(url.trim { it in " \t\n\r\u0000\u000B" }) ?: return null
        val path = m.groupValues[2]
        val patterns = if (m.groupValues[1].lowercase() == "youtu.be") listOf(SHORT) else listOf(WATCH, PATH)
        return patterns.firstNotNullOfOrNull { it.find(path)?.groupValues?.get(1) }
    }

    /** ID playlist dari link playlist; link video di dalam playlist tetap dianggap satu video. */
    fun playlistId(url: String): String? {
        val trimmed = url.trim { it in " \t\n\r\u0000\u000B" }
        if (videoId(trimmed) != null) return null
        return PLAYLIST.find(trimmed)?.groupValues?.get(1)
    }

    fun canonical(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    /** Link YouTube pertama di teks bebas (misalnya teks yang dibagikan aplikasi YouTube: "Judul https://youtu.be/..."). */
    fun firstUrlIn(text: String): String? =
        Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull { videoId(it) != null || playlistId(it) != null }
}
