package id.clipmi.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import id.clipmi.android.data.RenderStatus
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.Composition
import id.clipmi.core.transcript.Timecode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Danger = Color(0xFFE5484D)

@Composable
actual fun ClipEditorScreen(repo: Repository, renders: Renders, clipId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val renderer = renders.renderer
    val state by produceState<EditorState?>(null, clipId) {
        val clip = repo.db.clips().find(clipId) ?: return@produceState
        val project = repo.db.projects().find(clip.projectId) ?: return@produceState
        val source = project.videoPath?.let { repo.absolute(it) }
        val info = source?.let { renderer.probe(it) }
        value = EditorState(
            repo, renderer, scope, clip, project,
            repo.db.styles().captionStyles(), repo.db.styles().frameStyles(), renderer.segments(clip.projectId),
            info?.duration ?: project.durationSeconds ?: clip.endTime,
            info?.let { i -> i.width?.let { w -> i.height?.let { h -> w to h } } },
        ).also { it.refresh(immediate = true) }
    }
    val c = MaterialTheme.colorScheme
    val current = state
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BackLink(onBack)
            Text("Memuat klip…", color = c.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(top = 16.dp))
        }
        return
    }
    val source = current.project.videoPath?.let { repo.absolute(it) }
    if (source == null || fileSize(source) <= 0) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BackLink(onBack)
            Text("Video sumber belum ada. Unduh ulang video project ini.", color = Danger, modifier = Modifier.padding(top = 16.dp))
        }
        return
    }
    EditorContent(current, renders, source, onBack)
}

@Composable
private fun BackLink(onBack: () -> Unit) {
    Text(
        "‹ Klip", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/** Pemutar AVPlayer untuk pratinjau: posisi video (detik sumber) dibaca setiap frame. */
@OptIn(ExperimentalForeignApi::class)
private class PreviewPlayer(path: String) {
    val player = AVPlayer(uRL = NSURL.fileURLWithPath(path))
    var playing by mutableStateOf(false)
        private set

    fun seconds(): Double = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() } ?: 0.0

    fun seek(seconds: Double) {
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600), CMTimeMake(0, 1), CMTimeMake(0, 1))
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

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun EditorContent(state: EditorState, renders: Renders, source: String, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val preview = remember(source) { PreviewPlayer(source) }
    var position by remember { mutableStateOf(state.start) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    DisposableEffect(preview) {
        // Sesi audio "playback" (suara tetap keluar walau tombol senyap aktif) diatur Swift saat aplikasi dibuka.
        preview.seek(state.start)
        onDispose { preview.release() }
    }
    // Posisi dan putar ulang di dalam rentang klip.
    LaunchedEffect(preview) {
        while (true) {
            withFrameNanos { }
            val now = preview.seconds()
            position = now
            if (preview.playing && now >= state.end - 0.03) {
                preview.pause()
                preview.seek(state.start)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Pratinjau tetap di atas; isian di bawahnya yang bergulir.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackLink(onBack)
                Text(
                    state.title, fontWeight = FontWeight.Bold, color = c.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val composition = state.composition
                if (composition != null) {
                    val ratio = composition.canvas.width.toFloat() / composition.canvas.height
                    val height = min(maxWidth.value / ratio, 380f)
                    Box(Modifier.height(height.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp))) {
                        Preview(state, composition, preview)
                    }
                } else {
                    Box(Modifier.height(380.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(Color.Black))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton(if (preview.playing) "Jeda" else "Putar") {
                    if (preview.playing) preview.pause() else {
                        if (position < state.start || position >= state.end - 0.05) preview.seek(state.start)
                        preview.play()
                    }
                }
                val length = (state.end - state.start).coerceAtLeast(0.1)
                Slider(
                    value = ((position - state.start) / length).toFloat().coerceIn(0f, 1f),
                    onValueChange = { preview.seek(state.start + it * length) },
                    modifier = Modifier.weight(1f),
                )
                Text("${Timecode.format((position - state.start).coerceAtLeast(0.0))} / ${Timecode.format(length)}", fontSize = 12.sp, color = c.onBackground.copy(alpha = 0.6f))
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Rentang klip") {
                    TimeNudge("Mulai", state.start, { state.nudgeStart(it); preview.pause(); preview.seek(state.start) })
                    TimeNudge("Selesai", state.end, { state.nudgeEnd(it); preview.pause(); preview.seek(max(state.start, state.end - 2.0)) })
                }
            }
            item {
                Section("Judul dan hook") {
                    OutlinedTextField(state.title, { state.title = it; state.refresh() }, Modifier.fillMaxWidth(), label = { Text("Judul") }, maxLines = 3)
                    OutlinedTextField(state.hook, { state.hook = it; state.refresh() }, Modifier.fillMaxWidth(), label = { Text("Hook (tampil di awal video)") }, maxLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tampilkan hook di video", color = c.onSurface, modifier = Modifier.weight(1f))
                        Switch(state.showHook, { state.showHook = it; state.refresh() })
                    }
                }
            }
            item {
                Section("Gaya subtitle") {
                    ChoiceRow(state.captionStyles.map { it.id to it.name }, state.captionStyleId) { state.captionStyleId = it; state.refresh() }
                }
            }
            item {
                Section("Bingkai") {
                    ChoiceRow(state.frameStyles.map { it.id to it.name }, state.frameStyleId) { state.frameStyleId = it; state.refresh() }
                }
            }
            item { Box(Modifier.height(8.dp)) }
        }

        // Aksi bawah: simpan, render (menyimpan dulu), progres render.
        Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rendering = renders.active == state.clip.id
            if (rendering) {
                LinearProgressIndicator(progress = { renders.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                    SmallButton("Batal") { renders.cancel() }
                }
            } else {
                val clip by remember(state.clip.id) { state.liveClip() }.collectAsState(state.clip)
                message?.let { (text, ok) -> Text(text, fontSize = 13.sp, color = if (ok) c.primary else Danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                message = runCatching { state.save() }.fold({ "Tersimpan." to true }, { (it.message ?: "Gagal menyimpan.") to false })
                            }
                        },
                        enabled = !state.saving && state.changed, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    ) { Text("Simpan") }
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { if (state.changed) state.save() }
                                    .onSuccess { preview.pause(); message = null; renders.start(state.clip.id) }
                                    .onFailure { message = (it.message ?: "Gagal menyimpan.") to false }
                            }
                        },
                        enabled = renders.active == null && !state.saving, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary),
                    ) { Text("Render") }
                }
                val latest = clip ?: state.clip
                if (RenderStatus.from(latest.renderStatus) == RenderStatus.Done) {
                    Text("Sudah dirender · tersimpan di Photos", fontSize = 12.sp, color = c.primary)
                }
                if (RenderStatus.from(latest.renderStatus) == RenderStatus.Failed && latest.renderError != null) {
                    Text(latest.renderError!!, fontSize = 12.sp, color = Danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(label, fontSize = 13.sp) }
}

/** Waktu dengan satu desimal, misalnya 01:23.4. */
private fun fineTime(seconds: Double): String = "${Timecode.format(seconds)}.${((seconds * 10).roundToInt() % 10 + 10) % 10}"

@Composable
private fun TimeNudge(label: String, value: Double, onNudge: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(72.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(fineTime(value), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        for ((text, delta) in listOf("−1" to -1.0, "−0,1" to -0.1, "+0,1" to 0.1, "+1" to 1.0)) {
            SmallButton(text) { onNudge(delta) }
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<Long, String>>, selected: Long?, onSelect: (Long) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, name) in options) {
            val active = id == selected
            Text(
                name, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) c.primary else c.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (active) c.primary.copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.06f))
                    .clickable { onSelect(id) }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Pratinjau sebesar layar hasil: latar, video sesuai [Composition], lalu subtitle dan hook dari libass. */
@Composable
private fun Preview(state: EditorState, composition: Composition, preview: PreviewPlayer) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = widthPx / composition.canvas.width
        Background(composition)
        VideoLayer(composition, state.sourceSize, scale, preview)
        AssOverlay(state, composition, preview)
    }
}

