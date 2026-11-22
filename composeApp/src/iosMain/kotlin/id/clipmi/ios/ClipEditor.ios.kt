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

private val Danger = Color(0xFFE5484D)
private val Warn = Color(0xFFF5A524)

private val TABS = listOf("Potong", "Hook", "Gaya", "Sampul")

@Composable
actual fun ClipEditorScreen(repo: Repository, renders: Renders, clipId: Long, onBack: () -> Unit) {
    // Scope aplikasi (bukan layar): penyimpanan terakhir tetap selesai walau editor sudah ditutup.
    val scope = renders.scope
    // null = sedang dimuat; Result.failure = tidak bisa dibuka.
    val loaded by produceState<Result<EditorState>?>(null, clipId) {
        value = runCatching { EditorState.load(repo, renders.renderer, scope, clipId) ?: error("Video sumber belum ada. Unduh ulang video project ini.") }
    }
    val c = MaterialTheme.colorScheme
    val result = loaded
    val state = result?.getOrNull()
    if (state == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BackLink(onBack)
            Text(
                result?.exceptionOrNull()?.message ?: "Memuat klip…",
                color = if (result == null) c.onBackground.copy(alpha = 0.6f) else Danger,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        return
    }
    EditorContent(state, renders, onBack)
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
private class PendingText {
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
private fun sourceTime(cuts: List<Range>, start: Double, end: Double, output: Double): Double {
    var left = output
    for ((from, to) in ClipCuts.kept(cuts, start, end)) {
        val length = to - from
        if (left <= length) return from + left
        left -= length
    }
    return end
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun EditorContent(state: EditorState, renders: Renders, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val preview = remember(state.source) { PreviewPlayer(state.source) }
    val pending = remember { PendingText() }
    var position by remember { mutableStateOf(state.snapshot.clip.startTime) }
    var tab by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val notify: (String, Boolean) -> Unit = { text, ok -> notice = text to ok }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

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
            val cut = currentCuts.firstOrNull { now >= it.first && now < it.second }
            when {
                now >= clip.endTime - 0.03 -> {
                    preview.pause()
                    preview.seek(clip.startTime)
                }
                now < clip.startTime - 0.25 -> preview.seek(clip.startTime)
                cut != null -> preview.seek(cut.second)
            }
        }
    }
    val seek: (Double) -> Unit = { preview.seek(it) }

    Column(Modifier.fillMaxSize()) {
        // Pratinjau tetap di atas; isian tab di bawahnya yang bergulir.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackLink(onBack)
                Text(
                    snapshot.title, fontWeight = FontWeight.Bold, color = c.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                SmallButton("Urungkan", enabled = state.canUndo) { state.undo() }
                SmallButton("Ulangi", enabled = state.canRedo) { state.redo() }
            }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val composition = state.composition
                val ratio = composition.canvas.width.toFloat() / composition.canvas.height
                val height = min(maxWidth.value / ratio, 330f)
                Box(Modifier.height(height.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp))) {
                    Preview(state, composition, preview, hookMode = tab == 1)
                }
            }
            val length = spec.outputDuration().coerceAtLeast(0.1)
            val output = ClipCuts.map(cuts, spec.startTime, position.coerceIn(spec.startTime, spec.endTime))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton(if (preview.playing) "Jeda" else "Putar") {
                    if (preview.playing) preview.pause() else {
                        if (position < spec.startTime || position >= spec.endTime - 0.05) preview.seek(spec.startTime)
                        preview.play()
                    }
                }
                Slider(
                    value = (output / length).toFloat().coerceIn(0f, 1f),
                    onValueChange = { preview.seek(sourceTime(cuts, spec.startTime, spec.endTime, it * length)) },
                    modifier = Modifier.weight(1f),
                )
                Text("${Timecode.format(output.coerceAtLeast(0.0))} / ${Timecode.format(length)}", fontSize = 12.sp, color = c.onBackground.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TABS.forEachIndexed { index, label ->
                    val active = index == tab
                    Text(
                        label, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) c.primary else c.onBackground.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (active) c.primary.copy(alpha = 0.16f) else c.onBackground.copy(alpha = 0.05f))
                            .clickable { tab = index }.padding(vertical = 9.dp).wrapContentSize(Alignment.Center),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> TrimTab(state, position, preview.playing, seek, notify)
                1 -> HookTab(state, pending)
                2 -> StyleTab(state)
                else -> CoverTab(state, position, pending, notify)
            }
        }

        // Aksi bawah: render (menyimpan isian yang belum tersimpan dulu) dan progres render.
        Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notice?.let { (text, ok) -> Text(text, fontSize = 13.sp, color = if (ok) c.primary else Danger, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val rendering = renders.active == state.entity.id
            if (rendering) {
                LinearProgressIndicator(progress = { renders.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                    SmallButton("Batal") { renders.cancel() }
                }
            } else {
                val clip by remember(state.entity.id) { state.liveClip() }.collectAsState(state.entity)
                Button(
                    onClick = {
                        pending.commitAll()
                        scope.launch {
                            state.flush()
                            preview.pause()
                            notice = null
                            renders.start(state.entity.id)
                        }
                    },
                    enabled = renders.active == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary),
                ) { Text(if (renders.active != null) "Menunggu render lain…" else "Render") }
                val latest = clip ?: state.entity
                when (RenderStatus.from(latest.renderStatus)) {
                    RenderStatus.Done -> Text(
                        if (latest.changedAfterRender) "Diubah setelah render terakhir · render ulang untuk memperbarui" else "Sudah dirender · tersimpan di Photos",
                        fontSize = 12.sp, color = if (latest.changedAfterRender) Warn else c.primary,
                    )
                    RenderStatus.Failed -> latest.renderError?.let { Text(it, fontSize = 12.sp, color = Danger, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    else -> Text("Perubahan tersimpan otomatis.", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Komponen kecil
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, color = c.onSurface, modifier = Modifier.weight(1f))
            hint?.let { Text(it, fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f)) }
        }
        content()
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
}

/** Tombol aksi kotak membulat 12dp (bukan pil). */
@Composable
private fun Action(label: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val color = if (danger) Danger else c.primary
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (enabled) color else c.onSurface.copy(alpha = 0.35f),
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(if (enabled) color.copy(alpha = 0.12f) else c.onSurface.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, name) in options) {
            val active = key == selected
            Text(
                name, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) c.primary else c.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (active) c.primary.copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.06f))
                    .clickable { onSelect(key) }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.onSurface)
            subtitle?.let { Text(it, fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f)) }
        }
        Switch(checked, onChange)
    }
}

