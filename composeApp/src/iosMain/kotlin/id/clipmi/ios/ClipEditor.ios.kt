package id.clipmi.ios

import id.clipmi.android.ui.components.isSideBySide
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.ui.components.MarkupField
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import id.clipmi.android.ui.editor.PlatformGuideLayer
import id.clipmi.android.ui.editor.PlatformGuide
import id.clipmi.android.ui.editor.EditorPreviewPrefs
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiRangeSlider
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSlider
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipEditor
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.CoverTitle
import id.clipmi.core.render.CoverTitleLayout
import id.clipmi.core.render.EditorWord
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.HookLayout
import id.clipmi.core.render.Pause
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Warna editor (selalu gelap), sama dengan EditorScreen Android.
private val EDITOR_BG = Color(0xFF050706)
private val EDITOR_PANEL = Color(0xFF111614)
private val EDITOR_CARD = Color(0xFF1A221F)

private data class EditorTab(val label: String, val icon: ImageVector)

// 0 Potong, 1 Crop, 2 Gaya, 3 Hook, 4 Sampul (urutan Android).
private val TABS = listOf(
    EditorTab("Potong", ClipMiIcons.Scissors),
    EditorTab("Crop", ClipMiIcons.Image),
    EditorTab("Gaya", ClipMiIcons.Sliders),
    EditorTab("Hook", ClipMiIcons.Sparkle),
    EditorTab("Sampul", ClipMiIcons.Cover),
)

/**
 * Editor klip iOS dengan susunan Android: bilah atas (tutup, judul, urungkan/ulangi, Render), pratinjau dengan
 * subtitle libass, baris putar, lalu panel tab di bawah. Setiap perubahan langsung tersimpan dan bisa diurungkan.
 */
@Composable
actual fun ClipEditorScreen(repo: Repository, renders: Renders, clipId: Long, onBack: () -> Unit, onEditStyle: (caption: Boolean, id: Long) -> Unit) {
    // Scope aplikasi (bukan layar): penyimpanan terakhir tetap selesai walau editor sudah ditutup.
    val scope = renders.scope
    // null = sedang dimuat; Result.failure = tidak bisa dibuka.
    val loaded by produceState<Result<EditorState>?>(null, clipId) {
        value = runCatching { EditorState.load(repo, renders.renderer, scope, clipId) ?: error("Editor butuh video sumber project ini. Tunggu unduhan selesai atau unduh ulang videonya.") }
    }
    Box(Modifier.fillMaxSize().background(EDITOR_BG)) {
        val result = loaded ?: return@Box
        val state = result.getOrNull()
        if (state == null) {
            Column {
                IconCircle(ClipMiIcons.Close, "Tutup editor", onBack, modifier = Modifier.padding(12.dp), background = Color.Transparent, border = false)
                EmptyState("Video belum siap", result.exceptionOrNull()?.message ?: "", icon = ClipMiIcons.Film)
            }
        } else {
            Editor(state, renders, onBack, onEditStyle)
        }
    }
}

