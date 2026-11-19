package id.clipmi.android.ui.editor

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import id.clipmi.android.AppContainer
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LightStatusIcons
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiDarkSurface
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.work.RenderQueue
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.SpeakerTrack
import id.clipmi.core.render.HookLayout
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private data class EditorTab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    EditorTab("Potong", ClipMiIcons.Scissors),
    EditorTab("Crop", ClipMiIcons.Image),
    EditorTab("Gaya", ClipMiIcons.Sliders),
    EditorTab("Hook", ClipMiIcons.Sparkle),
    EditorTab("Sampul", ClipMiIcons.Cover),
)

/**
 * Editor klip sentuh (selalu gelap): pratinjau dengan subtitle libass di atas, panel tab di bawah (miring: pratinjau di
 * kiri, panel di kanan). Setiap perubahan langsung tersimpan dan bisa diurungkan.
 */
@Composable
fun EditorScreen(container: AppContainer, clipId: Long, onClose: () -> Unit) = ClipMiDarkSurface {
    LightStatusIcons()
    val scope = rememberCoroutineScope()
    val loaded by produceState<Result<EditorState?>?>(null, clipId) { value = runCatching { EditorState.load(container, scope, clipId) } }
    val c = ClipMi.colors

    Box(Modifier.fillMaxSize().background(EDITOR_BG)) {
        when (val result = loaded) {
            null -> Unit
            else -> {
                val state = result.getOrNull()
                if (state == null) {
                    Column(Modifier.statusBarsPadding()) {
                        IconCircle(ClipMiIcons.Close, "Tutup editor", onClose, modifier = Modifier.padding(12.dp), background = Color.Transparent, border = false)
                        EmptyState("Video belum siap", "Editor butuh video sumber project ini. Tunggu unduhan selesai, atau pilih ulang videonya.", icon = ClipMiIcons.Film)
                    }
                } else {
                    Editor(container, state, onClose)
                }
            }
        }
    }
}

val EDITOR_BG = Color(0xFF050706)
val EDITOR_PANEL = Color(0xFF111614)
val EDITOR_CARD = Color(0xFF1A221F)