/**
 * Kolom teks yang tersimpan sendiri 0,8 detik setelah berhenti mengetik ([onSave] menerima teks apa adanya). Nilai
 * dari luar (urungkan) menggantikan isi kolom.
 */
@Composable
private fun AutoSaveField(key: String, value: String, label: String, pending: PendingText, maxLines: Int = 3, onSave: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(value) }
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(value) { if (value != text.trim()) text = value }
    // Keluar dari tab/editor sebelum jeda selesai: teks tetap tersimpan.
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
    }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(label) }, maxLines = maxLines)
}

/** Angka satu desimal dengan koma, misalnya 2,5. */
private fun oneDecimal(value: Double): String {
    val tenths = (abs(value) * 10).roundToInt()
    return (if (value < 0 && tenths > 0) "−" else "") + "${tenths / 10},${tenths % 10}"
}

/** Waktu dengan satu desimal, misalnya 01:23,4. */
private fun fineTime(seconds: Double): String = "${Timecode.format(seconds)},${((seconds * 10).roundToInt() % 10 + 10) % 10}"

private fun parse(hex: String?): Color? =
    hex?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

private fun decodeImage(path: String?): ImageBitmap? =
    path?.let { readBytes(it) }?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }

// ---------------------------------------------------------------------------------------------------------------
// Tab Potong
// ---------------------------------------------------------------------------------------------------------------

