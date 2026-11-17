package id.clipmi.android.nativebridge

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

/**
 * libass lewat JNI: menggambar file ASS ClipMI (AssWriter) ke Bitmap ARGB_8888. Dipakai pratinjau editor dan overlay
 * subtitle saat render, sehingga yang terlihat sama dengan hasilnya.
 */
class AssRenderer(width: Int, height: Int, fontsDir: File, defaultFont: File?) : Closeable {
    private var handle: Long = nativeCreate(width, height, fontsDir.absolutePath, defaultFont?.absolutePath)

    init {
        check(handle != 0L) { "libass gagal dimulai" }
    }

    fun load(ass: String) {
        check(nativeLoadTrack(handle, ass.toByteArray(Charsets.UTF_8))) { "File ASS tidak terbaca" }
    }

    /**
     * Menggambar frame pada [timeMs]. Mengembalikan false bila gambarnya sama dengan panggilan sebelumnya (bitmap
     * tidak disentuh), sehingga overlay tidak perlu diunggah ulang ke GPU.
     */
    fun render(timeMs: Long, bitmap: Bitmap, clear: Boolean = true, force: Boolean = false): Boolean {
        val result = nativeRender(handle, timeMs, bitmap, clear, force)
        check(result >= 0) { "libass gagal menggambar frame" }
        return result > 0
    }

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("clipmi_native")
        }

        val version: String get() = nativeVersion()

        /** Menyalin font yang dibundel (assets/fonts) ke folder aplikasi, karena libass membaca font dari file. */
        fun installFonts(context: Context): File {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            context.assets.list("fonts").orEmpty().filter { it.endsWith(".ttf") || it.endsWith(".otf") }.forEach { name ->
                val target = File(dir, name)
                if (!target.exists()) context.assets.open("fonts/$name").use { input -> target.outputStream().use(input::copyTo) }
            }
            return dir
        }

        @JvmStatic private external fun nativeCreate(width: Int, height: Int, fontsDir: String, defaultFont: String?): Long
        @JvmStatic private external fun nativeLoadTrack(handle: Long, data: ByteArray): Boolean
        @JvmStatic private external fun nativeRender(handle: Long, timeMs: Long, bitmap: Bitmap, clear: Boolean, force: Boolean): Int
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeVersion(): String
    }
}
