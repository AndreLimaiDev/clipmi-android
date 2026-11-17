package id.clipmi.android.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import id.clipmi.android.nativebridge.AssRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Uji libass: setiap file ASS dari desktop (assets/lab, diekspor tools/export-ass-fixtures.php) digambar di atas latar
 * hitam pada beberapa waktu ke PNG, untuk dibandingkan piksel demi piksel dengan filter `ass` FFmpeg desktop
 * (tools/compare-ass.sh). Lalu diukur kecepatan menggambar semua frame (mode overlay transparan).
 */
class AssLab(private val context: Context, private val log: LabLog) {
    fun run(fps: Int) {
        val fonts = AssRenderer.installFonts(context)
        val out = File(log.dir, "ass").apply { deleteRecursively(); mkdirs() }
        log.line("libass ${AssRenderer.version}, font: ${fonts.list().orEmpty().sorted()}")

        val manifest = Json.parseToJsonElement(context.assets.open("lab/manifest.json").bufferedReader().readText()).jsonArray
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        var totalFrames = 0
        var totalNanos = 0L

        AssRenderer(WIDTH, HEIGHT, fonts, File(fonts, "Poppins-Bold.ttf")).use { ass ->
            for (entry in manifest) {
                val name = entry.jsonObject.getValue("name").jsonPrimitive.content
                val duration = entry.jsonObject.getValue("duration").jsonPrimitive.content.toDouble()
                ass.load(context.assets.open("lab/$name.ass").bufferedReader().readText())

                // Frame acuan: waktu yang sama dipakai tools/compare-ass.sh untuk FFmpeg desktop.
                for (ms in SNAPSHOT_MS) {
                    bitmap.eraseColor(Color.BLACK)
                    ass.render(ms, bitmap, clear = false, force = true)
                    File(out, "${name}_$ms.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }

                // Kecepatan: semua frame klip pada fps video, seperti overlay saat render.
                val frames = (duration * fps).toInt()
                var changed = 0
                val started = System.nanoTime()
                for (i in 0 until frames) if (ass.render(i * 1000L / fps, bitmap)) changed++
                val nanos = System.nanoTime() - started
                totalFrames += frames
                totalNanos += nanos
                log.line("$name: $frames frame, $changed berubah, rata-rata ${"%.2f".format(java.util.Locale.ROOT, nanos / 1e6 / frames)} ms/frame")
            }
        }
        log.result(
            "ass",
            mapOf(
                "ok" to true,
                "libass" to AssRenderer.version,
                "frames" to totalFrames,
                "avg_ms_per_frame" to "%.2f".format(java.util.Locale.ROOT, totalNanos / 1e6 / totalFrames),
                "snapshots" to out.absolutePath,
            ),
        )
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        val SNAPSHOT_MS = listOf(500L, 1000L, 2200L, 3400L)
    }
}
