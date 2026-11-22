package id.clipmi.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.Dialog
import id.clipmi.android.data.RenderStatus
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipEditor
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.Composition
import id.clipmi.core.render.CoverTitle
import id.clipmi.core.render.CoverTitleLayout
import id.clipmi.core.render.EditorWord
import id.clipmi.core.render.HookLayout
import id.clipmi.core.render.Pause
import id.clipmi.core.render.Range
import id.clipmi.core.render.SpeakerTrack
import id.clipmi.core.transcript.Timecode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

// Pratinjau editor iOS: pemutar AVPlayer, video sesuai Composition, subtitle/hook libass, gestur crop dan hook.

internal val Danger = Color(0xFFE5484D)
internal val Warn = Color(0xFFF5A524)

/** Pemutar AVPlayer untuk pratinjau: posisi video (detik sumber) dibaca setiap frame. */
@OptIn(ExperimentalForeignApi::class)
internal class PreviewPlayer(path: String) {
    val player = AVPlayer(uRL = NSURL.fileURLWithPath(path))
    var playing by mutableStateOf(false)
        private set

    /** Tujuan lompatan yang belum sampai (AVPlayer mencari frame secara asinkron) dan kapan diminta. */
    private var target: Double? = null
    private var targetAt = 0L

    fun seconds(): Double = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() } ?: 0.0

    fun seek(seconds: Double) {
        target = seconds
        targetAt = nowMillis()
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600), CMTimeMake(0, 1), CMTimeMake(0, 1))
    }

    /**
     * True selama lompatan terakhir belum sampai (paling lama 1,5 detik). Selama itu posisi lama jangan dipakai untuk
     * melompat lagi: lompatan yang diulang setiap frame saling membatalkan dan video macet.
     */
    fun settling(now: Double): Boolean {
        val to = target ?: return false
        if (abs(now - to) < 0.15 || nowMillis() - targetAt > 1500) {
            target = null
            return false
        }
        return true
    }

    fun play() {
        player.play()
        playing = true
    }

    fun pause() {
        player.pause()
        playing = false
    }

    fun release() {
        player.pause()
    }

    val isPlaying: Boolean get() = player.rate > 0f
}

/**
 * Isian teks yang belum tersimpan (judul, hook, teks sampul): tersimpan sendiri 0,8 detik setelah berhenti mengetik,
 * atau langsung saat Render ditekan.
 */
internal class PendingText {
    private val commits = LinkedHashMap<String, () -> Unit>()

    fun set(key: String, commit: (() -> Unit)?) {
        if (commit == null) commits.remove(key) else commits[key] = commit
    }

    fun commit(key: String) {
        commits.remove(key)?.invoke()
    }

    fun commitAll() {
        val all = commits.values.toList()
        commits.clear()
        all.forEach { it() }
    }
}

/** Waktu di video sumber untuk detik ke-[output] di video hasil (bagian yang dibuang dilewati). */
internal fun sourceTime(cuts: List<Range>, start: Double, end: Double, output: Double): Double {
    var left = output
    for ((from, to) in ClipCuts.kept(cuts, start, end)) {
        val length = to - from
        if (left <= length) return from + left
        left -= length
    }
    return end
}

internal fun parse(hex: String?): Color? =
    hex?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

internal fun decodeImage(path: String?): ImageBitmap? =
    path?.let { readBytes(it) }?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }

internal enum class PreviewMode { None, Crop, Hook }

/**
 * Pratinjau sebesar layar hasil: latar, video sesuai [Composition], lalu subtitle dan hook dari libass. Di tab Crop,
 * geser dan cubit mengatur posisi dan zoom video; di tab Hook, hook selalu terlihat (saat video berhenti) dan bisa
 * diseret ke posisi baru (titik tengahnya, persen layar).
 */
