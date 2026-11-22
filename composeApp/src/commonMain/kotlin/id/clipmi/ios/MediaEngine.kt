package id.clipmi.ios

/**
 * Bagian yang ditulis di Swift (iosApp): yt-dlp lewat Python tertanam, AVFoundation, FFmpeg di dalam proses
 * (ClipMIFFmpeg.xcframework), FreeType untuk lebar teks, dan Photos. Antarmuka biasa (bukan suspend/lambda) supaya
 * mudah diimplementasikan dari Swift.
 */
interface MediaEngine {
    /**
     * Mengunduh video YouTube ke [directory] lalu menggabungkan video dan audionya menjadi `source.mp4`.
     * Dipanggil dari main thread; Swift menjalankannya di thread latar dan memanggil [listener] di main thread.
     */
    fun downloadYouTube(url: String, directory: String, listener: DownloadListener)

    /** Folder font bawaan di dalam bundle aplikasi (Poppins-Bold.ttf, dll.; sama dengan assets/fonts Android). */
    fun fontsDirectory(): String

    /** Lebar piksel [text] dengan font [fontPath] pada [points] poin (FreeType, sama dengan Android); -1 bila gagal. */
    fun textWidth(fontPath: String, points: Double, text: String): Double

    /** Info video: JSON `{"width", "height", "fps", "has_audio", "duration"}` (ukuran sudah diputar), atau null. */
    fun probeVideo(path: String): String?

    /**
     * Menjalankan ffmpeg dengan [args] (tanpa nama program) di folder [directory]; progres dihitung dari
     * [durationSeconds]. Satu render pada satu waktu. [listener] dipanggil di main thread.
     */
    fun runFfmpeg(args: List<String>, directory: String, durationSeconds: Double, listener: RenderListener)

    fun cancelFfmpeg()

    /** Menyimpan video ke Photos (meminta izin tambah bila perlu). [listener] dipanggil di main thread. */
    fun saveVideoToPhotos(path: String, listener: SaveListener)

    /** Menyimpan gambar (JPEG) ke Photos, misalnya sampul klip. [listener] dipanggil di main thread. */
    fun saveImageToPhotos(path: String, listener: SaveListener)

    /**
     * Satu frame video pada [seconds] (AVAssetImageGenerator, rotasi diterapkan) sebagai JPEG di [output], sisi terpanjang
     * paling besar [maxSize] piksel. Sinkron; false bila gagal.
     */
    fun videoFrame(path: String, seconds: Double, output: String, maxSize: Int): Boolean

    /**
     * Memilih satu gambar dari Photos (PHPicker, tanpa izin galeri) lalu menyimpannya sebagai JPEG tegak di [output].
     * [listener] dipanggil di main thread: path = [output], atau null bila batal/gagal.
     */
    fun pickImage(output: String, listener: PickListener)

    /**
     * Wajah di gambar [imagePath] (Apple Vision, padanan FaceModels Android): JSON array `[{"cx", "w", "open"}]`,
     * `cx`/`w` = tengah dan lebar kotak wajah sebagai pecahan lebar gambar, `open` = jarak bibir dalam atas–bawah dibagi
     * lebar mulut (bisa tidak ada). Sinkron.
     */
    fun detectFaces(imagePath: String): String

    /**
     * Gambar [imagePath] sebagai byte abu-abu (1 byte per piksel, baris atas dulu) di [output]; hasil `"lebar×tinggi"`
     * seperti `"1600x432"`, atau null bila gagal. Sinkron.
     */
    fun grayPixels(imagePath: String, output: String): String?

    /** Lembar bagikan iOS untuk file [path] (simpan ke Files, Google Drive, AirDrop, dan seterusnya). */
    fun shareFile(path: String)

    /**
     * Memilih satu file ZIP dari aplikasi Files (disalin) ke [output]. [listener] dipanggil di main thread: path =
     * [output], atau null bila batal/gagal.
     */
    fun pickFile(output: String, listener: PickListener)

    /**
     * Mengganti semua pengingat jadwal dengan [json]: array `[{"id", "at" (epoch ms), "title", "body"}]` sebagai notifikasi
     * lokal (UNUserNotificationCenter). Izin notifikasi diminta saat pertama dipanggil.
     */
    fun scheduleReminders(json: String)

    /**
     * Statistik publik satu postingan lewat yt-dlp (Python tertanam, tanpa mengunduh video). [listener] dipanggil di main
     * thread dengan JSON `{"ok": true, "views", "likes", "comments", "shares"}` atau `{"ok": false, "error"}`.
     */
    fun fetchStats(url: String, directory: String, listener: ResultListener)

    /**
     * Permintaan HTTP (URLSession) untuk Mode API AI dan YouTube Data API. [headersJson] objek JSON nama → nilai; [body]
     * null untuk GET. [listener] dipanggil di main thread: kode HTTP (0 = tidak tersambung), isi jawaban, header
     * `retry-after` bila ada, dan pesan kesalahan jaringan ("timeout" bila melewati [timeoutSeconds]).
     */
    fun httpRequest(method: String, url: String, headersJson: String, body: String?, timeoutSeconds: Int, listener: HttpListener)

    // Rahasia (API key) di Keychain iOS, padanan Keystore Android; tidak ikut cadangan.

    fun secretGet(name: String): String?

    fun secretPut(name: String, value: String)

    fun secretRemove(name: String)

    /** Pengingat diizinkan pengguna (null = belum pernah ditanya). [listener] dipanggil di main thread. */
    fun remindersAllowed(listener: AllowedListener)

    // libass untuk pratinjau (ass_core.c, sama dengan Android). Handle 0 = gagal.

    /** Renderer libass seukuran [width]×[height] dengan font bawaan. */
    fun assCreate(width: Int, height: Int): Long

    /** Memuat (mengganti) trek ASS. */
    fun assLoad(handle: Long, ass: String): Boolean

    /**
     * Menggambar frame pada [timeMs] ke buffer RGBA premultiplied di alamat [pixels] ([width]×[height]×4 byte, milik
     * pemanggil dan di-pin selama panggilan). [clear] = kosongkan dulu. Hasil: -1 gagal, 0 tidak berubah, 1/2 berubah.
     */
    fun assRender(handle: Long, timeMs: Long, pixels: Long, width: Int, height: Int, clear: Boolean): Int

    fun assDestroy(handle: Long)
}

interface DownloadListener {
    /** Progres 0..1. */
    fun progress(fraction: Double, status: String)

    /**
     * Selesai: teks JSON `{"ok": true, "title", "duration", "video_id", "file", "mb"}` atau
     * `{"ok": false, "error"}`.
     */
    fun finished(resultJson: String)
}

interface RenderListener {
    /** Progres 0..1. */
    fun progress(fraction: Double)

    /** [code] 0 = berhasil, 255 = dibatalkan; [logTail] baris terakhir log FFmpeg untuk pesan gagal. */
    fun finished(code: Int, logTail: String)
}

interface SaveListener {
    /** null = tersimpan; selain itu pesan gagal. */
    fun finished(error: String?)
}

interface HttpListener {
    fun finished(status: Int, body: String, retryAfter: String?, error: String?)
}

interface ResultListener {
    fun finished(resultJson: String)
}

interface AllowedListener {
    /** "allowed", "denied", atau "unknown" (belum pernah ditanya). */
    fun finished(state: String)
}

interface PickListener {
    /** Path gambar tersimpan, atau null bila batal/gagal. */
    fun finished(path: String?)
}