@Composable
private fun Editor(container: AppContainer, state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val c = ClipMi.colors
    // 0 Potong, 1 Crop, 2 Gaya, 3 Hook, 4 Sampul.
    var tab by rememberSaveable { mutableStateOf(0) }
    if (tab !in TABS.indices) tab = 0
    val player = remember { ExoPlayer.Builder(context).build() }
    // Disimpan supaya memutar layar (tegak ↔ miring) tidak mengembalikan video ke awal klip.
    var position by rememberSaveable { mutableDoubleStateOf(state.snapshot.clip.startTime) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(state.source.toURI().toString()))
        player.prepare()
        player.seekTo((position * 1000).toLong())
    }
    LaunchedEffect(Unit) { container.database.styles().observeCaptionStyles().collect { state.captionStyles = it; state.refreshTracks() } }
    LaunchedEffect(Unit) { container.database.styles().observeFrameStyles().collect { state.frameStyles = it; state.refreshTracks() } }
    LaunchedEffect(Unit) {
        container.database.bumpers().observeAll().collect { all ->
            state.bumpers = all.filter { container.workspace.file(it.path)?.isFile == true }
            state.refreshTracks()
        }
    }

    // Pemutaran klip: bagian yang dibuang dilompati, berhenti di akhir klip lalu kembali ke awal.
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            val clip = state.snapshot.clip
            val now = player.currentPosition / 1000.0
            position = now
            playing = player.isPlaying
            if (!player.isPlaying) continue
            val cut = state.spec().cutRanges().firstOrNull { now >= it.first && now < it.second }
            when {
                now >= clip.endTime -> {
                    player.pause()
                    player.seekTo((clip.startTime * 1000).toLong())
                }
                now < clip.startTime - 0.25 -> player.seekTo((clip.startTime * 1000).toLong())
                cut != null -> player.seekTo((cut.second * 1000).toLong())
            }
        }
    }

    val seek: (Double) -> Unit = { seconds -> player.seekTo((seconds * 1000).toLong()); position = seconds }

    val title: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            IconCircle(ClipMiIcons.Close, "Tutup editor", onClose, background = Color.Transparent, border = false)
            Text(
                ClipText.displayTitle(state.entity.title, state.entity.partNumber),
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
    }
    val actions: @Composable () -> Unit = {
        IconCircle(ClipMiIcons.Undo, "Urungkan", { state.undo() }, size = 40.dp, background = Color.Transparent, border = false, enabled = state.canUndo)
        IconCircle(ClipMiIcons.Redo, "Ulangi", { state.redo() }, size = 40.dp, background = Color.Transparent, border = false, enabled = state.canRedo)
        PrimaryButton("Render", height = 40.dp, onClick = {
            scope.launch {
                val latest = container.database.clips().find(state.entity.id) ?: return@launch
                RenderQueue.render(context, latest)
                notifier.show("Masuk antrean render. Progres tampil di notifikasi.")
            }
        })
    }

    val hookLayout = remember(state.snapshot, state.captionStyles, state.frameStyles) { state.hookLayout() }
    val lead = remember(state.snapshot) { state.spec().leadSeconds() }
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        EditorPreview(
            state, player,
            modifier = modifier,
            // Di tab Hook, hook selalu terlihat walau video tidak di waktu tampilnya.
            hookTime = if (tab == 3 && !playing) hookLayout.start + lead + minOf(0.6, (hookLayout.end - hookLayout.start) / 2) else null,
            gesture = when (tab) {
                1 -> PreviewGesture { pan, zoom, _, size, overflow -> crop(state, pan.x / size.width, pan.y / size.height, zoom, overflow, player.currentPosition / 1000.0) }
                // Gaya bingkai sedang diedit dan punya logo: seret di pratinjau untuk memindah logo (posisi bebas 0–100).
                2 -> if (state.frameDraft?.logoPath != null) PreviewGesture { _, _, centroid, size, _ -> moveLogo(state, centroid.x / size.width, centroid.y / size.height) } else null
                3 -> PreviewGesture { _, _, centroid, size, _ ->
                    val x = (centroid.x / size.width * 100).roundToInt().coerceIn(0, 100)
                    val y = (centroid.y / size.height * 100).roundToInt().coerceIn(0, 100)
                    state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))))) }
                }
                else -> null
            },
            onGestureEnd = { state.endDrag() },
        )
    }

    // Putar/jeda (ikon putih tanpa lingkaran), bilah geser tipis di waktu video hasil, dan waktu.
    val playback: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (playing) "Jeda" else "Putar") {
                    if (player.isPlaying) player.pause() else {
                        val clip = state.snapshot.clip
                        if (position < clip.startTime || position >= clip.endTime - 0.05) seek(clip.startTime)
                        player.play()
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) ClipMiIcons.Pause else ClipMiIcons.Play, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            val spec = state.spec()
            val cuts = spec.cutRanges()
            val total = spec.outputDuration()
            val t = ClipCuts.map(cuts, spec.startTime, position.coerceIn(spec.startTime, spec.endTime))
            SeekBar(
                fraction = if (total > 0) (t / total).toFloat() else 0f,
                modifier = Modifier.weight(1f),
                onSeek = { f -> seek(sourceTime(cuts, spec.startTime, spec.endTime, f * total)) },
            )
            Text("${Timecode.format(t)} / ${Timecode.format(total)}", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }

    // Isi tab dan deretan tab. [header] tampil di atas isi (mode miring: urungkan/ulangi/render).
    val panel: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { modifier, header ->
        Column(modifier) {
            header?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> TrimPanel(state, position, playing, seek)
                    1 -> CropPanel(state, position, seek)
                    2 -> StylePanel(state)
                    3 -> HookPanel(state)
                    4 -> CoverPanel(state, position)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                TABS.forEachIndexed { i, item ->
                    val active = tab == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { tab = i }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.size(width = 48.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (active) c.accentSoft else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) { Icon(item.icon, null, tint = if (active) c.accent else c.textMuted, modifier = Modifier.size(20.dp)) }
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                            color = if (active) c.accent else c.textMuted,
                        )
                    }
                }
            }
        }
    }

    // Saat keyboard terbuka (teks hook, teks sampul), seluruh editor menyusut supaya kolom isian tetap terlihat.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            // Miring (HP miring, tablet mendatar): pratinjau 9:16 setinggi layar di kiri, panel tab di kanan.
            val safeY = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
            val sideWidth = (maxWidth * 0.36f).coerceAtLeast(240.dp)
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Left))) {
                Column(Modifier.width(sideWidth).fillMaxHeight().windowInsetsPadding(safeY)) {
                    title(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp))
                    preview(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp))
                    playback(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 4.dp))
                }
                panel(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                        .background(EDITOR_PANEL)
                        .windowInsetsPadding(safeY)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Right)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) { actions() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // Bilah atas: tutup, judul, urungkan/ulangi, render.
                Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    title(Modifier.weight(1f))
                    actions()
                }
                preview(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp))
                playback(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp))
                panel(
                    Modifier
                        .fillMaxWidth()
                        .weight(1.05f)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(EDITOR_PANEL)
                        .navigationBarsPadding(),
                    null,
                )
            }
        }
    }
}

/**
 * Geser dan cubit di tab Crop: posisi 0–100 dan zoom 100–250% (padanan crop editor desktop). Saat mengikuti pembicara,
 * geser kiri–kanan mengatur posisi manual adegan yang sedang tampil (adegan lain tetap otomatis).
 */
