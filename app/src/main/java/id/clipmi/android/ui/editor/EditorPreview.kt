package id.clipmi.android.ui.editor

import kotlin.math.abs
import androidx.compose.runtime.DisposableEffect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import id.clipmi.android.domain.MediaInfo
import id.clipmi.android.nativebridge.AssRenderer
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.Composition
import id.clipmi.core.render.SpeakerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Gerakan di atas pratinjau sejak jari menyentuh: total geser [pan] (piksel layar), total cubit [zoom], titik tengah
 * sentuhan, ukuran pratinjau, dan [overflow]: bagian video di luar area (pecahan lebar/tinggi pratinjau, 0 = tidak ada).
 */
fun interface PreviewGesture {
    fun onGesture(pan: Offset, zoom: Float, centroid: Offset, size: androidx.compose.ui.unit.IntSize, overflow: Offset)
}

/**
 * Pratinjau klip sebesar layar hasil: latar (blur/warna/gradasi), video yang ditata sesuai [Composition] (crop, zoom,
 * posisi, area video), lalu subtitle dan hook yang digambar libass dari ASS yang sama dengan render.
 *
 * @param hookTime bila tidak null, hook digambar pada waktu ini (detik sejak awal pembuka) alih-alih mengikuti video.
 */
@Composable
fun EditorPreview(
    state: EditorState,
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    hookTime: Double? = null,
    gesture: PreviewGesture? = null,
    onGestureEnd: () -> Unit = {},
) {
    val sourceSize by produceState<Pair<Int, Int>?>(null, state.source) { value = withContext(Dispatchers.IO) { MediaInfo.videoSize(state.source) } }
    val composition = remember(state.snapshot, state.captionStyles, state.frameStyles, state.frameDraft, state.speakerBase, sourceSize) {
        val spec = state.spec()
        Composition.make(state.frameStyle(), sourceSize, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), spec.speakerKeyframes)
    }
    val canvas = composition.canvas
    val currentOverflow = rememberUpdatedState(overflow(composition, sourceSize))

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Pratinjau sebesar mungkin di ruang yang ada, dengan rasio layar hasil.
        val ratio = canvas.width.toFloat() / canvas.height
        val boxWidth = minOf(maxWidth.value, maxHeight.value * ratio)
        val boxHeight = boxWidth / ratio
        val widthPx = with(density) { boxWidth.dp.toPx() }
        val scale = widthPx / canvas.width

        Box(
            Modifier
                .size(boxWidth.dp, boxHeight.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .then(
                    if (gesture != null) Modifier.pointerInput(gesture) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pan = Offset.Zero
                            var zoom = 1f
                            do {
                                val event = awaitPointerEvent()
                                pan += event.calculatePan()
                                zoom *= event.calculateZoom()
                                // Saat jari diangkat titik tengahnya kosong (NaN): abaikan.
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid.isSpecified && event.changes.any { it.pressed }) gesture.onGesture(pan, zoom, centroid, size, currentOverflow.value)
                                event.changes.forEach { change: PointerInputChange -> if (change.positionChanged()) change.consume() }
                            } while (event.changes.any { it.pressed })
                            onGestureEnd()
                        }
                    } else Modifier,
                ),
        ) {
            Background(state, composition, Modifier.fillMaxSize())
            VideoLayer(player, composition, sourceSize, scale, state.snapshot.clip.startTime)
            LogoLayer(state, composition, scale)
            AssOverlay(state, player, composition, hookTime, Modifier.fillMaxSize())
        }
    }
}

/** Latar layar: tanpa latar untuk crop penuh; blur dari thumbnail (diperkecil lalu diperbesar), warna, atau gradasi. */
@Composable
private fun Background(state: EditorState, composition: Composition, modifier: Modifier) {
    val background = composition.background ?: return
    val color = parse(background.color) ?: Color.Black
    when (background.kind) {
        "blur" -> {
            val blurred by produceState<Bitmap?>(null, state.project.thumbnailPath) {
                value = withContext(Dispatchers.IO) {
                    val bitmap = state.thumbnail?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } ?: return@withContext null
                    // Seperempat lalu sangat kecil: kesan blur yang mirip boxblur render, tanpa RenderEffect (Android 12+).
                    Bitmap.createScaledBitmap(bitmap, 32, maxOf(1, 32 * bitmap.height / maxOf(1, bitmap.width)), true)
                }
            }
            Box(modifier.background(color)) {
                blurred?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.95f) }
            }
        }
        "gradient" -> Box(modifier.background(Brush.verticalGradient(listOf(color, parse(background.color2) ?: Color.Black))))
        else -> Box(modifier.background(color))
    }
}

