package id.clipmi.ios

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionPreview
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.FrameStyle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

actual fun captionFontFamily(fontsDir: String, family: String): FontFamily = runCatching {
    val bytes = readBytes(IosUserFonts.file(fontsDir, family)) ?: error("font")
    FontFamily(Font("caption-$family", bytes))
}.getOrDefault(FontFamily.Default)

@Composable
actual fun rememberSampleFrame(repo: Repository): String? {
    val sample by produceState<String?>(null) {
        value = withContext(Dispatchers.Default) {
            val projects = repo.db.projects().all().sortedByDescending { it.updatedAt }
            val candidates = projects.flatMap { p ->
                listOfNotNull(p.thumbnailPath?.let { repo.absolute(it) }, repo.absolute("${repo.projectDir(p.id)}/thumb.jpg"), repo.absolute("${repo.projectDir(p.id)}/editor-thumb.jpg"))
            }
            candidates.firstOrNull { readable(it) } ?: drawSample(repo.absolute("tmp/style-sample.jpg"))
        }
    }
    return sample
}

/** FFmpeg di iPhone hanya membaca JPEG/PNG (thumbnail YouTube dari cadangan Android sering WebP). */
private fun readable(path: String): Boolean {
    if (fileSize(path) <= 0) return false
    val head = readBytes(path)?.take(4) ?: return false
    val jpeg = head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
    val png = head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
    return jpeg || png
}

/** Gradasi 16:9 dengan siluet sederhana (sama dengan Android), supaya crop dan blur tetap terlihat. */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun drawSample(path: String): String? {
    if (fileSize(path) > 0) return path
    return runCatching {
        val surface = Surface.makeRasterN32Premul(1280, 720)
        val canvas = surface.canvas
        canvas.drawRect(Rect.makeWH(1280f, 720f), Paint().apply { shader = Shader.makeLinearGradient(0f, 0f, 1280f, 720f, intArrayOf(0xFF1F3B57.toInt(), 0xFF0E7C66.toInt())) })
        canvas.drawCircle(640f, 300f, 110f, Paint().apply { color = 0xFFE7D8C9.toInt(); isAntiAlias = true })
        canvas.drawRRect(org.jetbrains.skia.RRect.makeLTRB(470f, 430f, 810f, 900f, 120f), Paint().apply { color = 0xFF2B2B2B.toInt(); isAntiAlias = true })
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)?.bytes ?: return null
        createDirectory(path.substringBeforeLast('/'))
        bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile(path, atomically = true) }
        path.takeIf { fileSize(it) > 0 }
    }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CaptionStylePreview(renderer: IosRenderer, style: CaptionStyle, sample: String?, modifier: Modifier) {
    val engine = renderer.engine
    val preview = remember(style) { CaptionPreview.build(AssWriter(IosFontMeasurer(engine, renderer.fontsDir)), style) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    BoxWithConstraints(modifier.aspectRatio(preview.width.toFloat() / preview.height).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }.roundToInt().coerceIn(2, 720)
        val height = (width.toFloat() * preview.height / preview.width).roundToInt().coerceAtLeast(2)
        sample?.let { SampleBackground(it, Modifier.fillMaxSize()) }
        LaunchedEffect(preview, width) {
            val handle = engine.assCreate(width, height)
            engine.assLoad(handle, preview.ass)
            val pixels = ByteArray(width * height * 4)
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            var started = -1L
            try {
                while (true) {
                    val now = withFrameNanos { it }
                    if (started < 0) started = now
                    val ms = ((now - started) / 1_000_000) % (preview.duration * 1000).toLong().coerceAtLeast(1)
                    pixels.usePinned { pinned -> engine.assRender(handle, ms, pinned.addressOf(0).toLong(), width, height, true) }
                    image = SkiaImage.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
                    delay(16)
                }
            } finally {
                engine.assDestroy(handle)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            image?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        }
    }
}

/** Contoh frame dipotong memenuhi kotak dan digelapkan sedikit (posisi subtitle tetap terlihat jelas). */
@Composable
private fun SampleBackground(path: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.Default) { readBytes(path)?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    }
    bitmap?.let {
        Box(modifier) {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        }
    }
}

@Composable
actual fun FrameStylePreview(media: StyleMedia, frame: FrameStyle, caption: CaptionStyle, sample: String?, modifier: Modifier) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(frame, caption, sample) {
        val source = sample ?: return@LaunchedEffect
        delay(300)
        busy = true
        val result = runCatching {
            val output = media.framePreview(frame, caption, source)
            withContext(Dispatchers.Default) { readBytes(output)?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() } }
        }
        busy = false
        result.getOrNull()?.let { image = it; error = null } ?: run { error = "Pratinjau gagal: ${result.exceptionOrNull()?.message?.take(120).orEmpty()}" }
    }

    Box(
        modifier.aspectRatio(frame.canvasWidth().toFloat() / frame.canvasHeight()).clip(RoundedCornerShape(18.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = "Pratinjau bingkai", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp), color = Color.White, strokeWidth = 2.dp)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(16.dp)) }
    }
}
