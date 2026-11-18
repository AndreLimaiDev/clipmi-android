package id.clipmi.android.ui.editor

import id.clipmi.android.ui.components.ClipMiRangeSlider
import id.clipmi.android.ui.components.ClipMiSlider
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import id.clipmi.core.render.SpeakerTrack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import id.clipmi.android.ui.theme.ClipMiIcons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.render.ClipCuts
import id.clipmi.core.render.ClipEditor
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.EditorWord
import id.clipmi.core.render.Fonts
import id.clipmi.core.render.HookLayout
import id.clipmi.core.render.Pause
import id.clipmi.core.transcript.Timecode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Waktu dengan persepuluhan detik, misalnya "26:27,4". */
private fun precise(seconds: Double): String = Timecode.format(seconds) + "," + ((seconds * 10).roundToInt() % 10)

private fun seconds(value: Double) = String.format(Locale.forLanguageTag("id"), "%.1f", value)

@Composable
internal fun PanelTitle(title: String, hint: String? = null) {
    val c = ClipMi.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.text, modifier = Modifier.weight(1f))
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Potong: timeline, ujung mulai/selesai, dan teks yang berjalan mengikuti video
// ---------------------------------------------------------------------------------------------------------------

/**
 * Panel Potong (gabungan potong dan teks): timeline selebar layar (ketuk untuk lompat, tahan-geser untuk menelusuri)
 * dengan tombol ◀ ▶ mulai di kiri dan selesai di kanan (ketuk = 0,1 dtk, tahan = terus bergeser makin cepat). Di
 * bawahnya teks klip yang mengikuti video: kalimat aktif disorot dan selalu terlihat; ketuk kata untuk buang/kembalikan,
 * tahan untuk opsi. Kartu intro di awal dan outro di akhir daftar.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TrimPanel(state: EditorState, position: Double, playing: Boolean, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val notifier = LocalNotifier.current
    val clip = state.snapshot.clip
    val editor = state.editor()
    val limit = state.projectDuration ?: state.segments.maxOfOrNull { it.end } ?: clip.endTime
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
    val spec = state.spec()
    val cuts = spec.cutRanges()
    val lines = remember(state.snapshot, state.captionStyles) { editor.lines(clip) }
    val clipWords = remember(state.snapshot, state.captionStyles) { editor.words(clip) }
    val pauses = remember(state.snapshot, state.silences, state.captionStyles) { state.silences?.let { editor.pauses(clip, it) }.orEmpty() }
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
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(window) { detectTapGestures { offset -> seek(from + (to - from) * (offset.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(window, "scrub") {
                        detectDragGestures { change, _ -> seek(from + (to - from) * (change.position.x / size.width).coerceIn(0f, 1f)) }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(t: Double) = ((t - from) / (to - from) * w).toFloat()
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
                // Rentang klip: latar aksen tipis dan garis tegak di kedua ujung.
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
            ActionChip("Mulai di sini", height = 34.dp, enabled = position < clip.endTime - ClipSpec.MIN_DURATION, onClick = {
                state.applyClip { editor.setRange(it, position, it.endTime) }
            })
            ActionChip("Selesai di sini", height = 34.dp, enabled = position > clip.startTime + ClipSpec.MIN_DURATION, onClick = {
                state.applyClip { editor.setRange(it, it.startTime, position) }
            })
            val longPauses = pauses.filter { !it.removed && it.length >= 0.6 }
            if (longPauses.isNotEmpty()) {
                ActionChip("Buang ${longPauses.size} jeda", height = 34.dp, onClick = {
                    if (!state.applyClip { editor.cleanPauses(it, state.silences.orEmpty(), 0.6) }) notifier.show(tooShort)
                })
            }
            ActionChip("Tempel ke batas kata", height = 34.dp, onClick = { state.applyClip { editor.snapToWords(it) } })
            ActionChip("+ Kalimat sebelum", height = 34.dp, enabled = editor.neighbour(clip, "before") != null, onClick = { state.applyClip { editor.extend(it, "before") } })
            ActionChip("+ Kalimat sesudah", height = 34.dp, enabled = editor.neighbour(clip, "after") != null, onClick = { state.applyClip { editor.extend(it, "after") } })
        }

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
            item(key = "intro") { BumperCard("Intro", state.bumpers.firstOrNull { it.id == state.snapshot.introId }?.name) { bumper = "intro" } }
            itemsIndexed(lines) { index, line ->
                val range = ranges[index]
                val marked = Keywords.mark(line.words.orEmpty().map { it.text }, state.snapshot.keywords)
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
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Putar dari sini") { seek(line.start) }.padding(end = 6.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index == 0) pauses.filter { it.after < range.first }.forEach { PauseChip(it) { if (!state.applyClip { c2 -> editor.togglePause(c2, it) }) notifier.show(tooShort) } }
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
                            pauses.filter { it.after == i }.forEach { pause ->
                                PauseChip(pause) { if (!state.applyClip { c2 -> editor.togglePause(c2, pause) }) notifier.show(tooShort) }
                            }
                        }
                    }
                }
            }
            item(key = "outro") { BumperCard("Outro", state.bumpers.firstOrNull { it.id == state.snapshot.outroId }?.name) { bumper = "outro" } }
        }
    }

    chosen?.let { word -> WordSheet(state, word, seek, onDone = { chosen = null }) }
    bumper?.let { kind -> BumperSheet(state, kind, onDone = { bumper = null }) }
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
private fun RepeatButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onStart: () -> Unit, onEnd: () -> Unit, onStep: () -> Unit) {
    val c = ClipMi.colors
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EDITOR_CARD)
            .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onStart()
                    step()
                    val job = scope.launch {
                        var wait = 400L
                        while (true) {
                            kotlinx.coroutines.delay(wait)
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

/** Kartu intro/outro di awal/akhir teks klip; ketuk untuk memilih video dari pustaka. */
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                FilterPill("${b.name} · ${Math.round(b.duration)} dtk", selected = selected == b.id, height = 40.dp, onClick = { choose(b.id); onDone() })
            }
        }
        if (kind == "intro" && s.introId != null && state.spec().leadKind() == "intro" && state.hookLayout().opener) {
            Text("Dengan intro, kartu pembuka hook tidak dipakai.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        if (options.isNotEmpty()) {
            val isProjectDefault = state.project.introId == s.introId && state.project.outroId == s.outroId
            GhostButton(if (isProjectDefault) "Sudah jadi bawaan project" else "Jadikan bawaan project", enabled = !isProjectDefault, onClick = {
                scope.launch {
                    state.container.database.projects().setBumpers(state.project.id, s.introId, s.outroId)
                    state.project = state.project.copy(introId = s.introId, outroId = s.outroId)
                    notifier.show("Klip baru di project ini memakai intro/outro yang sama.")
                }
            })
        }
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
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (word.cut) TextDecoration.LineThrough else null,
        ),
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
private fun WordSheet(state: EditorState, word: EditorWord, seek: (Double) -> Unit, onDone: () -> Unit) {
    val notifier = LocalNotifier.current
    val editor = state.editor()
    var text by remember(word) { mutableStateOf(word.text) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyword = Keywords.mark(listOf(word.text), state.snapshot.keywords).isNotEmpty()

    ClipMiSheet(onDone, title = "“${word.text}”", subtitle = "${precise(word.start)} – ${precise(word.end)}") {
        Field(text, { text = it; error = null }, label = "Teks di subtitle", supporting = "Ejaan saja yang berubah; waktu tiap kata tetap.", error = error, singleLine = true)
        PrimaryButton("Simpan teks", modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank() && text != word.text, onClick = {
            when (val result = editor.editWords(state.snapshot.clip, word.start, word.end, text)) {
                is ClipEditor.EditResult.Done -> { state.applyClip { result.clip }; onDone() }
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
            style = MaterialTheme.typography.bodySmall,
            color = ClipMi.colors.textMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Crop
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun CropPanel(state: EditorState, position: Double, seek: (Double) -> Unit) {
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
            ActionChip("Tengahkan", modifier = Modifier.weight(1f), height = 40.dp, onClick = { state.apply { it.copy(cropPosition = 50, cropY = 50) } })
            ActionChip("Kembali ke bawaan", modifier = Modifier.weight(1f), height = 40.dp, onClick = {
                state.apply { it.copy(cropPosition = null, cropY = null, cropZoom = null, speakerManual = emptyList()) }
            })
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
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.panel,
                gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deteksi pembicara gagal: $error", style = MaterialTheme.typography.bodySmall, color = c.bad)
            ActionChip("Coba lagi", height = 40.dp, onClick = { state.detectSpeaker() })
        }
        track == null -> ActionChip("Deteksi pembicara", height = 40.dp, onClick = { state.detectSpeaker() })
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
        // Kartu adegan yang sedang tampil selalu terlihat: daftar ikut bergeser saat video berjalan.
        val listState = rememberLazyListState()
        LaunchedEffect(current?.index) {
            val index = current?.index ?: return@LaunchedEffect
            if (!listState.isScrollInProgress) listState.animateScrollToItem(maxOf(0, index - 1))
        }
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shots, key = { it.index }) { shot ->
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
                    // Posisi diatur manual: titik kecil.
                    if (shot.manual != null) Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip("Otomatis lagi", modifier = Modifier.weight(1f), height = 40.dp, enabled = current?.manual != null, onClick = {
            current?.let { shot -> state.apply { it.copy(speakerManual = track.withManual(shot, null).manual) } }
        })
        ActionChip("Deteksi ulang", modifier = Modifier.weight(1f), height = 40.dp, onClick = { state.detectSpeaker() })
    }
}

/**
 * Posisi crop tetap (0–100) yang menampilkan bagian video yang sama dengan jalur pembicara saat ini, supaya mematikan
 * "Ikuti pembicara" tidak membuat crop meloncat.
 */
private fun fixedPosition(state: EditorState, position: Double): Int {
    val spec = state.spec()
    val keyframes = spec.speakerKeyframes ?: return spec.effectiveCropPosition()
    val center = SpeakerTrack.positionAt(keyframes, position - state.snapshot.clip.startTime)
    val (sw, sh) = state.sourceSize ?: return (center * 100).roundToInt().coerceIn(0, 100)
    val composition = state.composition()
    val rect = composition.video.rect ?: return (center * 100).roundToInt().coerceIn(0, 100)
    val shown = maxOf(rect.width.toDouble() / sw, rect.height.toDouble() / sh) * composition.video.zoom
    val dw = sw * shown
    if (dw <= rect.width) return 50
    return ((center * dw - rect.width / 2.0) / (dw - rect.width) * 100).roundToInt().coerceIn(0, 100)
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

// ---------------------------------------------------------------------------------------------------------------
// Gaya
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun StylePanel(state: EditorState) {
    val c = ClipMi.colors
    val captionId = state.snapshot.captionStyleId ?: state.captionStyles.firstOrNull { it.isDefault }?.id
    val frameId = state.snapshot.frameStyleId ?: state.frameStyles.firstOrNull { it.isDefault }?.id
    val fonts = remember { HashMap<String, FontFamily>() }
    // Sheet edit gaya langsung di editor ("caption" atau "frame"); pratinjau di atas memakai isiannya.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    editing?.let { kind ->
        StyleEditPanel(state, kind, onClose = { editing = null })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Gaya subtitle")
        ChipRow(gutter = 16.dp) {
            for (style in state.captionStyles) {
                val family = fonts.getOrPut(style.fontFamily) {
                    runCatching { FontFamily(Font(File(state.fontsDir(), Fonts.file(style.fontFamily)))) }.getOrDefault(FontFamily.Default)
                }
                val selected = style.id == captionId
                // Ketuk kartu lain untuk memakainya; ketuk kartu terpilih (ikon pensil) untuk mengubah gayanya.
                StyleCard(selected, "Ubah gaya ${style.name}", onClick = { if (selected) editing = "caption" else state.apply { it.copy(captionStyleId = style.id) } }) {
                    CaptionSample(style, family, selected)
                }
            }
        }
        SectionTitle("Bingkai", Modifier.padding(top = 4.dp))
        ChipRow(gutter = 16.dp) {
            for (frame in state.frameStyles) {
                val selected = frame.id == frameId
                StyleCard(selected, "Ubah bingkai ${frame.name}", onClick = { if (selected) editing = "frame" else state.apply { it.copy(frameStyleId = frame.id) } }) {
                    FrameSample(frame, selected)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = ClipMi.colors.text, modifier = modifier.padding(horizontal = 16.dp))
}

/** Kartu gaya; kartu terpilih menampilkan tombol pensil di tengah untuk mengubah gaya itu. */
@Composable
private fun StyleCard(selected: Boolean, editLabel: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = ClipMi.colors
    Box(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(EDITOR_CARD)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = if (selected) editLabel else null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (selected) {
            // Ikon pensil kecil di pojok, tidak menutupi contoh gaya.
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(RoundedCornerShape(8.dp)).background(c.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ClipMiIcons.Edit, contentDescription = editLabel, tint = c.onAccent, modifier = Modifier.size(14.dp))
            }
        }
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
        // Gambar kecil tata letak: latar dan letak video.
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

private fun hex(value: String?): Color = value?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

// ---------------------------------------------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------------------------------------------

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun HookPanel(state: EditorState) {
    val c = ClipMi.colors
    val layout = state.hookLayout()
    val spec = state.spec()
    val lead = spec.leadSeconds()
    val length = spec.outputDuration()
    var text by remember(state.entity.id) { mutableStateOf(state.snapshot.hook.orEmpty()) }
    fun merge(vararg changes: Pair<String, JsonElement>) = state.apply { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(changes.toMap()))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelTitle("Hook", if (layout.x != null) "Posisi diatur sendiri" else "Posisi bawaan")
            // Tersimpan sendiri 0,8 detik setelah berhenti mengetik; centang hijau sebentar sebagai tanda.
            var saved by remember { mutableStateOf(false) }
            LaunchedEffect(text) {
                if (text.trim() == state.snapshot.hook.orEmpty()) return@LaunchedEffect
                kotlinx.coroutines.delay(800)
                state.apply { it.copy(hook = text.trim().ifEmpty { null }) }
                saved = true
                kotlinx.coroutines.delay(2000)
                saved = false
            }
            Field(
                text, { text = it }, placeholder = "Teks pembuka 3 detik pertama", maxLines = 3,
                trailing = if (saved) ({ Icon(ClipMiIcons.Check, contentDescription = "Tersimpan", tint = c.accent, modifier = Modifier.size(20.dp)) }) else null,
            )
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
                state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(mapOf("size" to JsonPrimitive(v.roundToInt()))))) }
            }, onDone = { state.endDrag() })
            if (layout.alpha != null) {
                LabeledSlider("Sudut kotak", "${layout.radius}", layout.radius.toFloat(), HookLayout.RADIUS_RANGE.first.toFloat()..HookLayout.RADIUS_RANGE.last.toFloat(), onChange = { v ->
                    state.drag { it.copy(hookLayout = HookLayout.merge(it.hookLayout, JsonObject(mapOf("radius" to JsonPrimitive(v.roundToInt()))))) }
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
                    state.drag { s ->
                        s.copy(hookLayout = HookLayout.merge(s.hookLayout, JsonObject(mapOf("start" to JsonPrimitive(Math.round(r.start * 10) / 10.0), "end" to JsonPrimitive(Math.round(r.endInclusive * 10) / 10.0)))))
                    }
                },
                valueRange = min.toFloat()..maxOf(min + 0.5, length).toFloat(),
                onValueChangeFinished = { state.endDrag() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kartu pembuka", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("Hook di atas frame pertama yang dikaburkan, ${seconds(HookLayout.OPENER_SECONDS)} dtk sebelum klip.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                ClipMiSwitch(layout.opener) { on -> merge("opener" to JsonPrimitive(on)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Posisi bawaan", modifier = Modifier.weight(1f), height = 40.dp, enabled = layout.x != null, onClick = { merge("x" to JsonNull, "y" to JsonNull) })
                GhostButton("Reset tampilan", onClick = { state.apply { it.copy(hookLayout = null) } })
            }
        }
    }
}
