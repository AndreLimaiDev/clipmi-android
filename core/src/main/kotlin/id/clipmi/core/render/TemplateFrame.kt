package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlin.math.abs
import kotlin.math.ln

/**
 * Template bingkai buatan tim (padanan `App\Render\TemplateFrame` desktop): gambar PNG/WebP selayar penuh yang ditempel
 * di atas video, dengan bagian transparan tempat video tampil. Area itu dicari dari kanal alpha gambar.
 *
 * Desktop memperkecil kanal alpha ke kisi [GRID]×[GRID] dengan FFmpeg (`scale=…:flags=area`); di sini gambar diperkecil
 * dengan rata-rata luas yang sama oleh [downscale], lalu langkah pencariannya identik.
 */
object TemplateFrame {
    /** Gambar diperkecil ke kisi ini untuk mencari area transparan (ketelitian 0,5%). */
    const val GRID = 200

    class Rejected(message: String) : Exception(message)

    data class Analysis(val aspect: String, val area: TemplateArea)

    /**
     * @param alpha kanal alpha gambar asli (0–255), baris demi baris, ukuran [width]×[height].
     * @throws Rejected bila tidak ada area transparan yang cukup besar.
     */
    fun analyze(alpha: IntArray, width: Int, height: Int): Analysis =
        Analysis(aspectFor(width, height), transparentArea(downscale(alpha, width, height)))

    /** Area transparan dari kisi alpha [GRID]×[GRID] (pecahan layar). */
    fun transparentArea(grid: IntArray): TemplateArea {
        val transparent = { x: Int, y: Int -> grid[y * GRID + x] < 128 }

        // Baris yang sebagian besar transparan, lalu deretan terpanjang di antaranya = tinggi area video.
        val (top, bottom) = longestRun((0 until GRID).map { y -> (0 until GRID).count { x -> transparent(x, y) } >= GRID / 2.0 })
        if (top == null || bottom == null) {
            throw Rejected(
                "Template ini tidak punya area transparan untuk video. Simpan desainnya sebagai PNG atau WebP dengan bagian video " +
                    "dikosongkan (transparan).",
            )
        }
        val rows = top..bottom
        val (left, right) = longestRun((0 until GRID).map { x -> rows.count { y -> transparent(x, y) } >= rows.count() / 2.0 })
        if (left == null || right == null) throw Rejected("Area transparan di template terlalu kecil untuk video. Kosongkan bagian video yang lebih besar.")

        val area = TemplateArea(
            x = Php.round(left.toDouble() / GRID, 4),
            y = Php.round(top.toDouble() / GRID, 4),
            width = Php.round((right - left + 1).toDouble() / GRID, 4),
            height = Php.round((bottom - top + 1).toDouble() / GRID, 4),
        )
        if (area.width < 0.2 || area.height < 0.15) {
            throw Rejected("Area transparan di template terlalu kecil untuk video. Kosongkan bagian video yang lebih besar.")
        }
        return area
    }

    /** Rasio [FrameStyle.ASPECTS] dengan perbandingan lebar/tinggi terdekat. */
    fun aspectFor(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "9:16"
        val ratio = width.toDouble() / height
        return FrameStyle.ASPECTS.entries.minBy { abs(ln(it.value.width.toDouble() / it.value.height / ratio)) }.key
    }

    /** Rata-rata luas (seperti `flags=area` FFmpeg): setiap sel kisi = rata-rata piksel yang ditutupinya, berbobot luas. */
    fun downscale(alpha: IntArray, width: Int, height: Int): IntArray {
        val grid = IntArray(GRID * GRID)
        val sx = width.toDouble() / GRID
        val sy = height.toDouble() / GRID
        for (gy in 0 until GRID) {
            val y0 = gy * sy
            val y1 = y0 + sy
            for (gx in 0 until GRID) {
                val x0 = gx * sx
                val x1 = x0 + sx
                var sum = 0.0
                var weight = 0.0
                var y = y0.toInt()
                while (y < y1 && y < height) {
                    val wy = minOf(y1, y + 1.0) - maxOf(y0, y.toDouble())
                    var x = x0.toInt()
                    while (x < x1 && x < width) {
                        val w = wy * (minOf(x1, x + 1.0) - maxOf(x0, x.toDouble()))
                        sum += alpha[y * width + x] * w
                        weight += w
                        x++
                    }
                    y++
                }
                grid[gy * GRID + gx] = if (weight > 0) Math.round(sum / weight).toInt() else 255
            }
        }
        return grid
    }

    /** Awal dan akhir deretan true terpanjang (yang pertama bila sama panjang). */
    private fun longestRun(flags: List<Boolean>): Pair<Int?, Int?> {
        var best: Pair<Int, Int>? = null
        var start: Int? = null
        (flags + false).forEachIndexed { i, flag ->
            if (flag) {
                if (start == null) start = i
            } else if (start != null) {
                if (best == null || i - 1 - start!! > best!!.second - best!!.first) best = start!! to i - 1
                start = null
            }
        }
        return best?.first to best?.second
    }
}