@Composable
private fun Editor(state: EditorState, renders: Renders, onClose: () -> Unit, onEditStyle: (caption: Boolean, id: Long) -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val preview = remember(state.source) { PreviewPlayer(state.source) }
    val pending = remember { PendingText() }
    var position by remember { mutableStateOf(state.snapshot.clip.startTime) }
    // Tab terakhir per klip (kembali dari halaman ubah gaya tetap di tab Gaya).
    var tab by remember { mutableStateOf(if (EditorMemory.clipId == state.entity.id) EditorMemory.tab else 0) }
    LaunchedEffect(tab) { EditorMemory.clipId = state.entity.id; EditorMemory.tab = tab }
    val compact = EditorPreviewPrefs.compact

    val snapshot = state.snapshot
    val spec = remember(snapshot) { state.spec() }
    val cuts = remember(spec) { spec.cutRanges() }
    val currentCuts by rememberUpdatedState(cuts)

    DisposableEffect(preview) {
        // Sesi audio "playback" (suara tetap keluar walau tombol senyap aktif) diatur Swift saat aplikasi dibuka.
        preview.seek(state.snapshot.clip.startTime)
        onDispose { preview.release() }
    }
    // Pemutaran klip: bagian yang dibuang dilompati, berhenti di akhir klip lalu kembali ke awal (sama dengan Android).
    LaunchedEffect(preview) {
        while (true) {
            withFrameNanos { }
            val clip = state.snapshot.clip
            val now = preview.seconds()
            position = now
            if (!preview.playing || preview.settling(now)) continue
            // Lompat sedikit melewati akhir potongan: AVPlayer membulatkan waktu ke 1/600 detik, jadi lompatan tepat ke
            // akhir potongan bisa mendarat sedikit sebelumnya dan terus melompat (video macet).
            val cut = currentCuts.firstOrNull { now >= it.first && now < it.second - 0.04 }
            when {
                now >= clip.endTime - 0.03 -> {
                    preview.pause()
                    preview.seek(clip.startTime)
                }
                now < clip.startTime - 0.25 -> preview.seek(clip.startTime)
                cut != null -> preview.seek(min(cut.second + 0.02, clip.endTime))
            }
        }
    }
    val seek: (Double) -> Unit = { preview.seek(it); position = it }

    // Render selesai/gagal: jendela render (bila terbuka) atau snackbar (hasil ada di Photos).
    val rendering = renders.active == state.entity.id
    var renderWindow by remember { mutableStateOf(false) }
    val live by remember(state.entity.id) { state.liveClip() }.collectAsState(state.entity)
    var wasRendering by remember { mutableStateOf(false) }
    LaunchedEffect(rendering, live?.renderStatus) {
        if (rendering) wasRendering = true
        else if (wasRendering) {
            val latest = live ?: return@LaunchedEffect
            if (RenderStatus.from(latest.renderStatus).isBusy) return@LaunchedEffect
            wasRendering = false
            if (!renderWindow) when (RenderStatus.from(latest.renderStatus)) {
                RenderStatus.Done -> notifier.show("Render selesai · tersimpan di Photos.")
                RenderStatus.Failed -> notifier.show(latest.renderError ?: "Render gagal.")
                else -> Unit
            }
        }
    }

    if (renderWindow) {
        RenderWindow(state, renders, live, onRetry = { renders.start(state.entity.id) }, onDismiss = { renderWindow = false })
    }

    val topBar: @Composable () -> Unit = {
        // Bilah atas: tutup, judul, urungkan/ulangi, render.
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(ClipMiIcons.Close, "Tutup editor", { pending.commitAll(); onClose() }, background = Color.Transparent, border = false)
            Text(
                ClipText.displayTitle(snapshot.title, state.entity.partNumber), style = MaterialTheme.typography.titleMedium, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconCircle(ClipMiIcons.Undo, "Urungkan", { state.undo() }, size = 40.dp, background = Color.Transparent, border = false, enabled = state.canUndo)
            IconCircle(ClipMiIcons.Redo, "Ulangi", { state.redo() }, size = 40.dp, background = Color.Transparent, border = false, enabled = state.canRedo)
            PrimaryButton("Render", height = 40.dp, enabled = renders.active == null || rendering, onClick = {
                if (rendering) {
                    renderWindow = true
                    return@PrimaryButton
                }
                pending.commitAll()
                scope.launch {
                    state.flush()
                    preview.pause()
                    renderWindow = true
                    renders.start(state.entity.id)
                }
            })
        }
        if (rendering) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", style = MaterialTheme.typography.bodySmall, color = c.textBody, modifier = Modifier.weight(1f))
                    GhostButton("Batalkan", onClick = { renders.cancel() })
                }
                ProgressBar(renders.fraction.toFloat(), color = c.busy)
            }
        }
    }

    // Pratinjau (bisa diperkecil supaya panel mendapat ruang lebih).
    val previewWeight by animateFloatAsState(if (compact) 0.5f else 1f, label = "pratinjau")
    val previewArea: @Composable (Modifier) -> Unit = { area ->
        Box(area) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val composition = state.composition
                val ratio = composition.canvas.width.toFloat() / composition.canvas.height
                val boxWidth = min(maxWidth.value, maxHeight.value * ratio)
                Box(Modifier.width(boxWidth.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    Preview(state, composition, preview, mode = when (tab) { 1 -> PreviewMode.Crop; 3 -> PreviewMode.Hook; else -> PreviewMode.None })
                    // Tampilan aplikasi tujuan (hanya panduan, tidak ikut dirender).
                    EditorPreviewPrefs.guide?.let { PlatformGuideLayer(it, Modifier.fillMaxSize()) }
                }
            }
            Column(Modifier.align(Alignment.BottomEnd)) {
                // Tampilan aplikasi tujuan: menu asli iOS (centang pada pilihan aktif).
                val guide = EditorPreviewPrefs.guide
                Box(
                    Modifier.padding(8.dp).size(40.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (guide != null) c.accent.copy(alpha = 0.5f) else c.line, RoundedCornerShape(12.dp)),
                ) {
                    NativeMenuButton(
                        buildList {
                            add(NativeMenuItem("Tanpa tampilan aplikasi", "eye.slash", checked = guide == null) { EditorPreviewPrefs.guide = null })
                            PlatformGuide.entries.forEach { g ->
                                add(NativeMenuItem("Tampilan ${g.label}", "iphone", checked = g == guide) { EditorPreviewPrefs.guide = g })
                            }
                        },
                        background = c.bg, fill = if (guide != null) c.accentSoft else EDITOR_CARD,
                        symbol = "eye", tint = if (guide != null) c.accent else Color.White, size = 40.dp,
                    )
                }
                ToolButton(if (compact) ClipMiIcons.Expand else ClipMiIcons.Collapse, if (compact) "Perbesar pratinjau" else "Perkecil pratinjau") {
                    EditorPreviewPrefs.compact = !compact
                }
            }
        }
    }

    // Putar/jeda, bilah geser tipis di waktu video hasil, dan waktu.
    val playback: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) {
                    if (preview.playing) preview.pause() else {
                        if (position < spec.startTime || position >= spec.endTime - 0.05) seek(spec.startTime)
                        preview.play()
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (preview.playing) ClipMiIcons.Pause else ClipMiIcons.Play, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            val total = spec.outputDuration()
            val t = ClipCuts.map(cuts, spec.startTime, position.coerceIn(spec.startTime, spec.endTime))
            SeekBar(if (total > 0) (t / total).toFloat() else 0f, Modifier.weight(1f)) { f -> seek(sourceTime(cuts, spec.startTime, spec.endTime, f * total)) }
            Text("${Timecode.format(t)} / ${Timecode.format(total)}", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }

    // Panel tab dan deretan tab.
    val panel: @Composable (Modifier) -> Unit = { area ->
        Column(area.background(EDITOR_PANEL)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> TrimPanel(state, position, preview.playing, seek)
                    1 -> CropPanel(state, position, seek)
                    2 -> StylePanel(state, onEditStyle)
                    3 -> HookPanel(state, pending)
                    else -> CoverPanel(state, position, pending)
                }
            }
            // Saat mengetik, deretan tab disembunyikan supaya kolom isian dan bilah alatnya tidak tertutup.
            val keyboardOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            if (!keyboardOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                TABS.forEachIndexed { i, item ->
                    val active = tab == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { pending.commitAll(); tab = i }.padding(vertical = 4.dp),
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

    if (isSideBySide()) {
        // iPad atau layar miring: pratinjau di kiri, panel di kanan (sama dengan Android).
        Row(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                topBar()
                previewArea(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp))
                playback()
            }
            panel(Modifier.weight(0.58f).fillMaxHeight().clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)))
        }
    } else {
        Column(Modifier.fillMaxSize().imePadding()) {
            topBar()
            previewArea(Modifier.fillMaxWidth().weight(previewWeight).padding(horizontal = 16.dp, vertical = 4.dp))
            playback()
            panel(Modifier.fillMaxWidth().weight(1.05f).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)))
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.padding(8.dp).size(40.dp).clip(shape).background(if (active) c.accentSoft else EDITOR_CARD)
            .border(1.dp, if (active) c.accent.copy(alpha = 0.5f) else c.line, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (active) c.accent else Color.White, modifier = Modifier.size(20.dp)) }
}

/** Tab editor terakhir per klip, supaya kembali dari halaman ubah gaya tetap di tab yang sama. */
private object EditorMemory {
    var clipId: Long = 0
    var tab: Int = 0
}