/**
 * Rentang klip dan isinya (padanan TrimPanel Android): geser mulai/selesai, aksi cepat, lalu kata per baris subtitle.
 * Ketuk kata = buang/kembalikan; tekan lama = ubah teks, sembunyikan, kata kunci. Jeda dari suara bisa dibuang.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrimTab(state: EditorState, position: Double, playing: Boolean, seek: (Double) -> Unit, notify: (String, Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val snapshot = state.snapshot
    val clip = snapshot.clip
    val editor = remember(snapshot.captionStyleId) { state.editor() }
    val spec = remember(snapshot) { state.spec() }
    val cuts = remember(spec) { spec.cutRanges() }
    val lines = remember(snapshot) { editor.lines(clip) }
    val clipWords = remember(snapshot) { editor.words(clip) }
    val pauses = remember(snapshot, state.silences) { state.silences?.let { editor.pauses(clip, it) }.orEmpty() }
    var chosen by remember { mutableStateOf<EditorWord?>(null) }
    LaunchedEffect(clip.startTime, clip.endTime) { state.detectSilences() }
    val tooShort = "Sisa klip terlalu pendek (minimal ${oneDecimal(ClipSpec.MIN_DURATION)} detik)."

    var offset = 0
    val ranges = lines.map { line -> (offset until offset + line.words.orEmpty().size).also { offset += line.words.orEmpty().size } }
    val active = lines.indexOfLast { it.start <= position + 0.05 }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    // Kalimat yang sedang terdengar digulir ke atas daftar saat video berjalan.
    LaunchedEffect(active, playing) {
        if (playing && !listState.isScrollInProgress && lines.isNotEmpty()) listState.animateScrollToItem(active + HEADER_ITEMS)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Section("Rentang klip", "hasil ${Timecode.format(spec.outputDuration())}" + if (cuts.isNotEmpty()) " · ${cuts.size} dibuang" else "") {
                TimeNudge("Mulai", clip.startTime) { delta ->
                    state.applyClip { editor.nudge(it, "start", delta) }
                    seek(state.snapshot.clip.startTime)
                }
                TimeNudge("Selesai", clip.endTime) { delta ->
                    state.applyClip { editor.nudge(it, "end", delta) }
                    seek(max(state.snapshot.clip.startTime, state.snapshot.clip.endTime - 1.5))
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Mulai di sini", enabled = position < clip.endTime - ClipSpec.MIN_DURATION) { state.applyClip { editor.setRange(it, position, it.endTime) } }
                Action("Selesai di sini", enabled = position > clip.startTime + ClipSpec.MIN_DURATION) { state.applyClip { editor.setRange(it, it.startTime, position) } }
                val longPauses = pauses.filter { !it.removed && it.length >= 0.6 }
                if (longPauses.isNotEmpty()) {
                    Action("Buang ${longPauses.size} jeda") {
                        if (!state.applyClip { editor.cleanPauses(it, state.silences.orEmpty(), 0.6) }) notify(tooShort, false)
                    }
                }
                Action("Tempel ke batas kata") { state.applyClip { editor.snapToWords(it) } }
                Action("+ Kalimat sebelum", enabled = editor.neighbour(clip, "before") != null) { state.applyClip { editor.extend(it, "before") } }
                Action("+ Kalimat sesudah", enabled = editor.neighbour(clip, "after") != null) { state.applyClip { editor.extend(it, "after") } }
            }
        }
        item {
            val hint = when {
                state.silenceError != null -> "Jeda tidak terbaca: ${state.silenceError}"
                state.silences == null -> "Mencari jeda di suara klip…"
                else -> "Ketuk kata untuk membuang · tekan lama untuk ubah teks atau sembunyikan."
            }
            Text(hint, fontSize = 12.sp, color = if (state.silenceError != null) Danger else c.onBackground.copy(alpha = 0.55f))
        }
        itemsIndexed(lines) { index, line ->
            val range = ranges[index]
            val marked = Keywords.mark(line.words.orEmpty().map { it.text }, snapshot.keywords)
            val current = index == active && position >= clip.startTime && position <= clip.endTime
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(1.dp, if (current) c.primary.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    Timecode.format(ClipCuts.map(cuts, spec.startTime, line.start)),
                    fontSize = 12.sp,
                    color = if (current) c.primary else c.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { seek(line.start) }.padding(end = 6.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val togglePause: (Pause) -> Unit = { pause -> if (!state.applyClip { editor.togglePause(it, pause) }) notify(tooShort, false) }
                    if (index == 0) pauses.filter { it.after < range.first }.forEach { pause -> PauseChip(pause) { togglePause(pause) } }
                    for ((k, i) in range.withIndex()) {
                        val word = clipWords.getOrNull(i) ?: continue
                        WordChip(
                            word, keyword = k in marked, speaking = current && position >= word.start && position < word.end,
                            onClick = {
                                val ok = state.applyClip { if (word.cut) editor.restore(it, word.start, word.end) else editor.cut(it, word.start, word.end) }
                                if (!ok && !word.cut) notify(tooShort, false)
                            },
                            onLongClick = { chosen = word },
                        )
                        pauses.filter { it.after == i }.forEach { pause -> PauseChip(pause) { togglePause(pause) } }
                    }
                }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }

    chosen?.let { word -> WordDialog(state, editor, word, seek, notify) { chosen = null } }
}

/** Jumlah item di atas daftar baris (rentang, aksi, petunjuk). */
private const val HEADER_ITEMS = 3