@Composable
internal fun Preview(state: EditorState, composition: Composition, preview: PreviewPlayer, mode: PreviewMode) {
    val layout = if (mode == PreviewMode.Hook) state.hookLayout() else null
    val lead = if (mode == PreviewMode.Hook) state.spec().leadSeconds() else 0.0
    val hookTime = if (layout != null && !preview.playing) layout.start + lead + minOf(0.6, (layout.end - layout.start) / 2) else null
    val gesture = when (mode) {
        PreviewMode.Hook -> Modifier.pointerInput(mode) {
            fun move(at: Offset) {
                val x = (at.x / size.width * 100).roundToInt().coerceIn(0, 100)
                val y = (at.y / size.height * 100).roundToInt().coerceIn(0, 100)
                state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))))) }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                move(down.position)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.pressed }
                    if (change != null) {
                        move(change.position)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
                state.endDrag()
            }
        }
        PreviewMode.Crop -> Modifier.pointerInput(mode) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var pan = Offset.Zero
                var zoom = 1f
                do {
                    val event = awaitPointerEvent()
                    pan += event.calculatePan()
                    zoom *= event.calculateZoom()
                    if (event.changes.any { it.pressed }) {
                        crop(state, pan.x / size.width, pan.y / size.height, zoom, overflow(state.composition, state.sourceSize), preview.seconds())
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
                state.endDrag()
            }
        }
        PreviewMode.None -> Modifier
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(gesture)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = widthPx / composition.canvas.width
        Background(state, composition)
        VideoLayer(composition, state.sourceSize, scale, preview, state.snapshot.clip.startTime)
        AssOverlay(state, composition, preview, hookTime)
    }
}

/** Bagian video yang tidak muat di areanya, sebagai pecahan lebar/tinggi layar hasil (dasar geser crop). */
internal fun overflow(composition: Composition, sourceSize: Pair<Int, Int>?): Offset {
    val (sw, sh) = sourceSize ?: return Offset.Zero
    val rect = composition.video.rect ?: return Offset.Zero
    val video = composition.video
    val areaW = rect.width.toFloat()
    val areaH = rect.height.toFloat()
    val fit = if (video.fill == "cover") max(areaW / sw, areaH / sh) else min(areaW / sw, areaH / sh)
    val shown = fit * video.zoom.toFloat()
    return Offset(
        max(0f, sw * shown - areaW) / composition.canvas.width,
        max(0f, sh * shown - areaH) / composition.canvas.height,
    )
}

/**
 * Geser dan cubit di tab Crop (sama dengan Android): posisi 0–100 dan zoom 100–250%. Saat mengikuti pembicara, geser
 * kiri–kanan mengatur posisi manual adegan yang sedang tampil (adegan lain tetap otomatis).
 */
internal fun crop(state: EditorState, dx: Float, dy: Float, zoom: Float, overflow: Offset, position: Double) {
    state.drag { _ ->
        // Dihitung dari keadaan awal gerakan, jadi geser pelan pun terhitung (tidak hilang oleh pembulatan).
        val base = state.gestureBase
        val spec = state.spec(base)
        val baseZoom = base.cropZoom ?: (spec.frame.zoomFactor() * 100).roundToInt()
        fun move(start: Int, delta: Float, rest: Float) = (start - delta / rest * 100).roundToInt().coerceIn(0, 100)
        val track = if (state.followsSpeaker(base)) state.speakerTrack(base) else null
        val time = position - base.clip.startTime
        val shot = track?.shotAt(time)
        var manual = base.speakerManual
        if (track != null && shot != null && dx != 0f && overflow.x > 0f) {
            val composition = state.composition
            val area = (composition.video.rect?.width ?: composition.canvas.width).toFloat() / composition.canvas.width
            val videoWidth = overflow.x + area
            val half = area / 2 / videoWidth
            val start = shot.manual ?: track.keyframes.takeIf { it.isNotEmpty() }?.let { SpeakerTrack.positionAt(it, time) } ?: (spec.effectiveCropPosition() / 100.0)
            val visible = start.coerceIn(half.toDouble(), 1.0 - half)
            manual = track.withManual(shot, (visible - dx / videoWidth).coerceIn(half.toDouble(), 1.0 - half)).manual
        }
        base.copy(
            cropPosition = if (shot == null && dx != 0f && overflow.x > 0f) move(spec.effectiveCropPosition(), dx, overflow.x) else base.cropPosition,
            cropY = if (dy != 0f && overflow.y > 0f) move(spec.effectiveCropY(), dy, overflow.y) else base.cropY,
            cropZoom = if (zoom != 1f) (baseZoom * zoom).roundToInt().coerceIn(ClipSpec.ZOOM_RANGE.first, ClipSpec.ZOOM_RANGE.last) else base.cropZoom,
            speakerManual = manual,
        )
    }
}

