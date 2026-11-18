package id.clipmi.android.ui.editor

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
    EditorTab("Teks", ClipMiIcons.Edit),
    EditorTab("Crop", ClipMiIcons.Image),
    EditorTab("Gaya", ClipMiIcons.Sliders),
    EditorTab("Hook", ClipMiIcons.Sparkle),
    EditorTab("Sampul", ClipMiIcons.Cover),
)

/**
 * Editor klip sentuh (selalu gelap): pratinjau dengan subtitle libass di atas, panel tab di bawah. Setiap perubahan
 * langsung tersimpan dan bisa diurungkan.
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
    var tab by rememberSaveable { mutableStateOf(0) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var position by remember { mutableDoubleStateOf(state.snapshot.clip.startTime) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(state.source.toURI().toString()))
        player.prepare()
        player.seekTo((state.snapshot.clip.startTime * 1000).toLong())
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

    Column(Modifier.fillMaxSize()) {
        // Bilah atas: tutup, judul, urungkan/ulangi, render.
        Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(ClipMiIcons.Close, "Tutup editor", onClose, background = Color.Transparent, border = false)
            Text(
                ClipText.displayTitle(state.entity.title, state.entity.partNumber),
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
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

        // Pratinjau.
        val hookLayout = remember(state.snapshot, state.captionStyles, state.frameStyles) { state.hookLayout() }
        val lead = remember(state.snapshot) { state.spec().leadSeconds() }
        EditorPreview(
            state, player,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
            // Di tab Hook, hook selalu terlihat walau video tidak di waktu tampilnya.
            hookTime = if (tab == 4 && !playing) hookLayout.start + lead + minOf(0.6, (hookLayout.end - hookLayout.start) / 2) else null,
            gesture = when (tab) {
                2 -> PreviewGesture { pan, zoom, _, size, overflow -> crop(state, pan.x / size.width, pan.y / size.height, zoom, overflow) }
                4 -> PreviewGesture { _, _, centroid, size, _ ->
                    val x = (centroid.x / size.width * 100).roundToInt().coerceIn(0, 100)
                    val y = (centroid.y / size.height * 100).roundToInt().coerceIn(0, 100)
                    state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))))) }
                }
                else -> null
            },
            onGestureEnd = { state.endDrag() },
        )

        // Putar/jeda dan waktu di video hasil.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.text).clickable(role = Role.Button, onClickLabel = if (playing) "Jeda" else "Putar") {
                    if (player.isPlaying) player.pause() else {
                        val clip = state.snapshot.clip
                        if (position < clip.startTime || position >= clip.endTime - 0.05) seek(clip.startTime)
                        player.play()
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) ClipMiIcons.Pause else ClipMiIcons.Play, null, tint = EDITOR_BG, modifier = Modifier.size(20.dp)) }
            val spec = state.spec()
            val t = ClipCuts.map(spec.cutRanges(), spec.startTime, position.coerceIn(spec.startTime, spec.endTime))
            Text("${Timecode.format(t)} / ${Timecode.format(spec.outputDuration())}", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            Text(
                when (tab) {
                    2 -> "Geser video untuk posisi · cubit untuk zoom"
                    4 -> "Seret di pratinjau untuk memindah hook"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Panel tab.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1.05f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(EDITOR_PANEL)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> TrimPanel(state, position, seek)
                    1 -> TextPanel(state, seek)
                    2 -> CropPanel(state)
                    3 -> StylePanel(state)
                    4 -> HookPanel(state)
                    5 -> CoverPanel(state, position)
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
}

/** Geser dan cubit di tab Crop: posisi 0–100 dan zoom 100–250% (padanan crop editor desktop). */
private fun crop(state: EditorState, dx: Float, dy: Float, zoom: Float, overflow: Offset) {
    state.drag { _ ->
        // Dihitung dari keadaan awal gerakan, jadi geser pelan pun terhitung (tidak hilang oleh pembulatan).
        val base = state.gestureBase
        val spec = state.spec(base)
        val baseZoom = base.cropZoom ?: (spec.frame.zoomFactor() * 100).roundToInt()
        // Video mengikuti jari: menggeser sejauh seluruh sisa video = posisi 0 ke 100.
        fun move(start: Int, delta: Float, rest: Float) = (start - delta / rest * 100).roundToInt().coerceIn(0, 100)
        base.copy(
            cropPosition = if (dx != 0f && overflow.x > 0f) move(spec.effectiveCropPosition(), dx, overflow.x) else base.cropPosition,
            cropY = if (dy != 0f && overflow.y > 0f) move(spec.effectiveCropY(), dy, overflow.y) else base.cropY,
            cropZoom = if (zoom != 1f) (baseZoom * zoom).roundToInt().coerceIn(100, 250) else base.cropZoom,
        )
    }
}