private fun parse(hex: String?): Color? =
    hex?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/** Latar: warna atau gradasi; latar blur ditampilkan sebagai warnanya (thumbnail belum ada di iOS). */
@Composable
private fun Background(composition: Composition) {
    val background = composition.background
    val color = parse(background?.color) ?: Color.Black
    if (background?.kind == "gradient") {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color, parse(background.color2) ?: Color.Black))))
    } else {
        Box(Modifier.fillMaxSize().background(color))
    }
}

/**
 * Video di area [Composition.Video.rect]: "cover" = menutupi area lalu digeser sesuai posisi crop (seperti filter crop
 * FFmpeg), "contain" = utuh di dalam area; zoom memperbesar dari posisi yang sama (sama dengan VideoLayer Android).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun VideoLayer(composition: Composition, sourceSize: Pair<Int, Int>?, scale: Float, preview: PreviewPlayer) {
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
    val left = if (dw > areaW) -overflowX * video.cropX / 100f else (areaW - dw) / 2
    val top = if (dh > areaH) -overflowY * video.cropY / 100f else (areaH - dh) / 2
    // Satuan UIKit = dp Compose (titik layar).
    val toPoints = { px: Float -> (px * scale / density.density).toDouble() }
    val layer = remember(preview) { AVPlayerLayer.playerLayerWithPlayer(preview.player).apply { videoGravity = AVLayerVideoGravityResize } }

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
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                view.layer.cornerRadius = toPoints(video.radius.toFloat())
                layer.frame = CGRectMake(toPoints(left), toPoints(top), toPoints(dw), toPoints(dh))
                CATransaction.commit()
            },
        )
    }
}

/**
 * Subtitle dan hook dari ASS pratinjau, digambar libass (ass_core.c) ke buffer RGBA setiap frame. Waktu subtitle =
 * waktu video hasil (bagian yang dibuang dilewati); waktu hook = sejak awal pembuka (sama dengan Android).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun AssOverlay(state: EditorState, composition: Composition, preview: PreviewPlayer) {
    val tracks = state.tracks ?: return
    val engine = state.engine
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.roundToInt().coerceIn(2, 720)
        val height = (width.toFloat() * composition.canvas.height / composition.canvas.width).roundToInt().coerceAtLeast(2)

        LaunchedEffect(tracks, width) {
            val subs = engine.assCreate(width, height)
            val hook = tracks.hook?.let { engine.assCreate(width, height) }
            engine.assLoad(subs, tracks.subs)
            if (hook != null) engine.assLoad(hook, tracks.hook!!)
            val pixels = ByteArray(width * height * 4)
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val spec = state.spec
            val cuts = spec?.cutRanges().orEmpty()
            val lead = spec?.leadSeconds() ?: 0.0
            var last = Long.MIN_VALUE
            try {
                while (true) {
                    withFrameNanos { }
                    val source = preview.seconds().coerceIn(state.start, state.end)
                    val t = ClipCuts.map(cuts, state.start, source)
                    val ms = (t * 1000).toLong()
                    if (ms == last) continue
                    last = ms
                    pixels.usePinned { pinned ->
                        val address = pinned.addressOf(0).toLong()
                        engine.assRender(subs, ms, address, width, height, true)
                        if (hook != null) engine.assRender(hook, ((t + lead) * 1000).toLong(), address, width, height, false)
                    }
                    image = Image.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
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