@Composable
private fun TimeNudge(label: String, value: Double, onNudge: (Double) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(76.dp)) {
            Text(label, fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.6f))
            Text(fineTime(value), fontWeight = FontWeight.SemiBold, color = c.onSurface)
        }
        for ((text, delta) in listOf("−1" to -1.0, "−0,1" to -0.1, "+0,1" to 0.1, "+1" to 1.0)) {
            SmallButton(text) { onNudge(delta) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(word: EditorWord, keyword: Boolean, speaking: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        word.cut -> Danger.copy(alpha = 0.16f) to Danger
        speaking -> c.onSurface to c.surface
        word.hidden -> Color.Transparent to c.onSurface.copy(alpha = 0.5f)
        keyword -> c.primary.copy(alpha = 0.18f) to c.primary
        else -> c.onSurface.copy(alpha = 0.07f) to c.onSurface
    }
    Text(
        word.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg,
        textDecoration = if (word.cut) TextDecoration.LineThrough else null,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (word.hidden) Modifier.border(1.dp, c.onSurface.copy(alpha = 0.25f), RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PauseChip(pause: Pause, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Text(
        "jeda ${oneDecimal(pause.length)} dtk", fontSize = 13.sp, fontWeight = FontWeight.Bold,
        textDecoration = if (pause.removed) TextDecoration.LineThrough else null,
        color = if (pause.removed) c.onSurface.copy(alpha = 0.45f) else Warn,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pause.removed) Color.Transparent else Warn.copy(alpha = 0.14f))
            .border(1.dp, if (pause.removed) c.onSurface.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Opsi satu kata: ubah teks (waktu per kata tetap bila jumlahnya sama), buang, sembunyikan, kata kunci, putar. */
@Composable
private fun WordDialog(state: EditorState, editor: ClipEditor, word: EditorWord, seek: (Double) -> Unit, notify: (String, Boolean) -> Unit, onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var text by remember(word) { mutableStateOf(word.text) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyword = Keywords.mark(listOf(word.text), state.snapshot.keywords).isNotEmpty()
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("“${word.text}”", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.onSurface)
            Text("${fineTime(word.start)} – ${fineTime(word.end)}", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f))
            OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Teks di subtitle") }, singleLine = true, isError = error != null)
            Text(error ?: "Ejaan saja yang berubah; waktu tiap kata tetap.", fontSize = 12.sp, color = if (error != null) Danger else c.onSurface.copy(alpha = 0.55f))
            Button(
                onClick = {
                    when (val result = editor.editWords(state.snapshot.clip, word.start, word.end, text)) {
                        is ClipEditor.EditResult.Done -> {
                            state.applyClip { result.clip }
                            onDone()
                        }
                        is ClipEditor.EditResult.Rejected -> error = result.message
                    }
                },
                enabled = text.isNotBlank() && text != word.text, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) { Text("Simpan teks") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (word.cut) "Kembalikan" else "Buang dari video", danger = !word.cut, modifier = Modifier.weight(1f)) {
                    val ok = state.applyClip { if (word.cut) editor.restore(it, word.start, word.end) else editor.cut(it, word.start, word.end) }
                    if (!ok && !word.cut) notify("Sisa klip terlalu pendek.", false)
                    onDone()
                }
                Action(if (word.hidden) "Tampilkan" else "Sembunyikan", modifier = Modifier.weight(1f)) {
                    state.applyClip { if (word.hidden) editor.show(it, word.start, word.end) else editor.hide(it, word.start, word.end) }
                    onDone()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (keyword) "Lepas kata kunci" else "Kata kunci", modifier = Modifier.weight(1f)) {
                    state.toggleKeyword(word.text)
                    onDone()
                }
                Action("Putar dari sini", modifier = Modifier.weight(1f)) {
                    seek(word.start)
                    onDone()
                }
            }
            Text(
                "Sembunyikan = kata tetap terdengar tetapi tidak tampil di subtitle (misalnya \"eee\").",
                fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tab Hook
// ---------------------------------------------------------------------------------------------------------------

/** Teks dan tampilan hook (padanan HookPanel Android); posisi diatur dengan menyeret di pratinjau. */
@Composable
private fun HookTab(state: EditorState, pending: PendingText) {
    val layout = state.hookLayout()
    val spec = state.spec()
    val lead = spec.leadSeconds()
    val length = spec.outputDuration()
    fun merge(vararg changes: Pair<String, JsonElement>) = state.apply { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(changes.toMap()))) }
    fun dragMerge(vararg changes: Pair<String, JsonElement>) = state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(changes.toMap()))) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("Hook", if (layout.x != null) "Posisi diatur sendiri" else "Seret di pratinjau untuk memindah") {
                AutoSaveField("hook-${state.entity.id}", state.snapshot.hook.orEmpty(), "Teks pembuka di awal video", pending) { text ->
                    state.apply { it.copy(hook = text.trim().ifEmpty { null }) }
                }
                ToggleRow("Tampilkan di video", checked = state.snapshot.showHookOverlay) { on -> state.apply { it.copy(showHookOverlay = on) } }
            }
        }
        item {
            Section("Tampilan") {
                Text("Model", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                ChoiceRow(HookLayout.MODELS.map { (key, model) -> key to model.label }, layout.model) { merge("model" to JsonPrimitive(it)) }
                Text("Animasi", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                ChoiceRow(HookLayout.ANIMATIONS.map { (key, label) -> key to label }, layout.animation) { merge("animation" to JsonPrimitive(it)) }
                LabeledSlider("Ukuran huruf", "${layout.size}", layout.size.toFloat(), HookLayout.SIZE_RANGE.first.toFloat()..HookLayout.SIZE_RANGE.last.toFloat(), onChange = { v ->
                    dragMerge("size" to JsonPrimitive(v.roundToInt()))
                }, onDone = { state.endDrag() })
                if (layout.alpha != null) {
                    LabeledSlider("Sudut kotak", "${layout.radius}", layout.radius.toFloat(), HookLayout.RADIUS_RANGE.first.toFloat()..HookLayout.RADIUS_RANGE.last.toFloat(), onChange = { v ->
                        dragMerge("radius" to JsonPrimitive(v.roundToInt()))
                    }, onDone = { state.endDrag() })
                }
            }
        }
        item {
            Section("Waktu tampil", "${oneDecimal(layout.start)}–${oneDecimal(layout.end)} dtk") {
                val min = if (lead > 0) -lead else 0.0
                RangeSlider(
                    value = layout.start.toFloat()..layout.end.toFloat(),
                    onValueChange = { r ->
                        dragMerge(
                            "start" to JsonPrimitive((r.start * 10).roundToInt() / 10.0),
                            "end" to JsonPrimitive((r.endInclusive * 10).roundToInt() / 10.0),
                        )
                    },
                    valueRange = min.toFloat()..maxOf(min + 0.5, length).toFloat(),
                    onValueChangeFinished = { state.endDrag() },
                )
                ToggleRow(
                    "Kartu pembuka", "Hook di atas frame pertama yang dikaburkan, ${oneDecimal(HookLayout.OPENER_SECONDS)} dtk sebelum klip.", layout.opener,
                ) { on -> merge("opener" to JsonPrimitive(on)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Posisi bawaan", enabled = layout.x != null, modifier = Modifier.weight(1f)) { merge("x" to JsonNull, "y" to JsonNull) }
                    Action("Reset tampilan", enabled = state.snapshot.hookLayout != null, modifier = Modifier.weight(1f)) { state.apply { it.copy(hookLayout = null) } }
                }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column {
        Row {
            Text(label, fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = c.onSurface)
        }
        Slider(current, onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tab Gaya
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun StyleTab(state: EditorState) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("Gaya subtitle") {
                ChoiceRow(state.captionStyles.map { it.id.toString() to it.name }, state.captionStyleId()?.toString()) { id ->
                    state.apply { it.copy(captionStyleId = id.toLong()) }
                }
            }
        }
        item {
            Section("Bingkai") {
                ChoiceRow(state.frameStyles.map { it.id.toString() to it.name }, state.frameStyleId()?.toString()) { id ->
                    state.apply { it.copy(frameStyleId = id.toLong()) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tab Sampul
// ---------------------------------------------------------------------------------------------------------------

/**
 * Judul klip dan sampul (padanan CoverPanel Android): frame video atau gambar dari Photos, judul di atasnya, dan
 * pilihan menempelkannya di awal video. Pratinjau sampul dibuat FFmpeg seperti saat render.
 */
@Composable
private fun CoverTab(state: EditorState, position: Double, pending: PendingText, notify: (String, Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s = state.snapshot
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var making by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val coverKey = listOf(s.coverTime, s.coverPath, s.coverTitle, s.coverLayout, s.keywords, s.captionStyleId, s.frameStyleId, s.title, s.clip.startTime)
    LaunchedEffect(coverKey) {
        if (!s.hasCover) {
            image = null
            return@LaunchedEffect
        }
        delay(400)
        making = true
        try {
            image = decodeImage(state.renderer.cover(state.entitySnapshot(s), state.project, state.captionStyle(s), state.frameStyle(s)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify("Sampul gagal dibuat: ${e.message?.take(120)}", false)
        } finally {
            making = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("Judul klip") {
                AutoSaveField("title-${state.entity.id}", s.title, "Judul", pending) { text ->
                    text.trim().takeIf { it.isNotEmpty() }?.let { title -> state.apply { it.copy(title = title) } }
                }
            }
        }
        item {
            Section(
                "Sampul",
                when {
                    s.coverPath != null -> "Gambar dari Photos"
                    s.coverTime != null -> "Frame di ${Timecode.format(s.coverTime - s.clip.startTime)}"
                    else -> "Belum ada"
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.width(96.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(c.onSurface.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        image?.let { Image(it, contentDescription = "Sampul", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: Text("Tanpa sampul", fontSize = 11.sp, color = c.onSurface.copy(alpha = 0.5f))
                        if (making || picking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("Frame saat ini", modifier = Modifier.fillMaxWidth()) {
                            val time = (position.coerceIn(s.clip.startTime, s.clip.endTime) * 1000).roundToInt() / 1000.0
                            state.apply { it.copy(coverTime = time, coverPath = null) }
                        }
                        Action("Dari Photos", enabled = !picking, modifier = Modifier.fillMaxWidth()) {
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
                        }
                        if (s.hasCover) {
                            Action(if (saving) "Menyimpan…" else "Simpan ke Photos", enabled = !saving && !making, modifier = Modifier.fillMaxWidth()) {
                                saving = true
                                scope.launch {
                                    val error = runCatching { state.renderer.saveCover(state.entitySnapshot(), state.project, state.captionStyle(), state.frameStyle()) }
                                        .getOrElse { it.message ?: "gagal" }
                                    notify(if (error == null) "Sampul tersimpan di Photos." else "Sampul tidak bisa disimpan: $error", error == null)
                                    saving = false
                                }
                            }
                            Action("Tanpa sampul", danger = true, modifier = Modifier.fillMaxWidth()) { state.apply { it.copy(coverTime = null, coverPath = null) } }
                        }
                    }
                }
                if (s.hasCover) {
                    ToggleRow("Tempel di awal video", "Untuk platform yang memakai frame pertama.", s.coverInVideo) { on -> state.apply { it.copy(coverInVideo = on) } }
                    ToggleRow("Judul di sampul", checked = s.coverTitle) { on -> state.apply { it.copy(coverTitle = on) } }
                }
            }
        }
        if (s.hasCover && s.coverTitle) {
            item { CoverTitleSection(state, pending) }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

/** Model judul sampul: Klasik (sama dengan desktop) atau tipografi, teks dengan `*kata*` sebagai sorotan, warna, posisi, ukuran. */
@Composable
private fun CoverTitleSection(state: EditorState, pending: PendingText) {
    val c = MaterialTheme.colorScheme
    val layout = CoverTitle.parse(state.snapshot.coverLayout)
    fun update(change: (CoverTitleLayout) -> CoverTitleLayout) = state.apply { it.copy(coverLayout = CoverTitle.encode(change(CoverTitle.parse(it.coverLayout)))) }
    val defaultText = ClipText.displayTitle(state.snapshot.title, state.entity.partNumber)

    Section("Judul di sampul") {
        ChoiceRow(CoverTitle.MODELS.map { (key, label) -> key to label }, layout.model) { key -> update { it.copy(model = key) } }
        if (!layout.classic) {
            AutoSaveField("cover-${state.entity.id}", layout.text ?: defaultText, "Teks sampul (*kata* = sorotan)", pending, maxLines = 4) { text ->
                update { it.copy(text = text.takeIf { t -> t.isNotBlank() && t.trim() != defaultText }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sorotan", fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(64.dp))
                val accent = layout.accent ?: CoverTitle.ACCENTS[layout.model]
                CoverTitle.PALETTE.forEach { hex ->
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(parse(hex) ?: Color.White)
                            .border(2.dp, if (hex.equals(accent, ignoreCase = true)) c.primary else c.onSurface.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .clickable { update { it.copy(accent = hex) } },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Posisi", fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(64.dp))
                ChoiceRow(listOf("top" to "Atas", "center" to "Tengah", "bottom" to "Bawah"), layout.position) { key ->
                    update { it.copy(position = if (it.position == key) null else key) }
                }
            }
            LabeledSlider("Ukuran", "${layout.size}%", layout.size.toFloat(), CoverTitle.SIZE_MIN.toFloat()..CoverTitle.SIZE_MAX.toFloat(), onChange = { v ->
                state.drag { it.copy(coverLayout = CoverTitle.encode(CoverTitle.parse(it.coverLayout).copy(size = v.roundToInt()))) }
            }, onDone = { state.endDrag() })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Pratinjau
// ---------------------------------------------------------------------------------------------------------------

/**
 * Pratinjau sebesar layar hasil: latar, video sesuai [Composition], lalu subtitle dan hook dari libass. Di tab Hook,
 * hook selalu terlihat (saat video berhenti) dan bisa diseret ke posisi baru (titik tengahnya, persen layar).
 */
@Composable
private fun Preview(state: EditorState, composition: Composition, preview: PreviewPlayer, hookMode: Boolean) {
    val layout = if (hookMode) state.hookLayout() else null
    val lead = if (hookMode) state.spec().leadSeconds() else 0.0
    val hookTime = if (layout != null && !preview.playing) layout.start + lead + minOf(0.6, (layout.end - layout.start) / 2) else null
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (hookMode) Modifier.pointerInput(Unit) {
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
            } else Modifier,
        ),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = widthPx / composition.canvas.width
        Background(state, composition)
        VideoLayer(composition, state.sourceSize, scale, preview)
        AssOverlay(state, composition, preview, hookTime)
    }
}

/** Latar: blur dari frame video (diperbesar dan dikaburkan), warna, atau gradasi; crop penuh tanpa latar. */
@Composable
private fun Background(state: EditorState, composition: Composition) {
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
private fun AssOverlay(state: EditorState, composition: Composition, preview: PreviewPlayer, hookTime: Double?) {
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