/** Bilah geser tipis (2 dp) dengan bulatan putih; ketuk atau seret untuk pindah. [fraction] 0–1. */
@Composable
private fun SeekBar(fraction: Float, modifier: Modifier, onSeek: (Float) -> Unit) {
    val c = ClipMi.colors
    val update by rememberUpdatedState(onSeek)
    Canvas(
        modifier
            .height(28.dp)
            .pointerInput(Unit) { detectTapGestures { update((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> update((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val y = size.height / 2
        val track = 2.dp.toPx()
        val x = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(c.line, topLeft = Offset(0f, y - track / 2), size = Size(size.width, track), cornerRadius = CornerRadius(track))
        drawRoundRect(Color.White.copy(alpha = 0.8f), topLeft = Offset(0f, y - track / 2), size = Size(x, track), cornerRadius = CornerRadius(track))
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, y))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Komponen panel
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun PanelTitle(title: String, hint: String? = null) {
    val c = ClipMi.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.text, modifier = Modifier.weight(1f))
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = ClipMi.colors.text, modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ClipMi.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        ClipMiSwitch(checked, onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val c = ClipMi.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = c.text)
        }
        ClipMiSlider(value = current, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

/**
 * Kolom teks yang tersimpan sendiri 0,8 detik setelah berhenti mengetik (centang hijau sebentar sebagai tanda), atau
 * saat pindah tab/keluar. Nilai dari luar (urungkan) menggantikan isi kolom.
 */
@Composable
private fun AutoSaveField(
    key: String, value: String, pending: PendingText, label: String? = null, placeholder: String? = null, supporting: String? = null,
    minLines: Int = 1, maxLines: Int = 3, markup: Boolean = false, onSave: (String) -> Unit,
) {
    val c = ClipMi.colors
    var text by remember(key) { mutableStateOf(value) }
    var saved by remember { mutableStateOf(false) }
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(value) { if (value != text.trim()) text = value }
    DisposableEffect(key) { onDispose { pending.commit(key) } }
    LaunchedEffect(text) {
        if (text.trim() == value) {
            pending.set(key, null)
            return@LaunchedEffect
        }
        val current = text
        pending.set(key) { save(current) }
        delay(800)
        pending.set(key, null)
        save(current)
        saved = true
        delay(2000)
        saved = false
    }
    val mark: (@Composable () -> Unit)? = if (saved) ({ Icon(ClipMiIcons.Check, contentDescription = "Tersimpan", tint = c.accent, modifier = Modifier.padding(end = 12.dp).size(20.dp)) }) else null
    if (markup) {
        MarkupField(text, { text = it }, label = label, supporting = supporting, minLines = minLines, maxLines = maxLines, trailing = mark)
    } else {
        Field(text, { text = it }, label = label, placeholder = placeholder, supporting = supporting, minLines = minLines, maxLines = maxLines, trailing = mark)
    }
}

/** Waktu dengan persepuluhan detik, misalnya "26:27,4". */
private fun precise(seconds: Double): String = Timecode.format(seconds) + "," + (((seconds * 10).roundToInt() % 10) + 10) % 10

/** Angka satu desimal dengan koma, misalnya 2,5. */
private fun seconds(value: Double): String {
    val tenths = (abs(value) * 10).roundToInt()
    return (if (value < 0 && tenths > 0) "−" else "") + "${tenths / 10},${tenths % 10}"
}

// ---------------------------------------------------------------------------------------------------------------
// Potong: timeline, ujung mulai/selesai, dan teks yang berjalan mengikuti video
// ---------------------------------------------------------------------------------------------------------------

/**
 * Panel Potong (sama dengan Android): timeline (ketuk untuk lompat, seret untuk menelusuri) dengan tombol ◀ ▶ mulai di
 * kiri dan selesai di kanan (ketuk = 0,1 dtk, tahan = terus bergeser makin cepat), aksi cepat, lalu teks klip yang
 * mengikuti video. Ketuk kata untuk buang/kembalikan, tahan untuk opsi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrimPanel(state: EditorState, position: Double, playing: Boolean, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val notifier = LocalNotifier.current
    val snapshot = state.snapshot
    val clip = snapshot.clip
    val editor = remember(snapshot.captionStyleId) { state.editor() }
    val limit = state.sourceDuration ?: state.segments.maxOfOrNull { it.end } ?: clip.endTime
    // Rentang timeline: klip ±10 detik (dibulatkan ke 10 detik), tetap selama ujung sedang digeser.
    var holding by remember { mutableStateOf(false) }
    var window by remember { mutableStateOf(0.0 to 0.0) }
    LaunchedEffect(clip.startTime, clip.endTime, holding) {
        if (!holding) window = maxOf(0.0, floor((clip.startTime - 10) / 10) * 10) to minOf(limit, ceil((clip.endTime + 10) / 10) * 10)
    }
    val (from, to) = window
    val words = remember(window, state.segments) {
        state.segments.filter { it.end >= from && it.start <= to }.flatMap { it.words.orEmpty() }.filter { it.end >= from && it.start <= to }
    }
    val spec = remember(snapshot) { state.spec() }
    val cuts = remember(spec) { spec.cutRanges() }
    val lines = remember(snapshot) { editor.lines(clip) }
    val clipWords = remember(snapshot) { editor.words(clip) }
    val pauses = remember(snapshot, state.silences) { state.silences?.let { editor.pauses(clip, it) }.orEmpty() }
    var chosen by remember { mutableStateOf<EditorWord?>(null) }
    var bumper by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(clip.startTime, clip.endTime) { state.detectSilences() }
    val tooShort = "Sisa klip terlalu pendek (minimal ${seconds(ClipSpec.MIN_DURATION)} detik)."

    fun nudge(edge: String, delta: Double) {
        state.drag { s -> s.copy(clip = editor.nudge(s.clip, edge, delta)) }
        val now = state.snapshot.clip
        seek(if (edge == "start") now.startTime else maxOf(now.startTime, now.endTime - 1.5))
    }

    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Timeline dengan tombol ujung: kiri = mulai, kanan = selesai.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NudgeColumn("mulai", onStart = { holding = true }, onEnd = { holding = false; state.endDrag() }) { delta -> nudge("start", delta) }
            val span = (to - from).takeIf { it > 0 } ?: 1.0
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(window) { detectTapGestures { offset -> seek(from + span * (offset.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(window, "scrub") { detectDragGestures { change, _ -> seek(from + span * (change.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val w = size.width
                val h = size.height
                fun x(t: Double) = ((t - from) / span * w).toFloat()
                drawRoundRect(EDITOR_CARD, cornerRadius = CornerRadius(10.dp.toPx()))
                for (word in words) {
                    val inside = word.end > clip.startTime && word.start < clip.endTime
                    drawRoundRect(
                        if (inside) Color(0xFF3B5A50) else Color(0xFF26302D),
                        topLeft = Offset(x(word.start) + 1, h * 0.3f),
                        size = Size(maxOf(2f, x(word.end) - x(word.start) - 2), h * 0.4f),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                for ((a, b) in cuts) drawRect(c.bad.copy(alpha = 0.45f), topLeft = Offset(x(a), h * 0.18f), size = Size(x(b) - x(a), h * 0.64f))
                val left = x(clip.startTime)
                val right = x(clip.endTime)
                drawRect(c.accent.copy(alpha = 0.14f), topLeft = Offset(left, 0f), size = Size(right - left, h))
                val edge = 3.dp.toPx()
                drawRect(c.accent, topLeft = Offset(left, 0f), size = Size(edge, h))
                drawRect(c.accent, topLeft = Offset(right - edge, 0f), size = Size(edge, h))
                val px = x(position)
                if (px in 0f..w) drawRect(Color.White, topLeft = Offset(px - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))
            }
            NudgeColumn("selesai", onStart = { holding = true }, onEnd = { holding = false; state.endDrag() }) { delta -> nudge("end", delta) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(precise(clip.startTime), style = MaterialTheme.typography.labelMedium, color = c.text)
            Text(
                "hasil ${Timecode.format(spec.outputDuration())}" + if (cuts.isNotEmpty()) " · ${cuts.size} dibuang" else "",
                style = MaterialTheme.typography.labelSmall, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text(precise(clip.endTime), style = MaterialTheme.typography.labelMedium, color = c.text)
        }

        ChipRow(gutter = 16.dp) {
            ActionChip("Mulai di sini", { state.applyClip { editor.setRange(it, position, it.endTime) } }, height = 34.dp, enabled = position < clip.endTime - ClipSpec.MIN_DURATION)
            ActionChip("Selesai di sini", { state.applyClip { editor.setRange(it, it.startTime, position) } }, height = 34.dp, enabled = position > clip.startTime + ClipSpec.MIN_DURATION)
            val longPauses = pauses.filter { !it.removed && it.length >= 0.6 }
            if (longPauses.isNotEmpty()) {
                ActionChip("Buang ${longPauses.size} jeda", {
                    if (!state.applyClip { editor.cleanPauses(it, state.silences.orEmpty(), 0.6) }) notifier.show(tooShort)
                }, height = 34.dp)
            }
            ActionChip("Tempel ke batas kata", { state.applyClip { editor.snapToWords(it) } }, height = 34.dp)
            ActionChip("+ Kalimat sebelum", { state.applyClip { editor.extend(it, "before") } }, height = 34.dp, enabled = editor.neighbour(clip, "before") != null)
            ActionChip("+ Kalimat sesudah", { state.applyClip { editor.extend(it, "after") } }, height = 34.dp, enabled = editor.neighbour(clip, "after") != null)
        }
        state.silenceError?.let { Text("Jeda tidak terbaca: $it", style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(horizontal = 16.dp)) }

        // Teks berjalan: kalimat yang sedang terdengar disorot dan digulir ke atas daftar saat video berjalan.
        val listState = rememberLazyListState()
        val active = lines.indexOfLast { it.start <= position + 0.05 }.coerceAtLeast(0)
        LaunchedEffect(active, playing) {
            if (playing && !listState.isScrollInProgress && lines.isNotEmpty()) listState.animateScrollToItem(active + 1, scrollOffset = -8)
        }
        var offset = 0
        val ranges = lines.map { line -> (offset until offset + line.words.orEmpty().size).also { offset += line.words.orEmpty().size } }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") { BumperCard("Intro", state.bumpers.firstOrNull { it.id == snapshot.introId }?.name) { bumper = "intro" } }
            itemsIndexed(lines) { index, line ->
                val range = ranges[index]
                val marked = Keywords.mark(line.words.orEmpty().map { it.text }, snapshot.keywords)
                val current = index == active && position >= clip.startTime && position <= clip.endTime
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(EDITOR_CARD)
                        .border(1.dp, if (current) c.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        Timecode.format(ClipCuts.map(cuts, spec.startTime, line.start)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) c.accent else c.textMuted,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { seek(line.start) }.padding(end = 6.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val togglePause: (Pause) -> Unit = { pause -> if (!state.applyClip { editor.togglePause(it, pause) }) notifier.show(tooShort) }
                        if (index == 0) pauses.filter { it.after < range.first }.forEach { pause -> PauseChip(pause) { togglePause(pause) } }
                        for ((k, i) in range.withIndex()) {
                            val word = clipWords.getOrNull(i) ?: continue
                            WordChip(
                                word, keyword = k in marked, speaking = current && position >= word.start && position < word.end,
                                onClick = {
                                    val ok = state.applyClip { if (word.cut) editor.restore(it, word.start, word.end) else editor.cut(it, word.start, word.end) }
                                    if (!ok && !word.cut) notifier.show(tooShort)
                                },
                                onLongClick = { chosen = word },
                            )
                            pauses.filter { it.after == i }.forEach { pause -> PauseChip(pause) { togglePause(pause) } }
                        }
                    }
                }
            }
            item(key = "outro") { BumperCard("Outro", state.bumpers.firstOrNull { it.id == snapshot.outroId }?.name) { bumper = "outro" } }
        }
    }

    chosen?.let { word -> WordSheet(state, editor, word, seek) { chosen = null } }
    bumper?.let { kind -> BumperSheet(state, kind, onDone = { bumper = null }) }
}

/** Kartu intro/outro di awal/akhir teks klip; ketuk untuk memilih video dari pustaka (sama dengan Android). */
@Composable
private fun BumperCard(label: String, name: String?, onClick: () -> Unit) {
    val c = ClipMi.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (name == null) ClipMiIcons.Plus else ClipMiIcons.Film, null, tint = if (name == null) c.textMuted else c.accent, modifier = Modifier.size(16.dp))
        Text(name?.let { "$label · $it" } ?: label, style = MaterialTheme.typography.labelLarge, color = if (name == null) c.textMuted else c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pilih intro atau outro klip ini dari pustaka; bisa dijadikan bawaan project. */
@Composable
private fun BumperSheet(state: EditorState, kind: String, onDone: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val s = state.snapshot
    val label = if (kind == "intro") "Intro" else "Outro"
    val selected = if (kind == "intro") s.introId else s.outroId
    val options = state.bumpers.filter { it.kind == kind }
    fun choose(id: Long?) {
        state.apply { if (kind == "intro") it.copy(introId = id) else it.copy(outroId = id) }
    }
    ClipMiSheet(onDone, title = label) {
        if (options.isEmpty()) {
            Text("Belum ada video di pustaka. Tambahkan di Pengaturan › Intro & outro.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Tanpa ${label.lowercase()}", selected = selected == null, height = 40.dp, onClick = { choose(null); onDone() })
            options.forEach { b ->
                FilterPill("${b.name} · ${kotlin.math.round(b.duration).toInt()} dtk", selected = selected == b.id, height = 40.dp, onClick = { choose(b.id); onDone() })
            }
        }
        if (kind == "intro" && s.introId != null && state.hookLayout().opener) {
            Text("Dengan intro, kartu pembuka hook tidak dipakai.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        if (options.isNotEmpty()) {
            val isProjectDefault = state.projectBumpers == (s.introId to s.outroId)
            GhostButton(if (isProjectDefault) "Sudah jadi bawaan project" else "Jadikan bawaan project", enabled = !isProjectDefault, onClick = {
                scope.launch {
                    state.saveProjectBumpers()
                    notifier.show("Klip baru di project ini memakai intro/outro yang sama.")
                }
            })
        }
    }
}

/** Dua tombol kecil bertumpuk (▶ atas = maju, ◀ bawah = mundur) untuk satu ujung klip. */
@Composable
private fun NudgeColumn(edge: String, onStart: () -> Unit, onEnd: () -> Unit, onStep: (Double) -> Unit) {
    Column(Modifier.width(34.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RepeatButton(ClipMiIcons.Play, "Geser $edge maju 0,1 detik", Modifier.weight(1f), onStart, onEnd) { onStep(0.1) }
        RepeatButton(ClipMiIcons.Play, "Geser $edge mundur 0,1 detik", Modifier.weight(1f).rotate(180f), onStart, onEnd) { onStep(-0.1) }
    }
}

/** Tombol yang mengulang aksinya selama ditahan: sekali saat ditekan, lalu makin cepat (400 ms, lalu turun ke 40 ms). */
@Composable
private fun RepeatButton(icon: ImageVector, label: String, modifier: Modifier, onStart: () -> Unit, onEnd: () -> Unit, onStep: () -> Unit) {
    val c = ClipMi.colors
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EDITOR_CARD)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onStart()
                    step()
                    val job = scope.launch {
                        var wait = 400L
                        while (true) {
                            delay(wait)
                            step()
                            wait = maxOf(40L, (wait * 0.75).toLong())
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                    onEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(word: EditorWord, keyword: Boolean, speaking: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ClipMi.colors
    val (bg, fg) = when {
        word.cut -> c.badSoft to c.bad
        speaking -> Color.White to EDITOR_BG
        word.hidden -> Color.Transparent to c.textMuted
        keyword -> c.accentSoft to c.accent
        else -> Color(0xFF26302D) to c.text
    }
    Text(
        word.text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, textDecoration = if (word.cut) TextDecoration.LineThrough else null),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (word.hidden) Modifier.border(1.dp, c.lineStrong, RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PauseChip(pause: Pause, onClick: () -> Unit) {
    val c = ClipMi.colors
    Text(
        "jeda ${seconds(pause.length)} dtk",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, textDecoration = if (pause.removed) TextDecoration.LineThrough else null),
        color = if (pause.removed) c.textMuted else c.warn,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pause.removed) Color.Transparent else c.warnSoft)
            .border(1.dp, if (pause.removed) c.lineStrong else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Opsi satu kata: ubah teks (waktu per kata tetap bila jumlahnya sama), buang, sembunyikan, kata kunci, putar. */
@Composable
private fun WordSheet(state: EditorState, editor: ClipEditor, word: EditorWord, seek: (Double) -> Unit, onDone: () -> Unit) {
    val notifier = LocalNotifier.current
    var text by remember(word) { mutableStateOf(word.text) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyword = Keywords.mark(listOf(word.text), state.snapshot.keywords).isNotEmpty()

    ClipMiSheet(onDone, title = "“${word.text}”", subtitle = "${precise(word.start)} – ${precise(word.end)}") {
        Field(text, { text = it; error = null }, label = "Teks di subtitle", supporting = "Ejaan saja yang berubah; waktu tiap kata tetap.", error = error, singleLine = true)
        PrimaryButton("Simpan teks", modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank() && text != word.text, onClick = {
            when (val result = editor.editWords(state.snapshot.clip, word.start, word.end, text)) {
                is ClipEditor.EditResult.Done -> {
                    state.applyClip { result.clip }
                    onDone()
                }
                is ClipEditor.EditResult.Rejected -> error = result.message
            }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (word.cut) "Kembalikan" else "Buang dari video", modifier = Modifier.weight(1f), onClick = {
                val ok = state.applyClip { if (word.cut) editor.restore(it, word.start, word.end) else editor.cut(it, word.start, word.end) }
                if (!ok && !word.cut) notifier.show("Sisa klip terlalu pendek.")
                onDone()
            })
            SecondaryButton(if (word.hidden) "Tampilkan" else "Sembunyikan", modifier = Modifier.weight(1f), onClick = {
                state.applyClip { if (word.hidden) editor.show(it, word.start, word.end) else editor.hide(it, word.start, word.end) }
                onDone()
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (keyword) "Lepas kata kunci" else "Jadikan kata kunci", modifier = Modifier.weight(1f), onClick = {
                state.toggleKeyword(word.text)
                onDone()
            })
            SecondaryButton("Putar dari sini", modifier = Modifier.weight(1f), onClick = { seek(word.start); onDone() })
        }
        Text(
            "Sembunyikan = kata tetap terdengar tetapi tidak tampil di subtitle (misalnya \"eee\").",
            style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Crop
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun CropPanel(state: EditorState, position: Double, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val spec = state.spec()
    val frame = spec.frame
    val zoom = state.snapshot.cropZoom ?: (frame.zoomFactor() * 100).roundToInt()
    val follow = state.followsSpeaker()
    val needs = state.needsSpeakerDetection()

    // Ikuti pembicara tanpa hasil deteksi untuk rentang ini: dideteksi otomatis begitu tab Crop dibuka.
    LaunchedEffect(follow, needs) {
        if (follow && needs && state.speakerProgress == null && state.speakerError == null) state.detectSpeaker()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("Crop & zoom", frame.name)
        Text("Geser atau cubit video di pratinjau.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        if (!frame.usesCropPosition() && zoom <= 100) {
            Text("Video utuh: posisi crop berlaku saat di-zoom.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        if (frame.usesCropPosition()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ikuti pembicara", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                ClipMiSwitch(follow) { on ->
                    if (on) state.apply { it.copy(cropPosition = null) }
                    else state.apply { it.copy(cropPosition = fixedPosition(state, position)) }
                }
            }
        }
        if (follow) {
            SpeakerSection(state, position, seek)
        } else {
            LabeledSlider("Posisi kiri–kanan", "${spec.effectiveCropPosition()}", spec.effectiveCropPosition().toFloat(), 0f..100f, onChange = { v ->
                state.drag { it.copy(cropPosition = v.roundToInt()) }
            }, onDone = { state.endDrag() })
        }
        LabeledSlider("Posisi atas–bawah", "${spec.effectiveCropY()}", spec.effectiveCropY().toFloat(), 0f..100f, onChange = { v ->
            state.drag { it.copy(cropY = v.roundToInt()) }
        }, onDone = { state.endDrag() })
        LabeledSlider("Zoom", "$zoom%", zoom.toFloat(), ClipSpec.ZOOM_RANGE.first.toFloat()..ClipSpec.ZOOM_RANGE.last.toFloat(), onChange = { v ->
            state.drag { it.copy(cropZoom = v.roundToInt()) }
        }, onDone = { state.endDrag() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Tengahkan", { state.apply { it.copy(cropPosition = 50, cropY = 50) } }, Modifier.weight(1f), height = 40.dp)
            ActionChip("Kembali ke bawaan", {
                state.apply { it.copy(cropPosition = null, cropY = null, cropZoom = null, speakerManual = emptyList()) }
            }, Modifier.weight(1f), height = 40.dp)
        }
    }
}

/** Status deteksi pembicara, daftar adegan, dan posisi manual adegan yang sedang tampil. */
@Composable
private fun SpeakerSection(state: EditorState, position: Double, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val progress = state.speakerProgress
    val error = state.speakerError
    val track = state.speakerTrack()
    val clip = state.snapshot.clip
    when {
        progress != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mendeteksi pembicara… ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = c.textBody)
            ProgressBar(progress)
        }
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deteksi pembicara gagal: $error", style = MaterialTheme.typography.bodySmall, color = c.bad)
            ActionChip("Coba lagi", { state.detectSpeaker() }, height = 40.dp)
        }
        track == null -> ActionChip("Deteksi pembicara", { state.detectSpeaker() }, height = 40.dp)
    }
    if (track == null || progress != null) return

    val shots = track.shots()
    val current = track.shotAt(position - clip.startTime)
    val detected = track.analysis?.samples?.any { it != null } == true
    if (!detected && track.manual.isEmpty()) {
        Text("Pembicara tidak terdeteksi; crop memakai posisi project.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
    if (shots.size > 1) {
        val cuts = state.spec().cutRanges()
        ChipRow(gutter = 0.dp) {
            for (shot in shots) {
                val active = shot.index == current?.index
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) c.accentSoft else c.panel)
                        .border(1.dp, if (active) c.accent else c.line, RoundedCornerShape(12.dp))
                        .clickable { seek(clip.startTime + shot.from + minOf(0.05, (shot.to - shot.from) / 2)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${shot.index + 1}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (active) c.accent else c.text)
                    Text(Timecode.format(ClipCuts.map(cuts, clip.startTime, clip.startTime + shot.from)), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    if (shot.manual != null) Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip("Otomatis lagi", {
            current?.let { shot -> state.apply { it.copy(speakerManual = track.withManual(shot, null).manual) } }
        }, Modifier.weight(1f), height = 40.dp, enabled = current?.manual != null)
        ActionChip("Deteksi ulang", { state.detectSpeaker() }, Modifier.weight(1f), height = 40.dp)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Gaya
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun StylePanel(state: EditorState, onEditStyle: (caption: Boolean, id: Long) -> Unit) {
    val captionId = state.captionStyleId()
    val frameId = state.frameStyleId()
    val fonts = remember { HashMap<String, FontFamily>() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Gaya subtitle")
        ChipRow(gutter = 16.dp) {
            for (style in state.captionStyles) {
                val family = fonts.getOrPut(style.fontFamily) {
                    runCatching {
                        val bytes = readBytes(IosUserFonts.file(state.renderer.fontsDir, style.fontFamily)) ?: error("font")
                        FontFamily(Font("caption-${style.fontFamily}", bytes))
                    }.getOrDefault(FontFamily.Default)
                }
                StyleCard(style.id == captionId, onClick = { state.apply { it.copy(captionStyleId = style.id) } }, onEdit = { onEditStyle(true, style.id) }) {
                    CaptionSample(style, family, style.id == captionId)
                }
            }
            AddStyleCard("Gaya baru") { onEditStyle(true, 0) }
        }
        SectionTitle("Bingkai", Modifier.padding(top = 4.dp))
        ChipRow(gutter = 16.dp) {
            for (frame in state.frameStyles) {
                StyleCard(frame.id == frameId, onClick = { state.apply { it.copy(frameStyleId = frame.id) } }, onEdit = { onEditStyle(false, frame.id) }) {
                    FrameSample(frame, frame.id == frameId)
                }
            }
            AddStyleCard("Bingkai baru") { onEditStyle(false, 0) }
        }
    }
}

@Composable
private fun StyleCard(selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit, content: @Composable () -> Unit) {
    val c = ClipMi.colors
    Box(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(EDITOR_CARD)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        // Ubah gaya ini (sudut kanan atas kartu).
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.35f))
                .clickable(role = Role.Button, onClickLabel = "Ubah gaya", onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) { Icon(ClipMiIcons.Edit, "Ubah gaya", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Kartu di ujung deretan gaya: buat gaya baru. */
@Composable
private fun AddStyleCard(label: String, onClick: () -> Unit) {
    val c = ClipMi.colors
    Column(
        Modifier
            .width(112.dp)
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, c.lineStrong, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(ClipMiIcons.Plus, null, tint = c.text, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun CaptionSample(style: CaptionStyleEntity, family: FontFamily, selected: Boolean) {
    val c = ClipMi.colors
    val sample = if (style.uppercase) "SABAR" else "Sabar"
    Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            val color = hex(if (style.highlightActiveWord && style.highlightStyle == "color") style.highlightColor else style.primaryColor)
            Text(
                sample,
                style = TextStyle(fontFamily = family, fontSize = 18.sp, color = color, shadow = Shadow(hex(style.outlineColor), blurRadius = 4f)),
                modifier = if (style.highlightActiveWord && style.highlightStyle == "box") Modifier.clip(RoundedCornerShape(4.dp)).background(hex(style.highlightColor)).padding(horizontal = 6.dp) else Modifier,
            )
        }
        Text(style.name, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FrameSample(frame: FrameStyleEntity, selected: Boolean) {
    val c = ClipMi.colors
    Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bg = when (frame.background) {
            "color" -> hex(frame.backgroundColor)
            else -> Color(0xFF1E2E29)
        }
        Box(Modifier.size(36.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(if (frame.mode == "crop") Color(0xFF3B5A50) else bg)) {
            when (frame.mode) {
                "fit" -> Box(Modifier.align(Alignment.Center).fillMaxWidth().height(20.dp).background(Color(0xFF3B5A50)))
                "top" -> Box(
                    Modifier.align(if (frame.videoPosition == "bottom") Alignment.BottomCenter else if (frame.videoPosition == "top") Alignment.TopCenter else Alignment.Center)
                        .fillMaxWidth().height((64 * frame.videoHeight / 100).dp).background(Color(0xFF3B5A50)),
                )
            }
        }
        Text(frame.name, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun hex(value: String?): Color = parse(value) ?: Color.White

// ---------------------------------------------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HookPanel(state: EditorState, pending: PendingText) {
    val c = ClipMi.colors
    val layout = state.hookLayout()
    val spec = state.spec()
    val lead = spec.leadSeconds()
    val length = spec.outputDuration()
    fun merge(vararg changes: Pair<String, JsonElement>) = state.apply { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(changes.toMap()))) }
    fun dragMerge(vararg changes: Pair<String, JsonElement>) = state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(changes.toMap()))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelTitle("Hook", if (layout.x != null) "Posisi diatur sendiri" else "Seret di pratinjau untuk memindah")
            AutoSaveField("hook-${state.entity.id}", state.snapshot.hook.orEmpty(), pending, placeholder = "Teks pembuka 3 detik pertama") { text ->
                state.apply { it.copy(hook = text.trim().ifEmpty { null }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tampilkan", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.weight(1f))
                ClipMiSwitch(state.snapshot.showHookOverlay) { on -> state.apply { it.copy(showHookOverlay = on) } }
            }
        }
        Text("Model", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.padding(horizontal = 16.dp))
        ChipRow(gutter = 16.dp) {
            HookLayout.MODELS.forEach { (key, model) -> FilterPill(model.label, selected = layout.model == key, height = 34.dp, onClick = { merge("model" to JsonPrimitive(key)) }) }
        }
        Text("Animasi", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.padding(horizontal = 16.dp))
        ChipRow(gutter = 16.dp) {
            HookLayout.ANIMATIONS.forEach { (key, label) -> FilterPill(label, selected = layout.animation == key, height = 34.dp, onClick = { merge("animation" to JsonPrimitive(key)) }) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledSlider("Ukuran huruf", "${layout.size}", layout.size.toFloat(), HookLayout.SIZE_RANGE.first.toFloat()..HookLayout.SIZE_RANGE.last.toFloat(), onChange = { v ->
                dragMerge("size" to JsonPrimitive(v.roundToInt()))
            }, onDone = { state.endDrag() })
            if (layout.alpha != null) {
                LabeledSlider("Sudut kotak", "${layout.radius}", layout.radius.toFloat(), HookLayout.RADIUS_RANGE.first.toFloat()..HookLayout.RADIUS_RANGE.last.toFloat(), onChange = { v ->
                    dragMerge("radius" to JsonPrimitive(v.roundToInt()))
                }, onDone = { state.endDrag() })
            }
            val min = if (lead > 0) -lead else 0.0
            Row {
                Text("Waktu tampil", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.weight(1f))
                Text("${seconds(layout.start)}–${seconds(layout.end)} dtk", style = MaterialTheme.typography.labelMedium, color = c.text)
            }
            ClipMiRangeSlider(
                value = layout.start.toFloat()..layout.end.toFloat(),
                onValueChange = { r ->
                    dragMerge("start" to JsonPrimitive((r.start * 10).roundToInt() / 10.0), "end" to JsonPrimitive((r.endInclusive * 10).roundToInt() / 10.0))
                },
                valueRange = min.toFloat()..maxOf(min + 0.5, length).toFloat(),
                onValueChangeFinished = { state.endDrag() },
            )
            Toggle("Kartu pembuka", "Hook di atas frame pertama yang dikaburkan, ${seconds(HookLayout.OPENER_SECONDS)} dtk sebelum klip.", layout.opener) { on ->
                merge("opener" to JsonPrimitive(on))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionChip("Posisi bawaan", { merge("x" to JsonNull, "y" to JsonNull) }, Modifier.weight(1f), height = 40.dp, enabled = layout.x != null)
                GhostButton("Reset tampilan", onClick = { state.apply { it.copy(hookLayout = null) } })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Sampul
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tab Sampul (padanan CoverPanel Android): sampul dari frame video atau gambar Photos, judul di atasnya, dan pilihan
 * menempelkannya di awal video. Pratinjau sampul dibuat FFmpeg seperti saat render.
 */
@Composable
private fun CoverPanel(state: EditorState, position: Double, pending: PendingText) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val s = state.snapshot
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var making by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val coverKey = listOf(s.coverTime, s.coverPath, s.coverTitle, s.coverLayout, s.keywords, s.captionStyleId, s.frameStyleId, s.title, s.clip.startTime, s.cropPosition, s.cropZoom, s.cropY)
    LaunchedEffect(coverKey) {
        if (!s.hasCover) {
            image = null
            return@LaunchedEffect
        }
        delay(400)
        making = true
        try {
            val file = state.renderer.cover(state.entitySnapshot(s), state.project, state.captionStyle(s), state.frameStyle(s))
            image = withContext(Dispatchers.Default) { decodeImage(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notifier.show("Sampul gagal dibuat: ${e.message?.take(100)}")
        } finally {
            making = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle(
            "Sampul",
            when {
                s.coverPath != null -> "Gambar dari Photos"
                s.coverTime != null -> "Frame di ${Timecode.format(s.coverTime - s.clip.startTime)}"
                else -> "Belum ada"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(EDITOR_CARD), contentAlignment = Alignment.Center) {
                image?.let { Image(it, contentDescription = "Sampul", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(ClipMiIcons.Image, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
                if (making || picking) ProgressBar(null, Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Frame saat ini", {
                    val time = (position.coerceIn(s.clip.startTime, s.clip.endTime) * 1000).roundToInt() / 1000.0
                    state.apply { it.copy(coverTime = time, coverPath = null) }
                }, Modifier.fillMaxWidth(), height = 40.dp, icon = ClipMiIcons.Film)
                ActionChip("Dari Photos", {
                    val relative = state.coverImagePath()
                    val absolute = state.absolute(relative)
                    createDirectory(absolute.substringBeforeLast('/'))
                    picking = true
                    state.engine.pickImage(
                        absolute,
                        object : PickListener {
                            override fun finished(path: String?) {
                                picking = false
                                if (path != null) state.apply { it.copy(coverPath = relative, coverTime = null) }
                            }
                        },
                    )
                }, Modifier.fillMaxWidth(), height = 40.dp, icon = ClipMiIcons.Image, enabled = !picking)
                if (s.hasCover) {
                    ActionChip(if (saving) "Menyimpan…" else "Simpan ke Photos", {
                        saving = true
                        scope.launch {
                            val error = runCatching { state.renderer.saveCover(state.entitySnapshot(), state.project, state.captionStyle(), state.frameStyle()) }
                                .getOrElse { it.message ?: "gagal" }
                            notifier.show(if (error == null) "Sampul tersimpan di Photos." else "Sampul tidak bisa disimpan: $error")
                            saving = false
                        }
                    }, Modifier.fillMaxWidth(), height = 40.dp, icon = ClipMiIcons.Download, enabled = !saving && !making)
                    GhostButton("Tanpa sampul", danger = true, onClick = { state.apply { it.copy(coverTime = null, coverPath = null) } })
                }
            }
        }
        if (s.hasCover) {
            Toggle("Judul di sampul", null, s.coverTitle) { on -> state.apply { it.copy(coverTitle = on) } }
            if (s.coverTitle) CoverTitleSection(state, pending)
            Toggle("Tempel di awal video", "Untuk platform yang memakai frame pertama.", s.coverInVideo) { on -> state.apply { it.copy(coverInVideo = on) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Judul klip", style = MaterialTheme.typography.labelMedium, color = c.textBody)
            AutoSaveField("title-${state.entity.id}", s.title, pending, placeholder = "Judul klip") { text ->
                text.trim().takeIf { it.isNotEmpty() }?.let { title -> state.apply { it.copy(title = title) } }
            }
        }
    }
}

/** Model judul sampul: Klasik (sama dengan desktop) atau tipografi, teks dengan `*kata*` sebagai sorotan, warna, posisi, ukuran. */
@Composable
private fun CoverTitleSection(state: EditorState, pending: PendingText) {
    val c = ClipMi.colors
    val layout = CoverTitle.parse(state.snapshot.coverLayout)
    fun update(change: (CoverTitleLayout) -> CoverTitleLayout) = state.apply { it.copy(coverLayout = CoverTitle.encode(change(CoverTitle.parse(it.coverLayout)))) }
    val defaultText = ClipText.displayTitle(state.snapshot.title, state.entity.partNumber)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipRow(gutter = 0.dp) {
            CoverTitle.MODELS.forEach { (key, label) -> FilterPill(label, selected = layout.model == key, height = 34.dp, onClick = { update { it.copy(model = key) } }) }
        }
        if (layout.classic) {
            // Judul klasik: hanya posisi tegak (judul selalu di tengah mendatar, sama dengan ClipMI PC).
            LabeledSlider("Posisi tegak", "${layout.y ?: 58}%", (layout.y ?: 58).toFloat(), 5f..95f, onChange = { v ->
                state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(y = v.roundToInt()))) }
            }, onDone = { state.endDrag() })
            return@Column
        }
        AutoSaveField(
            "cover-${state.entity.id}", layout.text ?: defaultText, pending, label = "Teks sampul",
            supporting = "Pilih kata, lalu ketuk ikon stabilo", minLines = 4, maxLines = 8, markup = true,
        ) { text -> update { it.copy(text = text.takeIf { t -> t.isNotBlank() && t.trim() != defaultText }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sorotan", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.width(64.dp))
            val accent = layout.accent ?: CoverTitle.ACCENTS[layout.model]
            CoverTitle.PALETTE.forEach { hexValue ->
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(hex(hexValue))
                        .border(2.dp, if (hexValue.equals(accent, ignoreCase = true)) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { update { it.copy(accent = hexValue) } },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Posisi", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.width(64.dp))
            listOf("top" to "Atas", "center" to "Tengah", "bottom" to "Bawah").forEach { (key, label) ->
                FilterPill(label, selected = layout.position == key && layout.y == null, height = 32.dp, onClick = {
                    update { it.copy(position = if (it.position == key && it.y == null) null else key, y = null) }
                })
            }
        }
        LabeledSlider("Ukuran", "${layout.size}%", layout.size.toFloat(), CoverTitle.SIZE_MIN.toFloat()..CoverTitle.SIZE_MAX.toFloat(), onChange = { v ->
            state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(size = v.roundToInt()))) }
        }, onDone = { state.endDrag() })
        // Posisi bebas: geser judul ke mana saja (persen lebar/tinggi layar).
        LabeledSlider("Posisi mendatar (X)", "${layout.x ?: 50}%", (layout.x ?: 50).toFloat(), 0f..100f, onChange = { v ->
            state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(x = v.roundToInt()))) }
        }, onDone = { state.endDrag() })
        val defaultY = when (layout.position ?: if (layout.model == "kapital" || layout.model == "pop") "bottom" else "center") { "top" -> 22; "bottom" -> 76; else -> 50 }
        LabeledSlider("Posisi tegak (Y)", "${layout.y ?: defaultY}%", (layout.y ?: defaultY).toFloat(), 0f..100f, onChange = { v ->
            state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(y = v.roundToInt()))) }
        }, onDone = { state.endDrag() })
        if (layout.x != null || layout.y != null) {
            GhostButton("Kembalikan posisi bawaan", onClick = { update { it.copy(x = null, y = null) } })
        }
    }
}

/**
 * Jendela render seperti editor video pada umumnya: kartu 9:16 bersudut membulat dengan frame klip, terisi dari bawah
 * mengikuti progres, lalu tanda selesai dengan Bagikan. Render tetap berjalan bila jendela disembunyikan.
 */
@Composable
private fun RenderWindow(state: EditorState, renders: Renders, live: ClipEntity?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val c = ClipMi.colors
    val notifier = LocalNotifier.current
    val clipboard = LocalClipboardManager.current
    val rendering = renders.active == state.entity.id || state.entity.id in renders.queued
    val status = RenderStatus.from(live?.renderStatus)
    val done = !rendering && status == RenderStatus.Done
    val failed = !rendering && status == RenderStatus.Failed
    val fraction = if (renders.active == state.entity.id) renders.fraction.toFloat().coerceIn(0f, 1f) else if (done) 1f else 0f
    val shown by animateFloatAsState(fraction, label = "progres render")
    val frame = remember(state.thumbnail) { decodeImage(state.thumbnail) }

    Dialog(onDismissRequest = { if (!rendering) onDismiss() }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(28.dp)).background(EDITOR_PANEL).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.height(320.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
                frame?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                // Bagian yang belum selesai digelapkan; terang naik dari bawah seiring progres.
                Box(Modifier.fillMaxWidth().fillMaxHeight(1f - shown).background(Color.Black.copy(alpha = 0.62f)))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(shown).border(2.dp, c.accent.copy(alpha = if (done) 0f else 0.9f)))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        done -> Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(ClipMiIcons.Check, null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        failed -> Icon(ClipMiIcons.Alert, null, tint = c.bad, modifier = Modifier.size(48.dp))
                        else -> Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                    }
                }
            }
            Text(
                when {
                    done -> "Render selesai · tersimpan di Foto"
                    failed -> "Render gagal"
                    renders.active != state.entity.id -> "Menunggu giliran render…"
                    else -> "Merender klip…"
                },
                style = MaterialTheme.typography.titleMedium, color = c.text,
            )
            Text(
                when {
                    failed -> live?.renderError ?: "Sebab tidak diketahui."
                    done -> ClipText.displayTitle(live?.title ?: state.entity.title, state.entity.partNumber)
                    else -> "Biarkan aplikasi tetap terbuka. Layar tidak akan terkunci selama render."
                },
                style = MaterialTheme.typography.bodySmall, color = if (failed) c.bad else c.textMuted, textAlign = TextAlign.Center, maxLines = 4,
            )
            if (!done && !failed) ProgressBar(fraction, color = c.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    done -> {
                        SecondaryButton("Tutup", onClick = onDismiss, modifier = Modifier.weight(1f))
                        PrimaryButton("Bagikan", icon = ClipMiIcons.Share, modifier = Modifier.weight(1f), onClick = {
                            val output = live?.outputPath?.let { state.absolute(it) }
                            if (output != null && fileSize(output) > 0) {
                                val clip = live!!
                                clipboard.setText(AnnotatedString(ClipText.shareText(clip.title, clip.partNumber, clip.caption, state.hashtags(clip))))
                                notifier.show("Caption & hashtag disalin. Tempel di aplikasi tujuan.")
                                state.renderer.engine.shareFile(output)
                            } else {
                                notifier.show("File hasil tidak ditemukan.")
                            }
                        })
                    }
                    failed -> {
                        SecondaryButton("Tutup", onClick = onDismiss, modifier = Modifier.weight(1f))
                        PrimaryButton("Coba lagi", modifier = Modifier.weight(1f), onClick = onRetry)
                    }
                    else -> {
                        SecondaryButton("Sembunyikan", onClick = onDismiss, modifier = Modifier.weight(1f))
                        SecondaryButton("Batalkan", onClick = { renders.cancel(); onDismiss() }, modifier = Modifier.weight(1f), contentColor = c.bad)
                    }
                }
            }
        }
    }
}