private fun crop(state: EditorState, dx: Float, dy: Float, zoom: Float, overflow: Offset, position: Double) {
    state.drag { _ ->
        // Dihitung dari keadaan awal gerakan, jadi geser pelan pun terhitung (tidak hilang oleh pembulatan).
        val base = state.gestureBase
        val spec = state.spec(base)
        val baseZoom = base.cropZoom ?: (spec.frame.zoomFactor() * 100).roundToInt()
        // Video mengikuti jari: menggeser sejauh seluruh sisa video = posisi 0 ke 100.
        fun move(start: Int, delta: Float, rest: Float) = (start - delta / rest * 100).roundToInt().coerceIn(0, 100)
        val track = if (state.followsSpeaker(base)) state.speakerTrack(base) else null
        val time = position - base.clip.startTime
        val shot = track?.shotAt(time)
        var manual = base.speakerManual
        if (track != null && shot != null && dx != 0f && overflow.x > 0f) {
            val composition = state.composition()
            val area = (composition.video.rect?.width ?: composition.canvas.width).toFloat() / composition.canvas.width
            // Lebar video yang tampil, dalam pecahan lebar layar hasil; tengah crop dibatasi supaya crop tetap di dalam video.
            val videoWidth = overflow.x + area
            val half = area / 2 / videoWidth
            val start = shot.manual ?: track.keyframes.takeIf { it.isNotEmpty() }?.let { SpeakerTrack.positionAt(it, time) } ?: (spec.effectiveCropPosition() / 100.0)
            val visible = start.coerceIn(half.toDouble(), 1.0 - half)
            manual = track.withManual(shot, (visible - dx / videoWidth).coerceIn(half.toDouble(), 1.0 - half)).manual
        }
        base.copy(
            cropPosition = if (shot == null && dx != 0f && overflow.x > 0f) move(spec.effectiveCropPosition(), dx, overflow.x) else base.cropPosition,
            cropY = if (dy != 0f && overflow.y > 0f) move(spec.effectiveCropY(), dy, overflow.y) else base.cropY,
            cropZoom = if (zoom != 1f) (baseZoom * zoom).roundToInt().coerceIn(100, 250) else base.cropZoom,
            speakerManual = manual,
        )
    }
}

/** Bilah geser tipis (2 dp) dengan bulatan putih; ketuk atau seret untuk pindah. [fraction] 0–1. */
@Composable
private fun SeekBar(fraction: Float, modifier: Modifier, onSeek: (Float) -> Unit) {
    val c = ClipMi.colors
    androidx.compose.foundation.Canvas(
        modifier
            .height(28.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val y = size.height / 2
        val track = 2.dp.toPx()
        val x = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(c.line, topLeft = Offset(0f, y - track / 2), size = Size(size.width, track), cornerRadius = CornerRadius(track))
        drawRoundRect(Color.White.copy(alpha = 0.8f), topLeft = Offset(0f, y - track / 2), size = Size(x, track), cornerRadius = CornerRadius(track))
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, y))
    }
}

/** Waktu di video sumber untuk detik ke-[output] di video hasil (bagian yang dibuang dilewati). */
private fun sourceTime(cuts: List<id.clipmi.core.render.Range>, start: Double, end: Double, output: Double): Double {
    var left = output
    for ((from, to) in ClipCuts.kept(cuts, start, end)) {
        val length = to - from
        if (left <= length) return from + left
        left -= length
    }
    return end
}

/**
 * Pindah logo gaya bingkai yang sedang diedit ke titik jari ([fx], [fy] = pecahan layar): jari = tengah logo, dihitung
 * balik dari rumus letak render (tepi area jangkar di 0, tepi seberang di 100).
 */
private fun moveLogo(state: EditorState, fx: Float, fy: Float) {
    val draft = state.frameDraft ?: return
    val composition = state.composition()
    val canvas = composition.canvas
    val area = composition.anchorArea
    val logo = composition.logo
    val (iw, ih) = logoSizes.getOrPut(draft.logoPath ?: return) {
        val file = state.container.workspace.file(draft.logoPath) ?: return
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        maxOf(1, options.outWidth) to maxOf(1, options.outHeight)
    }
    val lh = logo.width.toDouble() * ih / iw
    fun percent(center: Double, size: Double, start: Int, end: Int, total: Int): Int {
        val room = total - size - start - end
        if (room <= 0) return 50
        return ((center - size / 2 - start) / room * 100).roundToInt().coerceIn(0, 100)
    }
    val x = percent(fx.toDouble() * canvas.width, logo.width.toDouble(), area.left, area.right, canvas.width)
    val y = percent(fy.toDouble() * canvas.height, lh, area.top, area.bottom, canvas.height)
    if (x != draft.logoX || y != draft.logoY) state.editFrame(draft.copy(logoX = x, logoY = y))
}

/** Ukuran gambar logo (lebar, tinggi) per path, supaya tidak dibaca ulang di tiap gerakan jari. */
private val logoSizes = HashMap<String, Pair<Int, Int>>()
