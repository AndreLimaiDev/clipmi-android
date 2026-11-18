package id.clipmi.android.ui.editor

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
// Potong: timeline sentuh
// ---------------------------------------------------------------------------------------------------------------

/**
 * Timeline klip: rentang ±10 detik di sekitar klip, kata dari transkrip, bagian yang dibuang, dan pegangan mulai/selesai
 * yang menempel ke batas kata (dengan getaran halus).
 */
@Composable
fun TrimPanel(state: EditorState, position: Double, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val clip = state.snapshot.clip
    val editor = state.editor()
    val haptic = LocalHapticFeedback.current
    val limit = state.projectDuration ?: state.segments.maxOfOrNull { it.end } ?: clip.endTime
    var dragging by remember { mutableStateOf<String?>(null) }
    var window by remember { mutableStateOf(0.0 to 0.0) }
    LaunchedEffect(clip.startTime, clip.endTime, dragging) {
        if (dragging == null) window = maxOf(0.0, floor((clip.startTime - 10) / 10) * 10) to minOf(limit, ceil((clip.endTime + 10) / 10) * 10)
    }
    val (from, to) = window
    val words = remember(window, state.segments) {
        state.segments.filter { it.end >= from && it.start <= to }.flatMap { it.words.orEmpty() }.filter { it.end >= from && it.start <= to }
    }
    val cuts = state.spec().cutRanges()
    var snapped by remember { mutableStateOf<Double?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelTitle("Potong", "Tarik ujung · ketuk untuk memutar")

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .pointerInput(window) {
                    detectTapGestures { offset -> seek(from + (to - from) * offset.x / size.width) }
                }
                .pointerInput(window, "drag") {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val current = state.snapshot.clip
                            val startX = ((current.startTime - from) / (to - from) * size.width).toFloat()
                            val endX = ((current.endTime - from) / (to - from) * size.width).toFloat()
                            dragging = if (abs(offset.x - startX) <= abs(offset.x - endX)) "start" else "end"
                        },
                        onDragEnd = { dragging = null; snapped = null; state.endDrag() },
                        onDragCancel = { dragging = null; snapped = null; state.endDrag() },
                        onDrag = { change, _ ->
                            val edge = dragging ?: return@detectDragGestures
                            var time = from + (to - from) * (change.position.x / size.width).coerceIn(0f, 1f)
                            // Menempel ke batas kata terdekat (awal kata untuk mulai, akhir kata untuk selesai).
                            val boundary = words.map { if (edge == "start") it.start else it.end }.minByOrNull { abs(it - time) }
                            if (boundary != null && abs(boundary - time) < (to - from) * 0.012) {
                                if (snapped != boundary) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                snapped = boundary
                                time = boundary
                            } else {
                                snapped = null
                            }
                            state.drag { s ->
                                val next = if (edge == "start") state.editor().setRange(s.clip, time, s.clip.endTime) else state.editor().setRange(s.clip, s.clip.startTime, time)
                                s.copy(clip = next)
                            }
                            seek(if (edge == "start") state.snapshot.clip.startTime else maxOf(state.snapshot.clip.startTime, state.snapshot.clip.endTime - 1.5))
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(t: Double) = ((t - from) / (to - from) * w).toFloat()
            drawRoundRect(EDITOR_CARD, cornerRadius = CornerRadius(14.dp.toPx()))
            // Kata: batang kecil, lebih terang di dalam klip.
            for (word in words) {
                val inside = word.end > clip.startTime && word.start < clip.endTime
                drawRoundRect(
                    if (inside) Color(0xFF3B5A50) else Color(0xFF26302D),
                    topLeft = Offset(x(word.start) + 1, h * 0.32f),
                    size = Size(maxOf(2f, x(word.end) - x(word.start) - 2), h * 0.36f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            for ((a, b) in cuts) drawRect(c.bad.copy(alpha = 0.45f), topLeft = Offset(x(a), h * 0.18f), size = Size(x(b) - x(a), h * 0.64f))
            // Rentang klip dan pegangan.
            val left = x(clip.startTime)
            val right = x(clip.endTime)
            drawRect(c.accent.copy(alpha = 0.12f), topLeft = Offset(left, 0f), size = Size(right - left, h))
            drawRect(c.accent, topLeft = Offset(left, 0f), size = Size(right - left, 3.dp.toPx()))
            drawRect(c.accent, topLeft = Offset(left, h - 3.dp.toPx()), size = Size(right - left, 3.dp.toPx()))
            val handle = 14.dp.toPx()
            for (edge in listOf(left - handle, right)) {
                drawRoundRect(c.accent, topLeft = Offset(edge, 0f), size = Size(handle, h), cornerRadius = CornerRadius(6.dp.toPx()))
                drawRect(c.onAccent, topLeft = Offset(edge + handle / 2 - 1.dp.toPx(), h * 0.35f), size = Size(2.dp.toPx(), h * 0.3f))
            }
            // Penanda putar.
            val px = x(position)
            if (px in 0f..w) drawRect(Color.White, topLeft = Offset(px - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))
        }

        EdgeRow("Mulai", clip.startTime) { delta -> state.applyClip { editor.nudge(it, "start", delta) }; seek(state.snapshot.clip.startTime) }
        EdgeRow("Selesai", clip.endTime) { delta -> state.applyClip { editor.nudge(it, "end", delta) }; seek(maxOf(state.snapshot.clip.startTime, state.snapshot.clip.endTime - 1.5)) }

        ChipRow(gutter = 0.dp) {
            FilterPill("Tempel ke batas kata", selected = false, height = 36.dp, onClick = { state.applyClip { editor.snapToWords(it) } })
            FilterPill("+ Kalimat sebelum", selected = false, height = 36.dp, enabled = editor.neighbour(clip, "before") != null, onClick = { state.applyClip { editor.extend(it, "before") } })
            FilterPill("+ Kalimat sesudah", selected = false, height = 36.dp, enabled = editor.neighbour(clip, "after") != null, onClick = { state.applyClip { editor.extend(it, "after") } })
        }

        val spec = state.spec()
        Text(
            "Rentang ${Timecode.format(clip.duration())} · hasil ${Timecode.format(spec.outputDuration())}" +
                if (cuts.isNotEmpty()) " · ${cuts.size} bagian dibuang" else "",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
        )
    }
}

@Composable
private fun EdgeRow(label: String, time: Double, onNudge: (Double) -> Unit) {
    val c = ClipMi.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(76.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            Text(precise(time), style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        for (delta in listOf(-0.5, -0.1, 0.1, 0.5)) {
            FilterPill((if (delta > 0) "+" else "−") + seconds(abs(delta)), selected = false, height = 36.dp, onClick = { onNudge(delta) }, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Teks & kata
// ---------------------------------------------------------------------------------------------------------------

/** Kata per baris subtitle: ketuk untuk buang/kembalikan, tahan untuk ubah teks, sembunyikan, atau kata kunci; jeda bisa dibuang. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TextPanel(state: EditorState, seek: (Double) -> Unit) {
    val c = ClipMi.colors
    val notifier = LocalNotifier.current
    val clip = state.snapshot.clip
    val editor = state.editor()
    val lines = remember(state.snapshot, state.captionStyles) { editor.lines(clip) }
    val words = remember(state.snapshot, state.captionStyles) { editor.words(clip) }
    val pauses = remember(state.snapshot, state.silences, state.captionStyles) { state.silences?.let { editor.pauses(clip, it) }.orEmpty() }
    var chosen by remember { mutableStateOf<EditorWord?>(null) }
    LaunchedEffect(clip.startTime, clip.endTime) { state.detectSilences() }
    val tooShort = "Sisa klip terlalu pendek (minimal ${seconds(ClipSpec.MIN_DURATION)} detik)."

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle("Teks & kata", "Ketuk = buang · tahan = opsi")
        val longPauses = pauses.filter { !it.removed && it.length >= 0.6 }
        if (longPauses.isNotEmpty()) {
            SecondaryButton("Buang ${longPauses.size} jeda ≥ 0,6 dtk", height = 40.dp, onClick = {
                if (!state.applyClip { editor.cleanPauses(it, state.silences.orEmpty(), 0.6) }) notifier.show(tooShort)
            })
        } else if (state.silences == null && state.silenceError == null) {
            Text("Membaca jeda dari suara klip…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var offset = 0
            val ranges = lines.map { line -> (offset until offset + line.words.orEmpty().size).also { offset += line.words.orEmpty().size } }
            itemsIndexed(lines) { index, line ->
                val range = ranges[index]
                val marked = Keywords.mark(line.words.orEmpty().map { it.text }, state.snapshot.keywords)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(EDITOR_CARD).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(Timecode.format(state.spec().let { ClipCuts.map(it.cutRanges(), it.startTime, line.start) }), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index == 0) pauses.filter { it.after < range.first }.forEach { PauseChip(it) { if (!state.applyClip { c2 -> editor.togglePause(c2, it) }) notifier.show(tooShort) } }
                        for ((k, i) in range.withIndex()) {
                            val word = words.getOrNull(i) ?: continue
                            WordChip(
                                word, keyword = k in marked,
                                onClick = {
                                    val ok = state.applyClip { if (word.cut) editor.restore(it, word.start, word.end) else editor.cut(it, word.start, word.end) }
                                    if (!ok && !word.cut) notifier.show(tooShort)
                                },
                                onLongClick = { chosen = word },
                            )
                            pauses.filter { it.after == i && (i < range.last || index == lines.lastIndex || true) }.forEach { pause ->
                                PauseChip(pause) { if (!state.applyClip { c2 -> editor.togglePause(c2, pause) }) notifier.show(tooShort) }
                            }
                        }
                    }
                }
            }
        }
    }

    chosen?.let { word -> WordSheet(state, word, seek, onDone = { chosen = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(word: EditorWord, keyword: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ClipMi.colors
    val (bg, fg) = when {
        word.cut -> c.badSoft to c.bad
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
fun CropPanel(state: EditorState) {
    val c = ClipMi.colors
    val spec = state.spec()
    val frame = spec.frame
    val zoom = state.snapshot.cropZoom ?: (frame.zoomFactor() * 100).roundToInt()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("Crop & zoom", frame.name)
        if (!frame.usesCropPosition() && zoom <= 100) {
            Text("Bingkai \"${frame.name}\" menampilkan video utuh, jadi posisi crop hanya berlaku saat di-zoom.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        LabeledSlider("Posisi kiri–kanan", "${spec.effectiveCropPosition()}", spec.effectiveCropPosition().toFloat(), 0f..100f, onChange = { v ->
            state.drag { it.copy(cropPosition = v.roundToInt()) }
        }, onDone = { state.endDrag() })
        LabeledSlider("Posisi atas–bawah", "${spec.effectiveCropY()}", spec.effectiveCropY().toFloat(), 0f..100f, onChange = { v ->
            state.drag { it.copy(cropY = v.roundToInt()) }
        }, onDone = { state.endDrag() })
        LabeledSlider("Zoom", "$zoom%", zoom.toFloat(), ClipSpec.ZOOM_RANGE.first.toFloat()..ClipSpec.ZOOM_RANGE.last.toFloat(), onChange = { v ->
            state.drag { it.copy(cropZoom = v.roundToInt()) }
        }, onDone = { state.endDrag() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Tengahkan", modifier = Modifier.weight(1f), height = 44.dp, onClick = { state.apply { it.copy(cropPosition = 50, cropY = 50) } })
            SecondaryButton("Ikuti bingkai", modifier = Modifier.weight(1f), height = 44.dp, onClick = { state.apply { it.copy(cropPosition = null, cropY = null, cropZoom = null) } })
        }
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
        Slider(
            value = current.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
        )
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
        Text(
            "Ketuk gaya yang terpilih untuk mengubahnya.",
            style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, modifier = Modifier.padding(horizontal = 16.dp),
        )
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
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(ClipMiIcons.Edit, contentDescription = editLabel, tint = c.onAccent, modifier = Modifier.size(20.dp))
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
            Field(text, { text = it }, placeholder = "Teks pembuka 3 detik pertama", maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Simpan teks", height = 40.dp, enabled = text.trim() != state.snapshot.hook.orEmpty(), onClick = {
                    state.apply { it.copy(hook = text.trim().ifEmpty { null }) }
                })
                Text("Tampilkan", style = MaterialTheme.typography.labelMedium, color = c.textBody, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
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
            RangeSlider(
                value = layout.start.toFloat()..layout.end.toFloat(),
                onValueChange = { r ->
                    state.drag { s ->
                        s.copy(hookLayout = HookLayout.merge(s.hookLayout, JsonObject(mapOf("start" to JsonPrimitive(Math.round(r.start * 10) / 10.0), "end" to JsonPrimitive(Math.round(r.endInclusive * 10) / 10.0)))))
                    }
                },
                valueRange = min.toFloat()..maxOf(min + 0.5, length).toFloat(),
                onValueChangeFinished = { state.endDrag() },
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kartu pembuka", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("Hook di atas frame pertama yang dikaburkan, ${seconds(HookLayout.OPENER_SECONDS)} dtk sebelum klip.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                ClipMiSwitch(layout.opener) { on -> merge("opener" to JsonPrimitive(on)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Posisi bawaan", modifier = Modifier.weight(1f), height = 40.dp, enabled = layout.x != null, onClick = { merge("x" to JsonNull, "y" to JsonNull) })
                GhostButton("Reset tampilan", onClick = { state.apply { it.copy(hookLayout = null) } })
            }
        }
    }
}
