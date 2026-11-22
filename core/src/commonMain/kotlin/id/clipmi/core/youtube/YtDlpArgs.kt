package id.clipmi.core.youtube

/**
 * Argumen yt-dlp, padanan `App\YouTube\YtDlp` desktop.
 *
 * Bedanya dengan desktop:
 * - Di HP tidak ada FFmpeg untuk menggabungkan video dan audio, jadi keduanya diunduh sebagai dua file terpisah
 *   (format dipisah koma) lalu digabung tanpa encode ulang oleh MediaMuxer Android.
 * - JavaScript runtime untuk tantangan YouTube adalah QuickJS yang dibundel youtubedl-android (`libqjs.so`); library
 *   itu sendiri yang menambahkan `--js-runtimes quickjs:<path>` serta `--cache-dir`. Desktop memakai Deno.
 */
object YtDlpArgs {
    /** Cabang video dari `YtDlp::VIDEO_FORMAT` desktop (H.264 ≤1080p), tanpa cabang yang butuh penggabungan FFmpeg. */
    const val VIDEO_ONLY_FORMAT = "bv*[height<=1080][vcodec^=avc1]"
    const val AUDIO_ONLY_FORMAT = "ba[ext=m4a]"

    /** Video dan audio dalam satu pemanggilan; koma membuat yt-dlp mengunduh keduanya sebagai file terpisah. */
    const val SPLIT_FORMAT = "$VIDEO_ONLY_FORMAT,$AUDIO_ONLY_FORMAT"

    /** Sama dengan `SubtitlePicker::LANGUAGES` desktop. */
    const val SUB_LANGS = "id,id-orig"

    const val PROGRESS_PREFIX = "clipmi-progress"

    /**
     * Argumen dasar. cacheDir wajib: tanpa --cache-dir, youtubedl-android menambahkan --no-cache-dir, sehingga player
     * YouTube yang sudah dipra-proses untuk tantangan JS tidak disimpan dan setiap unduhan memecahkannya ulang
     * (±22 detik di HP kelas menengah).
     */
    fun base(cacheDir: String): List<String> =
        listOf("--no-config", "--no-playlist", "--no-mtime", "--cache-dir", cacheDir)

    /** Metadata, subtitle json3, dan thumbnail tanpa video; sama dengan `fetchInfoAndSubtitles` desktop. */
    fun metadata(cacheDir: String, directory: String, name: String = "youtube"): List<String> = base(cacheDir) + listOf(
        "--skip-download",
        "--write-info-json",
        "--write-thumbnail",
        "--write-subs",
        "--write-auto-subs",
        "--sub-langs", SUB_LANGS,
        "--sub-format", "json3",
        "--ignore-errors",
        "-o", outputTemplate(directory, "$name.%(ext)s"),
    )

    /**
     * Video (H.264 ≤1080p) dan audio (AAC) sebagai dua file: {name}.stream.mp4 dan {name}.stream.m4a. Dibedakan dari
     * ekstensi, karena dengan --load-info-json field seperti height ikut terbawa ke format audio.
     */
    fun media(cacheDir: String, directory: String, name: String = "source"): List<String> = base(cacheDir) + listOf(
        "-f", SPLIT_FORMAT,
        "--newline",
        "--progress-template", "download:$PROGRESS_PREFIX %(progress._percent_str)s",
        "-o", outputTemplate(directory, "$name.stream.%(ext)s"),
    )

    /** "%" di path harus di-escape karena yt-dlp membacanya sebagai template (sama dengan desktop). */
    fun outputTemplate(directory: String, file: String): String =
        directory.replace("%", "%%").trimEnd('/') + "/" + file

    fun parseProgress(line: String): Float? =
        Regex("$PROGRESS_PREFIX\\s+([\\d.]+)%").find(line)?.groupValues?.get(1)?.toFloatOrNull()?.coerceAtMost(100f)
}