/**
 * Video di area [Composition.Video.rect]: "cover" = menutupi area lalu digeser sesuai posisi crop (seperti filter
 * crop FFmpeg), "contain" = utuh di dalam area. Zoom memperbesar dari posisi yang sama.
 */
@Composable
private fun VideoLayer(player: ExoPlayer, composition: Composition, sourceSize: Pair<Int, Int>?, scale: Float, clipStart: Double) {
    val (sw, sh) = sourceSize ?: return
    val video = composition.video
    val rect = video.rect ?: return
    val density = LocalDensity.current
    val areaW = rect.width.toFloat()
    val areaH = rect.height.toFloat()
    val zoom = video.zoom.toFloat()
    val fit = if (video.fill == "cover") maxOf(areaW / sw, areaH / sh) else minOf(areaW / sw, areaH / sh)
    val shown = fit * zoom
    val dw = sw * shown
    val dh = sh * shown
    // Sisa video di luar area dibagi sesuai posisi crop (0 = kiri/atas, 100 = kanan/bawah).
    val overflowX = maxOf(0f, dw - areaW)
    val overflowY = maxOf(0f, dh - areaH)
    val fixedLeft = rect.x + (if (dw > areaW) -overflowX * video.cropX / 100f else (areaW - dw) / 2)
    // Ikuti pembicara: tengah crop mengikuti jalur pada waktu video saat ini (seperti ekspresi x filter crop render).
    val keyframes = video.keyframes
    var center by remember { mutableFloatStateOf(-1f) }
    if (keyframes != null) {
        // Waktu gambar yang sedang tampil (bukan jam pemutar), supaya crop pindah di frame yang sama dengan render.
        val clock = remember(player) { ShownFrameClock(player) }
        DisposableEffect(clock) {
            clock.attach()
            onDispose { clock.detach() }
        }
        LaunchedEffect(keyframes, clipStart) {
            while (true) {
                awaitFrame()
                center = SpeakerTrack.positionAt(keyframes, clock.seconds() - clipStart).toFloat()
            }
        }
    }
    fun left(): Float =
        if (keyframes != null && dw > areaW) {
            val c = if (center >= 0f) center else SpeakerTrack.positionAt(keyframes, player.currentPosition / 1000.0 - clipStart).toFloat()
            rect.x - (dw * c - areaW / 2).coerceIn(0f, overflowX)
        } else fixedLeft
    val top = rect.y + (if (dh > areaH) -overflowY * video.cropY / 100f else (areaH - dh) / 2)
    val radius = video.radius * scale

    Box(
        Modifier
            .offset { IntOffset((rect.x * scale).roundToInt(), (rect.y * scale).roundToInt()) }
            .requiredSize(with(density) { (areaW * scale).toDp() }, with(density) { (areaH * scale).toDp() })
            .clip(RoundedCornerShape(with(density) { radius.toDp() })),
    ) {
        AndroidView(
            factory = { TextureView(it).also(player::setVideoTextureView) },
            onRelease = { player.clearVideoTextureView(it) },
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset(((left() - rect.x) * scale).roundToInt(), ((top - rect.y) * scale).roundToInt()) }
                .requiredSize(with(density) { (dw * scale).toDp() }, with(density) { (dh * scale).toDp() }),
        )
    }
}

/**
 * Subtitle dan hook dari ASS pratinjau, digambar libass ke bitmap seukuran pratinjau setiap frame. Waktu subtitle =
 * waktu video hasil (bagian yang dibuang dilewati); waktu hook = sejak awal pembuka.
 */