/** Latar: blur dari frame video (diperbesar dan dikaburkan), warna, atau gradasi; crop penuh tanpa latar. */
@Composable
internal fun Background(state: EditorState, composition: Composition) {
    val background = composition.background
    val color = parse(background?.color) ?: Color.Black
    when (background?.kind) {
        "blur" -> {
            val thumbnail = remember(state.thumbnail) { decodeImage(state.thumbnail) }
            Box(Modifier.fillMaxSize().background(color)) {
                thumbnail?.let {
                    Image(
                        it, null, contentScale = ContentScale.Crop, alpha = 0.95f,
                        modifier = Modifier.fillMaxSize().blur(18.dp, BlurredEdgeTreatment.Rectangle),
                    )
                }
            }
        }
        "gradient" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color, parse(background.color2) ?: Color.Black))))
        else -> Box(Modifier.fillMaxSize().background(color))
    }
}

/**
 * Video di area [Composition.Video.rect]: "cover" = menutupi area lalu digeser sesuai posisi crop (seperti filter crop
 * FFmpeg), "contain" = utuh di dalam area; zoom memperbesar dari posisi yang sama (sama dengan VideoLayer Android).
 * View UIKit tidak menerima sentuhan, supaya seret di pratinjau (posisi hook) sampai ke Compose.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun VideoLayer(composition: Composition, sourceSize: Pair<Int, Int>?, scale: Float, preview: PreviewPlayer, clipStart: Double) {
    val (sw, sh) = sourceSize ?: return
    val video = composition.video
    val rect = video.rect ?: return
    val density = LocalDensity.current
    val areaW = rect.width.toFloat()
    val areaH = rect.height.toFloat()
    val zoom = video.zoom.toFloat()
    val fit = if (video.fill == "cover") max(areaW / sw, areaH / sh) else min(areaW / sw, areaH / sh)
    val shown = fit * zoom
    val dw = sw * shown
    val dh = sh * shown
    val overflowX = max(0f, dw - areaW)
    val overflowY = max(0f, dh - areaH)
    val fixedLeft = if (dw > areaW) -overflowX * video.cropX / 100f else (areaW - dw) / 2
    val top = if (dh > areaH) -overflowY * video.cropY / 100f else (areaH - dh) / 2
    // Ikuti pembicara: tengah crop mengikuti jalur pada waktu video saat ini (seperti ekspresi x filter crop render).
    val keyframes = video.keyframes
    fun left(): Float =
        if (keyframes != null && dw > areaW) -(dw * SpeakerTrack.positionAt(keyframes, preview.seconds() - clipStart).toFloat() - areaW / 2).coerceIn(0f, overflowX)
        else fixedLeft
    // Satuan UIKit = dp Compose (titik layar).
    val toPoints = { px: Float -> (px * scale / density.density).toDouble() }
    val layer = remember(preview) { AVPlayerLayer.playerLayerWithPlayer(preview.player).apply { videoGravity = AVLayerVideoGravityResize } }
    fun place() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.frame = CGRectMake(toPoints(left()), toPoints(top), toPoints(dw), toPoints(dh))
        CATransaction.commit()
    }
    if (keyframes != null) {
        LaunchedEffect(keyframes, clipStart, dw, dh, top) {
            while (true) {
                withFrameNanos { }
                place()
            }
        }
    }

    Box(
        Modifier
            .offset { IntOffset((rect.x * scale).roundToInt(), (rect.y * scale).roundToInt()) }
            .requiredSize(with(density) { (areaW * scale).toDp() }, with(density) { (areaH * scale).toDp() })
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .clip(RoundedCornerShape(with(density) { (video.radius * scale).toDp() })),
    ) {
        UIKitView(
            factory = {
                UIView().apply {
                    // Clip Compose tidak memotong view UIKit: sudut dan potongan video lewat CALayer.
                    this.layer.masksToBounds = true
                    this.layer.addSublayer(layer)
                }
            },
            modifier = Modifier.requiredSize(with(density) { (areaW * scale).toDp() }, with(density) { (areaH * scale).toDp() }),
            update = { view ->
                view.layer.cornerRadius = toPoints(video.radius.toFloat())
                place()
            },
            properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
        )
    }
}

/**
 * Subtitle dan hook dari ASS pratinjau, digambar libass (ass_core.c) ke buffer RGBA setiap frame. Waktu subtitle =
 * waktu video hasil (bagian yang dibuang dilewati); waktu hook = sejak awal pembuka, atau [hookTime] (tab Hook).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun AssOverlay(state: EditorState, composition: Composition, preview: PreviewPlayer, hookTime: Double?) {
    val tracks = state.tracks ?: return
    val engine = state.engine
    val fixedHook by rememberUpdatedState(hookTime)
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.roundToInt().coerceIn(2, 720)
        val height = (width.toFloat() * composition.canvas.height / composition.canvas.width).roundToInt().coerceAtLeast(2)

        LaunchedEffect(tracks, width, height) {
            val subs = engine.assCreate(width, height)
            val hook = tracks.hook?.let { engine.assCreate(width, height) }
            engine.assLoad(subs, tracks.subs)
            if (hook != null) engine.assLoad(hook, tracks.hook!!)
            val pixels = ByteArray(width * height * 4)
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val spec = state.spec()
            val cuts = spec.cutRanges()
            val lead = spec.leadSeconds()
            var last = Long.MIN_VALUE to Long.MIN_VALUE
            try {
                while (true) {
                    withFrameNanos { }
                    val source = preview.seconds().coerceIn(spec.startTime, spec.endTime)
                    val t = ClipCuts.map(cuts, spec.startTime, source)
                    val ms = (t * 1000).toLong()
                    val hookMs = ((fixedHook ?: (t + lead)) * 1000).toLong()
                    if ((ms to hookMs) == last) continue
                    last = ms to hookMs
                    pixels.usePinned { pinned ->
                        val address = pinned.addressOf(0).toLong()
                        engine.assRender(subs, ms, address, width, height, true)
                        if (hook != null) engine.assRender(hook, hookMs, address, width, height, false)
                    }
                    image = SkiaImage.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
                }
            } finally {
                engine.assDestroy(subs)
                if (hook != null) engine.assDestroy(hook)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            image?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        }
    }
}

/**
 * Posisi crop tetap (0–100) yang menampilkan bagian video yang sama dengan jalur pembicara saat ini, supaya mematikan
 * "Ikuti pembicara" tidak membuat crop meloncat.
 */
internal fun fixedPosition(state: EditorState, position: Double): Int {
    val spec = state.spec()
    val keyframes = spec.speakerKeyframes ?: return spec.effectiveCropPosition()
    val center = SpeakerTrack.positionAt(keyframes, position - state.snapshot.clip.startTime)
    val (sw, sh) = state.sourceSize ?: return (center * 100).roundToInt().coerceIn(0, 100)
    val composition = state.composition
    val rect = composition.video.rect ?: return (center * 100).roundToInt().coerceIn(0, 100)
    val shown = maxOf(rect.width.toDouble() / sw, rect.height.toDouble() / sh) * composition.video.zoom
    val dw = sw * shown
    if (dw <= rect.width) return 50
    return ((center * dw - rect.width / 2.0) / (dw - rect.width) * 100).roundToInt().coerceIn(0, 100)
}

