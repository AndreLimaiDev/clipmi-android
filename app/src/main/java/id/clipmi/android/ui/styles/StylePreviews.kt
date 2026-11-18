package id.clipmi.android.ui.styles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.domain.FontMeasurer
import id.clipmi.android.nativebridge.AssRenderer
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionPreview
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.FrameStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Contoh frame video untuk pratinjau gaya: gambar kecil project terbaru, atau gradasi buatan bila belum ada project.
 */
@Composable
fun rememberSampleFrame(container: AppContainer): File? {
    val context = LocalContext.current
    val sample by produceState<File?>(null) {
        value = withContext(Dispatchers.IO) {
            container.database.projects().recentThumbnails().firstNotNullOfOrNull { path ->
                container.workspace.file(path)?.takeIf { it.isFile }?.let { readableSample(it, context.cacheDir) }
            } ?: File(context.cacheDir, "style-sample.jpg").also { if (!it.isFile) drawSample(it) }
        }
    }
    return sample
}

/**
 * FFmpeg di HP hanya membaca JPEG/PNG (tanpa dekoder WebP), sedangkan thumbnail YouTube sering WebP:
 * selain JPEG/PNG, thumbnail diubah dulu ke JPEG di cache.
 */
private fun readableSample(file: File, cacheDir: File): File? {
    val head = ByteArray(4)
    val read = file.inputStream().use { it.read(head) }
    val jpeg = read >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
    val png = read >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
    if (jpeg || png) return file
    val out = File(cacheDir, "style-sample-${(file.path + file.lastModified()).hashCode().toUInt()}.jpg")
    if (out.isFile) return out
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
    return out
}

/** Gradasi 16:9 dengan siluet sederhana, supaya crop dan blur tetap terlihat. */
private fun drawSample(file: File) {
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRect(0f, 0f, 1280f, 720f, Paint().apply { shader = LinearGradient(0f, 0f, 1280f, 720f, 0xFF1F3B57.toInt(), 0xFF0E7C66.toInt(), Shader.TileMode.CLAMP) })
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7D8C9.toInt() }
    canvas.drawCircle(640f, 300f, 110f, body)
    canvas.drawRoundRect(470f, 430f, 810f, 900f, 120f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2B2B.toInt() })
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
}

/**
 * Pratinjau gaya subtitle: kalimat contoh dari AssWriter yang sama dengan render, digambar libass dan diputar berulang
 * di atas contoh frame (padanan caption-preview.js desktop).
 */
@Composable
fun CaptionStylePreview(container: AppContainer, style: CaptionStyle, sample: File?, modifier: Modifier = Modifier) {
    val fonts = remember { container.renderer.fontsDir() }
    val preview = remember(style) { CaptionPreview.build(AssWriter(FontMeasurer(fonts)), style) }
    var image by remember { mutableStateOf<Bitmap?>(null) }

    BoxWithConstraints(
        modifier.aspectRatio(preview.width.toFloat() / preview.height).clip(RoundedCornerShape(18.dp)).background(Color.Black),
    ) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }.roundToInt().coerceIn(2, 720)
        val height = (width.toFloat() * preview.height / preview.width).roundToInt()
        sample?.let { SampleBackground(it, Modifier.fillMaxSize()) }
        LaunchedEffect(preview, width) {
            val renderer = AssRenderer(width, height, fonts, null).apply { load(preview.ass) }
            val buffers = arrayOf(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
            var next = 0
            val started = System.nanoTime()
            try {
                while (true) {
                    awaitFrame()
                    val ms = ((System.nanoTime() - started) / 1_000_000) % (preview.duration * 1000).toLong()
                    val target = buffers[next]
                    withContext(Dispatchers.Default) { renderer.render(ms, target, clear = true, force = true) }
                    image = target
                    next = 1 - next
                    delay(16)
                }
            } finally {
                renderer.close()
            }
        }
        image?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

/** Contoh frame dipotong memenuhi kotak dan digelapkan sedikit (posisi subtitle tetap terlihat jelas). */
@Composable
private fun SampleBackground(file: File, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    }
    bitmap?.let {
        Box(modifier) {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        }
    }
}

/**
 * Pratinjau gaya bingkai: satu frame dirender FFmpeg dengan filtergraph render yang sama (lihat Renderer.framePreview),
 * dihitung ulang sesaat setelah isian berhenti berubah.
 */
@Composable
fun FrameStylePreview(container: AppContainer, frame: FrameStyle, caption: CaptionStyle, sample: File?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(frame, caption, sample) {
        val source = sample ?: return@LaunchedEffect
        delay(300)
        busy = true
        val output = File(context.cacheDir, "frame-preview.jpg")
        val result = runCatching {
            container.renderer.framePreview(frame, caption, source, output)
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(output.path) }
        }
        busy = false
        result.getOrNull()?.let { image = it; error = null } ?: run { error = "Pratinjau gagal: ${result.exceptionOrNull()?.message?.take(120)}" }
    }

    Box(
        modifier.aspectRatio(frame.canvasWidth().toFloat() / frame.canvasHeight()).clip(RoundedCornerShape(18.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it.asImageBitmap(), contentDescription = "Pratinjau bingkai", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp), color = Color.White, strokeWidth = 2.dp)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(16.dp)) }
    }
}