@Composable
private fun AssOverlay(state: EditorState, player: ExoPlayer, composition: Composition, hookTime: Double?, modifier: Modifier) {
    val tracks = state.tracks ?: return
    var frame by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Bitmap?>(null) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.roundToInt().coerceIn(2, 1080)
        val height = (width.toFloat() * composition.canvas.height / composition.canvas.width).roundToInt()

        LaunchedEffect(tracks, width, hookTime) {
            val fonts = state.fontsDir()
            val subs = AssRenderer(width, height, fonts, null).apply { load(tracks.subs) }
            val hook = tracks.hook?.let { AssRenderer(width, height, fonts, null).apply { load(it) } }
            val buffers = arrayOf(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
            var next = 0
            val clip = state.snapshot.clip
            val cuts = state.spec().cutRanges()
            val lead = state.spec().leadSeconds()
            try {
                while (true) {
                    awaitFrame()
                    val source = player.currentPosition / 1000.0
                    val t = ClipCuts.map(cuts, clip.startTime, source.coerceIn(clip.startTime, clip.endTime))
                    val target = buffers[next]
                    withContext(Dispatchers.Default) {
                        subs.render((t * 1000).toLong(), target, clear = true, force = true)
                        hook?.render((((hookTime ?: (t + lead))) * 1000).toLong(), target, clear = false, force = true)
                    }
                    image = target
                    next = 1 - next
                    frame++
                }
            } finally {
                subs.close()
                hook?.close()
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            frame
            image?.let { drawImage(it.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        }
    }
}

private fun parse(hex: String?): Color? =
    hex?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/** Bagian video yang tidak muat di areanya, sebagai pecahan lebar/tinggi layar hasil (dasar geser crop). */
private fun overflow(composition: Composition, sourceSize: Pair<Int, Int>?): Offset {
    val (sw, sh) = sourceSize ?: return Offset.Zero
    val rect = composition.video.rect ?: return Offset.Zero
    val video = composition.video
    val areaW = rect.width.toFloat()
    val areaH = rect.height.toFloat()
    val fit = if (video.fill == "cover") maxOf(areaW / sw, areaH / sh) else minOf(areaW / sw, areaH / sh)
    val shown = fit * video.zoom.toFloat()
    return Offset(
        maxOf(0f, sw * shown - areaW) / composition.canvas.width,
        maxOf(0f, sh * shown - areaH) / composition.canvas.height,
    )
}

/** Logo gaya bingkai di posisi yang sama dengan render (overlay FFmpeg), supaya bisa dilihat dan digeser di editor. */
@Composable
private fun LogoLayer(state: EditorState, composition: Composition, scale: Float) {
    val path = (state.frameDraft ?: state.frameEntity())?.logoPath ?: return
    val file = state.container.workspace.file(path)?.takeIf { it.isFile } ?: return
    val bitmap by produceState<Bitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) } }
    val image = bitmap ?: return
    val density = LocalDensity.current
    val (left, top, height) = logoRect(composition, image.width, image.height)
    val logo = composition.logo
    Image(
        image.asImageBitmap(), contentDescription = null, alpha = logo.opacity.toFloat(),
        modifier = Modifier
            .offset { IntOffset((left * scale).roundToInt(), (top * scale).roundToInt()) }
            .requiredSize(with(density) { (logo.width * scale).toDp() }, with(density) { (height * scale).toDp() }),
    )
}

/** Kiri, atas, dan tinggi logo di layar hasil (sama dengan `anchorExpression` render). */
internal fun logoRect(composition: Composition, imageWidth: Int, imageHeight: Int): Triple<Float, Float, Float> {
    val logo = composition.logo
    val area = composition.anchorArea
    val canvas = composition.canvas
    val height = logo.width.toFloat() * imageHeight / maxOf(1, imageWidth)
    val left = area.left + (canvas.width - logo.width - area.left - area.right) * logo.x.toFloat()
    val top = area.top + (canvas.height - height - area.top - area.bottom) * logo.y.toFloat()
    return Triple(left, top, height)
}

/**
 * Waktu video (detik) dari gambar yang sedang tampil di layar: dari [VideoFrameMetadataListener] (waktu presentasi
 * dan waktu tampil tiap frame), bukan dari jam pemutar yang bisa mendahului gambar 1–2 frame. Bila data frame tidak
 * cocok dengan jam pemutar (selisih > 1 detik, misalnya ada offset internal), jam pemutar yang dipakai.
 */
private class ShownFrameClock(private val player: ExoPlayer) {
    private val frames = ArrayDeque<Pair<Long, Long>>()
    private val listener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
        synchronized(frames) {
            frames.addLast(releaseTimeNs to presentationTimeUs)
            while (frames.size > 12) frames.removeFirst()
        }
    }

    fun attach() = player.setVideoFrameMetadataListener(listener)

    fun detach() = player.clearVideoFrameMetadataListener(listener)

    fun seconds(): Double {
        val fallback = player.currentPosition / 1000.0
        // TextureView menampilkan frame pada gambar layar berikutnya setelah dirilis: mundur satu vsync.
        val now = System.nanoTime() - 16_000_000L
        val shown = synchronized(frames) { frames.lastOrNull { it.first <= now } ?: frames.firstOrNull() } ?: return fallback
        var media = shown.second / 1_000_000.0
        if (abs(media - fallback) > 1.0 && media > OFFSET_US / 1_000_000.0) media -= OFFSET_US / 1_000_000.0
        return if (abs(media - fallback) <= 1.0) media else fallback
    }

    private companion object {
        /** Offset waktu renderer ExoPlayer (1e12 µs) yang mungkin ikut di waktu presentasi frame. */
        const val OFFSET_US = 1_000_000_000_000L
    }
}
