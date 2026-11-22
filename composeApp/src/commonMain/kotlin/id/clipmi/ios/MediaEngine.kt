package id.clipmi.ios

/**
 * Bagian yang ditulis di Swift (iosApp): yt-dlp lewat Python tertanam dan penggabungan video+audio AVFoundation.
 * Antarmuka biasa (bukan suspend/lambda) supaya mudah diimplementasikan dari Swift.
 */
interface MediaEngine {
    /**
     * Mengunduh video YouTube ke [directory] lalu menggabungkan video dan audionya menjadi `source.mp4`.
     * Dipanggil dari main thread; Swift menjalankannya di thread latar dan memanggil [listener] di main thread.
     */
    fun downloadYouTube(url: String, directory: String, listener: DownloadListener)
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
