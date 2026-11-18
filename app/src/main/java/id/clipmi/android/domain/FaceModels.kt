package id.clipmi.android.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Wajah dalam satu frame: kotak dalam piksel frame dan keyakinan BlazeFace. */
data class FaceBox(val x: Float, val y: Float, val width: Float, val height: Float, val score: Float) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2

    fun iou(other: FaceBox): Float {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val right = min(x + width, other.x + other.width)
        val bottom = min(y + height, other.y + other.height)
        if (right <= left || bottom <= top) return 0f
        val overlap = (right - left) * (bottom - top)
        return overlap / (width * height + other.width * other.height - overlap)
    }
}

/**
 * Model wajah MediaPipe (Apache 2.0, assets/models) dijalankan LiteRT di CPU HP, tanpa layanan Google:
 * BlazeFace jarak dekat (128×128) untuk mencari wajah, dan landmark wajah (256×256, 478 titik) untuk mengukur bukaan
 * mulut. Tidak thread-safe: satu pemanggil dalam satu waktu.
 */
class FaceModels(private val context: Context) : AutoCloseable {
    private var detector: Model? = null
    private var landmarks: Model? = null

    private class Model(val compiled: CompiledModel) {
        val inputs: List<TensorBuffer> = compiled.createInputBuffers()
        val outputs: List<TensorBuffer> = compiled.createOutputBuffers()

        fun run(input: FloatArray): List<FloatArray> {
            inputs[0].writeFloat(input)
            compiled.run(inputs, outputs)
            return outputs.map { it.readFloat() }
        }

        fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            compiled.close()
        }
    }

    private fun model(name: String): Model = Model(CompiledModel.create(context.assets, name, CompiledModel.Options(Accelerator.CPU)))

    /**
     * Wajah di [frame]. Frame dibagi tiga potongan persegi yang saling tumpang (BlazeFace butuh gambar persegi dan wajah
     * yang cukup besar); bila tidak ada wajah, dicoba lagi dengan potongan setengah ukuran untuk wajah kecil (gambar
     * lebar).
     */
    fun faces(frame: Bitmap): List<FaceBox> {
        val w = frame.width
        val h = frame.height
        val side = min(w, h)
        val found = squares(w, h, side, 3).flatMap { (x, y) -> detectSquare(frame, x, y, side) }.toMutableList()
        if (found.isEmpty() && side >= 256) {
            val half = side / 2
            for ((x, y) in squares(w, h, half, if (w >= h) 5 else 3, rows = 2)) found += detectSquare(frame, x, y, half)
        }
        return suppress(found)
    }

    /** Posisi kiri-atas potongan persegi berukuran [side] yang menutupi frame: [count] per baris, [rows] baris. */
    private fun squares(w: Int, h: Int, side: Int, count: Int, rows: Int = 1): List<Pair<Int, Int>> {
        fun spread(length: Int, n: Int): List<Int> =
            if (length <= side || n <= 1) listOf(maxOf(0, (length - side) / 2)) else (0 until n).map { (length - side) * it / (n - 1) }.distinct()
        val along = if (w >= h) spread(w, count) else spread(h, count)
        val across = if (w >= h) spread(h, rows) else spread(w, rows)
        return along.flatMap { a -> across.map { b -> if (w >= h) a to b else b to a } }
    }

    private fun detectSquare(frame: Bitmap, left: Int, top: Int, side: Int): List<FaceBox> {
        val model = detector ?: model("models/face_detector.tflite").also { detector = it }
        val input = pixels(crop(frame, left.toFloat(), top.toFloat(), side.toFloat(), DETECTOR_SIZE), signed = true)
        val outputs = model.run(input)
        val boxes = outputs.first { it.size == ANCHORS.size / 2 * 16 }
        val scores = outputs.first { it.size == ANCHORS.size / 2 }
        val result = ArrayList<FaceBox>()
        val scale = side / DETECTOR_SIZE.toFloat()
        for (i in scores.indices) {
            val score = 1f / (1f + exp(-scores[i].coerceIn(-100f, 100f)))
            if (score < MIN_SCORE) continue
            val o = i * 16
            val cx = boxes[o] / DETECTOR_SIZE + ANCHORS[i * 2]
            val cy = boxes[o + 1] / DETECTOR_SIZE + ANCHORS[i * 2 + 1]
            val bw = boxes[o + 2] / DETECTOR_SIZE
            val bh = boxes[o + 3] / DETECTOR_SIZE
            if (bw <= 0f || bh <= 0f) continue
            result += FaceBox(left + (cx - bw / 2) * DETECTOR_SIZE * scale, top + (cy - bh / 2) * DETECTOR_SIZE * scale, bw * DETECTOR_SIZE * scale, bh * DETECTOR_SIZE * scale, score)
        }
        return result
    }

    /** Non-maximum suppression berbobot seperti MediaPipe: kotak yang tumpang dirata-rata menurut keyakinannya. */
    private fun suppress(boxes: List<FaceBox>): List<FaceBox> {
        val remaining = boxes.sortedByDescending { it.score }.toMutableList()
        val result = ArrayList<FaceBox>()
        while (remaining.isNotEmpty()) {
            val best = remaining[0]
            val group = remaining.filter { it.iou(best) > NMS_IOU }
            remaining.removeAll(group)
            val total = group.sumOf { it.score.toDouble() }.toFloat()
            fun avg(value: (FaceBox) -> Float) = group.sumOf { (value(it) * it.score).toDouble() }.toFloat() / total
            result += FaceBox(avg { it.x }, avg { it.y }, avg { it.width }, avg { it.height }, best.score)
        }
        return result
    }

    /**
     * Bukaan mulut wajah [face]: jarak bibir atas–bawah dibagi lebar mulut (0 = tertutup). Wajah dipotong persegi 1,5×
     * kotaknya seperti MediaPipe, lalu landmark dibaca.
     */
    fun mouthOpening(frame: Bitmap, face: FaceBox): Double? {
        val model = landmarks ?: model("models/face_landmarks_detector.tflite").also { landmarks = it }
        val side = max(face.width, face.height) * 1.5f
        val input = pixels(crop(frame, face.centerX - side / 2, face.centerY - side / 2, side, LANDMARK_SIZE), signed = false)
        val points = model.run(input).firstOrNull { it.size >= 468 * 3 } ?: return null
        fun point(i: Int) = points[i * 3] to points[i * 3 + 1]
        fun distance(a: Int, b: Int): Double {
            val (ax, ay) = point(a)
            val (bx, by) = point(b)
            return hypot((ax - bx).toDouble(), (ay - by).toDouble())
        }
        val width = distance(61, 291)
        return if (width > 1.0) distance(13, 14) / width else null
    }

    private fun crop(frame: Bitmap, left: Float, top: Float, side: Float, size: Int): Bitmap {
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            postTranslate(-left, -top)
            postScale(size / side, size / side)
        }
        Canvas(target).drawBitmap(frame, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }

    /** RGB berurutan (HWC) dalam −1…1 ([signed], BlazeFace) atau 0…1 (landmark). */
    private fun pixels(bitmap: Bitmap, signed: Boolean): FloatArray {
        val size = bitmap.width * bitmap.height
        val argb = IntArray(size)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val out = FloatArray(size * 3)
        for (i in 0 until size) {
            val p = argb[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (signed) {
                out[i * 3] = r / 127.5f - 1f
                out[i * 3 + 1] = g / 127.5f - 1f
                out[i * 3 + 2] = b / 127.5f - 1f
            } else {
                out[i * 3] = r / 255f
                out[i * 3 + 1] = g / 255f
                out[i * 3 + 2] = b / 255f
            }
        }
        return out
    }

    override fun close() {
        detector?.close()
        landmarks?.close()
        detector = null
        landmarks = null
    }

    companion object {
        const val DETECTOR_SIZE = 128
        const val LANDMARK_SIZE = 256
        const val MIN_SCORE = 0.55f
        const val NMS_IOU = 0.3f

        /**
         * Anchor SSD BlazeFace jarak dekat (x, y berurutan, 896 anchor): stride 8 → grid 16×16 × 2 anchor, stride 16 →
         * grid 8×8 × 6 anchor; ukuran tetap, titik tengah (i + 0,5) / grid.
         */
        val ANCHORS: FloatArray = buildList {
            for ((grid, count) in listOf(16 to 2, 8 to 6)) {
                for (y in 0 until grid) for (x in 0 until grid) repeat(count) {
                    add((x + 0.5f) / grid)
                    add((y + 0.5f) / grid)
                }
            }
        }.toFloatArray()
    }
}
