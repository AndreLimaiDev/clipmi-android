package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSwitch
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.GroupDivider
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.PromptStyleGuideSheet
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SectionLabel
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.analysis.PromptOptions
import id.clipmi.core.analysis.PromptStyle
import id.clipmi.core.analysis.PromptStyles
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

// Layar tambahan iPhone, padanan CorrectionsScreen, PromptStyleSheet, AiUsageScreen, ResultsScreen, dan DataScreen Android.

/** Ukuran berkas ringkas, misalnya "1,2 GB", "340 MB" (sama dengan formatBytes Android). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> oneDecimal(bytes / (1L shl 30).toDouble()) + " GB"
    bytes >= 1L shl 20 -> (bytes / (1L shl 20).toDouble()).roundToLong().toString() + " MB"
    else -> (bytes / 1024.0).roundToLong().toString() + " KB"
}

/** Satu desimal dengan koma (gaya Indonesia). */
private fun oneDecimal(value: Double): String {
    val scaled = (value * 10).roundToLong()
    return "${scaled / 10},${scaled % 10}"
}

/** Angka ringkas: 12.806 → "12,8 rb". */
private fun compact(value: Long): String = when {
    value >= 1_000_000 -> oneDecimal(value / 1_000_000.0) + " jt"
    value >= 1_000 -> oneDecimal(value / 1_000.0) + " rb"
    else -> value.toString()
}

private fun decimals(value: Double, places: Int): String {
    var factor = 1L
    repeat(places) { factor *= 10 }
    val scaled = (value * factor).roundToLong()
    return "${scaled / factor}." + (scaled % factor).toString().padStart(places, '0')
}

// ---------------------------------------------------------------------------------------------------------------
// Kamus koreksi
// ---------------------------------------------------------------------------------------------------------------

/** Kamus koreksi: pasangan kata salah → benar yang dipakai ulang lintas project (sama dengan desktop dan Android). */
@Composable
fun CorrectionsScreen(repo: Repository, onBack: () -> Unit) {
    val entries by remember { repo.db.corrections().observeAll() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var editing by remember { mutableStateOf<CorrectionEntryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val c = ClipMi.colors

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Kamus koreksi", onBack) {
            if (!entries.isNullOrEmpty()) {
                IconCircle(ClipMiIcons.Refresh, "Terapkan ke semua transkrip", {
                    scope.launch {
                        val (projects, total) = repo.applyCorrectionsToAll()
                        notifier.show(if (total > 0) "$total kata dikoreksi di $projects project." else "Semua transkrip sudah sesuai kamus.")
                    }
                }, background = Color.Transparent, border = false)
            }
            IconCircle(ClipMiIcons.Plus, "Tambah kata", { adding = true })
        }
        val list = entries ?: return@Column
        if (list.isEmpty()) {
            EmptyState("Kamus masih kosong", "Contoh: allah → Allah. Diterapkan otomatis pada transkrip baru.", icon = ClipMiIcons.Book)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp)) {
            itemsIndexed(list, key = { _, it -> it.id }) { i, entry ->
                val top = if (i == 0) 24.dp else 0.dp
                val bottom = if (i == list.lastIndex) 24.dp else 0.dp
                Column(Modifier.clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.card)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = entry }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(entry.wrongText, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                        Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                        Text(
                            entry.correctText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (entry.isActive) c.text else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        ClipMiSwitch(entry.isActive, onCheckedChange = { active ->
                            scope.launch { repo.db.corrections().update(entry.copy(isActive = active, updatedAt = nowMillis())) }
                        })
                    }
                    if (i < list.lastIndex) GroupDivider()
                }
            }
        }
    }

    if (adding || editing != null) CorrectionSheet(repo, editing, onDone = { adding = false; editing = null })
}

@Composable
private fun CorrectionSheet(repo: Repository, entry: CorrectionEntryEntity?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wrong by remember { mutableStateOf(entry?.wrongText.orEmpty()) }
    var correct by remember { mutableStateOf(entry?.correctText.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ClipMiSheet(
        onDone,
        title = if (entry == null) "Tambah koreksi" else "Ubah koreksi",
        subtitle = "Cocok per kata utuh, tidak membedakan huruf besar/kecil; tanda baca di kata asli dipertahankan.",
    ) {
        Field(wrong, { wrong = it; error = null }, label = "Teks salah", placeholder = "misal: alloh", singleLine = true)
        Field(correct, { correct = it; error = null }, label = "Teks benar", placeholder = "misal: Allah", singleLine = true, error = error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry != null) SecondaryButton("Hapus", onClick = { confirmDelete = true }, contentColor = ClipMi.colors.bad, modifier = Modifier.weight(1f))
            PrimaryButton("Simpan", modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    error = repo.saveCorrection(entry?.id, wrong, correct, entry?.isActive ?: true)
                    if (error == null) onDone()
                }
            })
        }
    }
    if (confirmDelete && entry != null) {
        ConfirmDialog(
            "Hapus koreksi?", "\"${entry.wrongText} → ${entry.correctText}\" dihapus dari kamus. Transkrip yang sudah dikoreksi tidak berubah.", "Hapus", danger = true,
            onConfirm = { confirmDelete = false; scope.launch { repo.db.corrections().delete(entry.id); onDone() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Gaya analisis per project
// ---------------------------------------------------------------------------------------------------------------

private val DURATIONS = listOf(null to "Ikut gaya", (15 to 30) to "15–30 dtk", (30 to 60) to "30–60 dtk", (60 to 90) to "60–90 dtk")
private val COUNTS = listOf(null to "Ikut gaya", (3 to 5) to "3–5", (5 to 10) to "5–10", (10 to 15) to "10–15", (15 to 25) to "15–25")

/** Pemilih kecil di kartu analisis: gaya yang dipakai project ini; ketuk untuk mengubah. */
@Composable
fun PromptStyleChip(analysis: IosAnalysis, project: ProjectEntity, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    var open by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    LaunchedEffect(project.promptStyle, project.promptOptions) {
        val (style, options) = analysis.promptStyle(project)
        label = style.name + if (options != null && !options.isEmpty()) " · diatur" else ""
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Ubah gaya analisis") { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Gaya analisis", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
    if (open) PromptStyleSheet(analysis, analysis.settings, project, onDismiss = { open = false })
}

@Composable
private fun PromptStyleSheet(analysis: IosAnalysis, settings: IosSettings, project: ProjectEntity, onDismiss: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var styles by remember { mutableStateOf(PromptStyles.BUILT_IN) }
    var selected by remember { mutableStateOf(PromptStyles.KAJIAN.id) }
    var duration by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var count by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var focus by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PromptStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var guide by remember { mutableStateOf(false) }
    if (guide) PromptStyleGuideSheet(onDismiss = { guide = false })

    LaunchedEffect(Unit) {
        val (style, options) = analysis.promptStyle(project)
        selected = style.id
        duration = options?.minSeconds?.let { it to (options.maxSeconds ?: it) }
        count = options?.minClips?.let { it to (options.maxClips ?: it) }
        focus = options?.focus.orEmpty()
    }
    LaunchedEffect(reload) { styles = settings.promptStyles() }

    if (creating || editing != null) {
        CustomStyleSheet(
            editing,
            onDismiss = { creating = false; editing = null },
            onSave = { style ->
                scope.launch {
                    settings.saveCustomPromptStyle(style)
                    selected = style.id
                    reload++
                    creating = false
                    editing = null
                }
            },
            onDelete = { id ->
                scope.launch {
                    settings.deleteCustomPromptStyle(id)
                    if (selected == id) selected = PromptStyles.KAJIAN.id
                    reload++
                    editing = null
                }
            },
        )
        return
    }

    ClipMiSheet(onDismiss, title = "Gaya analisis", subtitle = "Kriteria AI memilih momen sesuai jenis video. Berlaku untuk analisis otomatis dan lewat chat AI di project ini.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            styles.forEach { style ->
                val on = style.id == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else c.bg)
                        .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(14.dp))
                        .clickable { selected = style.id }.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(style.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                        val desc = style.description.ifBlank { if (style.custom) "Gaya buatan sendiri" else "" }
                        if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    if (style.custom) GhostButton("Ubah", onClick = { editing = style })
                    else if (on) Icon(ClipMiIcons.CircleCheck, null, tint = c.accent, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton("+ Buat gaya sendiri", onClick = { creating = true })
                // Panduan membuat gaya sendiri.
                IconCircle(ClipMiIcons.Book, "Panduan gaya sendiri", { guide = true }, size = 36.dp, background = Color.Transparent, border = false, tint = c.textMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Durasi klip", style = MaterialTheme.typography.labelMedium, color = c.textBody)
            ChipRow(gutter = 0.dp) { DURATIONS.forEach { (v, l) -> FilterPill(l, selected = duration == v, onClick = { duration = v }) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jumlah klip", style = MaterialTheme.typography.labelMedium, color = c.textBody)
            ChipRow(gutter = 0.dp) { COUNTS.forEach { (v, l) -> FilterPill(l, selected = count == v, onClick = { count = v }) } }
        }
        Field(focus, { focus = it }, label = "Fokus topik (opsional)", placeholder = "misalnya: sabar, rezeki, keluarga", singleLine = true)
        PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                analysis.setPromptStyle(project.id, selected, PromptOptions(duration?.first, duration?.second, count?.first, count?.second, focus.trim().ifEmpty { null }))
                notifier.show("Gaya analisis disimpan. Berlaku untuk analisis berikutnya.")
                onDismiss()
            }
        })
    }
}

/** Membuat atau mengubah gaya sendiri: nama, keterangan, dan kriteria (satu per baris). */
@Composable
private fun CustomStyleSheet(style: PromptStyle?, onDismiss: () -> Unit, onSave: (PromptStyle) -> Unit, onDelete: (String) -> Unit) {
    val c = ClipMi.colors
    var name by remember { mutableStateOf(style?.name.orEmpty()) }
    var description by remember { mutableStateOf(style?.description.orEmpty()) }
    // Kriteria pengguna saja (aturan dasar di akhir ditambahkan otomatis).
    val base = PromptStyles.custom("x", "x", emptyList()).criteria.size
    var criteria by remember { mutableStateOf(style?.criteria?.dropLast(base)?.joinToString("\n").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ClipMiSheet(
        onDismiss,
        title = if (style == null) "Gaya baru" else "Ubah gaya",
        subtitle = "Tulis kriteria satu per baris. Aturan dasar (durasi, berdiri sendiri, tidak memotong kalimat, jumlah klip, seri) ditambahkan otomatis.",
    ) {
        Field(name, { name = it; error = null }, label = "Nama gaya", placeholder = "misalnya: Khutbah Jumat", singleLine = true, error = error)
        Field(description, { description = it }, label = "Keterangan (opsional)", singleLine = true)
        Field(criteria, { criteria = it }, label = "Kriteria", placeholder = "misalnya: Lewati bagian berbahasa Arab", minLines = 4, maxLines = 10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (style != null) SecondaryButton("Hapus", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f), contentColor = c.bad)
            PrimaryButton("Simpan gaya", modifier = Modifier.weight(1f), onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "Nama gaya wajib diisi."
                    return@PrimaryButton
                }
                val custom = PromptStyles.custom(style?.id ?: "custom-${nowMillis()}", trimmed, criteria.lines())
                onSave(custom.copy(description = description.trim()))
            })
        }
    }
    if (confirmDelete && style != null) {
        ConfirmDialog(
            "Hapus gaya?", "Gaya \"${style.name}\" dihapus. Project yang memakainya kembali ke gaya Kajian monolog.", "Hapus", danger = true,
            onConfirm = { confirmDelete = false; onDelete(style.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Pemakaian AI
// ---------------------------------------------------------------------------------------------------------------

/** Halaman Pemakaian AI: catatan token/audio dan perkiraan biaya per analisis dan transkripsi. */
@Composable
fun AiUsageScreen(repo: Repository, onBack: () -> Unit) {
    val usages by remember { repo.db.aiUsages().observeAll() }.collectAsState(initial = null)
    val c = ClipMi.colors

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Pemakaian AI", onBack)
        val list = usages ?: return@Column
        if (list.isEmpty()) {
            EmptyState("Belum ada pemakaian", "Setiap analisis dan transkripsi lewat API dicatat di sini beserta perkiraan biayanya.", icon = ClipMiIcons.Chart)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                val total = list.mapNotNull { it.cost }.sum()
                val tokens = list.sumOf { (it.inputTokens + it.outputTokens).toLong() }
                val audio = list.sumOf { it.audioSeconds }
                ClipMiCard(spacing = 10.dp, padding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("Total token", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            Text(IosAnalysis.groupThousands(tokens.toInt()), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 36.sp), color = c.text)
                        }
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
                            Text("Perkiraan", style = MaterialTheme.typography.labelSmall, color = c.accent)
                            Text("$" + decimals(total, 2), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.accent)
                        }
                    }
                    Text(
                        listOfNotNull("${list.size} pemakaian", audio.takeIf { it > 0 }?.let { "${(it / 60).toLong()} menit audio" }, "biaya sebenarnya mengikuti tagihan penyedia").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                }
            }
            val byDay = list.groupBy { Wall.of(it.createdAt).date }
            byDay.forEach { (day, rows) ->
                item(key = "day-$day") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(IdDate.dayMonth(day) + " ${day.year}", style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
                        ClipMiCard(spacing = 0.dp, padding = PaddingValues(vertical = 4.dp)) {
                            rows.forEachIndexed { i, usage ->
                                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
                                val transcription = usage.purpose == "transcription"
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (transcription) c.busy.copy(alpha = 0.12f) else c.accentSoft), contentAlignment = Alignment.Center) {
                                        Icon(if (transcription) ClipMiIcons.Mic else ClipMiIcons.Sparkle, null, tint = if (transcription) c.busy else c.accent, modifier = Modifier.size(16.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(usage.projectTitle.ifEmpty { "(project dihapus)" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                if (transcription) "Transkripsi" else "Analisis",
                                                usage.model,
                                                if (usage.audioSeconds > 0) "${(usage.audioSeconds / 60).toLong().coerceAtLeast(1)} menit" else null,
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val t = usage.inputTokens + usage.outputTokens
                                        if (t > 0) Text(compact(t.toLong()) + " token", style = MaterialTheme.typography.labelMedium, color = c.textBody)
                                        usage.cost?.let { Text("$" + decimals(it, 3), style = MaterialTheme.typography.labelSmall, color = c.textMuted) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Hasil render
// ---------------------------------------------------------------------------------------------------------------

private enum class ResultFilter(val label: String) { All("Semua"), Ready("Siap unggah"), Stale("Hasil lama"), Scheduled("Sudah dijadwalkan") }

/** Layar Hasil render: klip yang sedang/selesai dirender, dengan Jadwalkan, Bagikan, Render ulang, dan editor. */
@Composable
fun ResultsScreen(
    repo: Repository, engine: MediaEngine, renders: Renders, onBack: () -> Unit,
    onOpenProject: (Long) -> Unit, onOpenEditor: (Long) -> Unit, onSchedule: (Long) -> Unit, onOpenSchedule: (Long) -> Unit,
) {
    val clips by remember { repo.db.clips().observeRendered() }.collectAsState(initial = null)
    val schedule by rememberScheduleData(repo)
    val scheduled = schedule?.scheduledClipIds.orEmpty()
    val scheduleOf = schedule?.schedules.orEmpty().sortedBy { it.at }.groupBy { it.clip.id }.mapValues { it.value.first().id }
    val c = ClipMi.colors
    var filter by remember { mutableStateOf(ResultFilter.All) }
    var playing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Hasil render", onBack)
        val list = clips
        if (list != null && list.isEmpty()) {
            EmptyState("Belum ada hasil", "Render klip terpilih dari halaman project.", icon = ClipMiIcons.Film)
            return@Column
        }
        fun output(clip: ClipEntity) = clip.outputPath?.takeIf { !it.contains("://") }?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 }
        fun matches(f: ResultFilter, clip: ClipEntity) = when (f) {
            ResultFilter.All -> true
            ResultFilter.Ready -> clip.render == RenderStatus.Done && !clip.changedAfterRender && clip.id !in scheduled
            ResultFilter.Stale -> clip.changedAfterRender
            ResultFilter.Scheduled -> clip.id in scheduled
        }
        val all = list.orEmpty()
        val shown = all.filter { matches(filter, it) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "where") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ClipMiIcons.Image, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                    Text("Tersimpan di Foto dan di ClipMI (Files › ClipMI › renders)", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            val filters = ResultFilter.entries.filter { f -> f == ResultFilter.All || f == filter || all.any { matches(f, it) } }
            if (filters.size > 2 || filter != ResultFilter.All) {
                item(key = "filter") {
                    ChipRow(gutter = 0.dp) {
                        filters.forEach { f -> FilterPill("${f.label} · ${all.count { matches(f, it) }}", selected = filter == f, height = 34.dp, onClick = { filter = f }) }
                    }
                }
            }
            items(shown, key = { it.id }) { clip ->
                ResultCard(
                    repo, engine, renders, clip, output(clip), clip.id in scheduled,
                    onPlay = { playing = it }, onOpenProject = onOpenProject, onOpenEditor = onOpenEditor,
                    onSchedule = { id -> scheduleOf[id]?.let(onOpenSchedule) ?: onSchedule(id) },
                )
            }
        }
    }

    playing?.let { path ->
        ClipMiSheet({ playing = null }, title = "Hasil render") {
            Box(Modifier.height(420.dp).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                SourceVideo(path, Modifier.fillMaxSize())
            }
            GhostButton("Tutup", onClick = { playing = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultCard(
    repo: Repository, engine: MediaEngine, renders: Renders, clip: ClipEntity, output: String?, isScheduled: Boolean,
    onPlay: (String) -> Unit, onOpenProject: (Long) -> Unit, onOpenEditor: (Long) -> Unit, onSchedule: (Long) -> Unit,
) {
    val c = ClipMi.colors
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    val title = ClipText.displayTitle(clip.title, clip.partNumber)
    val rendering = renders.active == clip.id

    ClipMiCard(padding = PaddingValues(12.dp), spacing = 12.dp) {
        when {
            rendering || clip.render == RenderStatus.Queued || clip.render == RenderStatus.Rendering -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (rendering) "Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka" else "Antre render…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    if (rendering) GhostButton("Batalkan", onClick = { renders.cancel() })
                }
                ProgressBar(if (rendering) renders.fraction.toFloat() else null, color = c.busy)
            }
            clip.render == RenderStatus.Failed -> {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Render gagal: ${clip.renderError ?: "sebab tidak diketahui"}", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Coba lagi", { renders.start(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Refresh, primary = true, enabled = renders.active == null)
                    ActionChip("Buka editor", { onOpenEditor(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Scissors)
                }
            }
            else -> {
                val missing = output == null
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.width(64.dp).height(112.dp).clip(RoundedCornerShape(12.dp)).background(c.panel)
                            .clickable(enabled = !missing, onClickLabel = "Putar hasil") { output?.let(onPlay) },
                        contentAlignment = Alignment.Center,
                    ) {
                        ClipThumbnail(repo, engine, clip, Modifier.fillMaxSize())
                        if (!missing) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                Icon(ClipMiIcons.Play, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (output != null) {
                            Text(listOf(Timecode.format(clip.duration), formatBytes(fileSize(output))).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        when {
                            missing -> StatusBadge("File hilang", Tone.Bad)
                            clip.changedAfterRender -> StatusBadge("Hasil lama", Tone.Warn)
                            isScheduled -> StatusBadge("Dijadwalkan", Tone.Busy)
                            else -> StatusBadge("Siap unggah", Tone.Good)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (missing || clip.changedAfterRender) {
                        ActionChip("Render ulang", { renders.start(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Refresh, primary = true, enabled = renders.active == null)
                    } else {
                        ActionChip(if (isScheduled) "Lihat jadwal" else "Jadwalkan", { onSchedule(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Calendar, primary = !isScheduled)
                    }
                    if (!missing) {
                        ActionChip("Bagikan", {
                            clipboard.setText(AnnotatedString(ClipText.shareText(clip.title, clip.partNumber, clip.caption, repo.hashtags(clip))))
                            notifier.show("Caption & hashtag disalin. Tempel di aplikasi tujuan.")
                            engine.shareFile(output!!)
                        }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Share)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Editor", { onOpenEditor(clip.id) }, Modifier.weight(1f), height = 36.dp, icon = ClipMiIcons.Scissors)
                    ActionChip("Project", { onOpenProject(clip.projectId) }, Modifier.weight(1f), height = 36.dp, icon = ClipMiIcons.Home)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Data & penyimpanan
// ---------------------------------------------------------------------------------------------------------------

/**
 * Data & penyimpanan: sisa ruang iPhone, pemakaian folder ClipMI, dan video sumber per project (hapus untuk menghemat
 * ruang; unduh atau pilih ulang saat diperlukan). Transkrip, klip, dan hasil tetap ada.
 */
@Composable
fun DataScreen(repo: Repository, downloads: Downloads, localVideos: LocalVideos, onBack: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val projects by remember { repo.db.projects().observeAll() }.collectAsState(initial = null)
    var refresh by remember { mutableStateOf(0) }
    var deleteSource by remember { mutableStateOf<ProjectEntity?>(null) }
    var recoverLocal by remember { mutableStateOf<ProjectEntity?>(null) }
    val sizes by produceState<Map<Long, Long>?>(null, projects, refresh) {
        value = withContext(Dispatchers.Default) { projects.orEmpty().associate { it.id to directorySize(repo.absolute(repo.projectDir(it.id))) } }
    }
    val space by produceState<Triple<Long, Long, Long>?>(null, projects, refresh) {
        value = withContext(Dispatchers.Default) {
            val renders = directorySize(repo.absolute("renders"))
            Triple(freeSpace(repo.documents), directorySize(repo.documents) - renders, renders)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Data & penyimpanan", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("Penyimpanan")
            ClipMiCard(spacing = 14.dp, padding = PaddingValues(20.dp)) {
                val s = space
                if (s == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                } else {
                    Column {
                        Text("Sisa ruang iPhone", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Text(formatBytes(s.first), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 36.sp), color = c.text)
                    }
                    val total = s.second + s.third
                    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.panel)) {
                        if (total > 0) {
                            Box(Modifier.weight(s.second.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(c.accent))
                            Box(Modifier.weight(s.third.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(c.busy))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(c.accent, "Folder kerja", formatBytes(s.second))
                        Legend(c.busy, "Video hasil", formatBytes(s.third))
                    }
                    if (total > 0) Text("Video sumber paling besar; transkrip, klip, dan hasil tetap ada bila dihapus.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            val list = projects.orEmpty().sortedByDescending { sizes?.get(it.id) ?: 0 }
            if (list.isNotEmpty()) {
                ClipMiCard(spacing = 0.dp, padding = PaddingValues(vertical = 4.dp)) {
                    list.forEachIndexed { i, p ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        sizes?.get(p.id)?.let(::formatBytes) ?: "…",
                                        when (p.video) {
                                            VideoStatus.Deleted -> "sumber dihapus"
                                            VideoStatus.Failed -> "gagal"
                                            VideoStatus.Ready -> null
                                            else -> p.video.label.lowercase()
                                        },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = if (p.video == VideoStatus.Deleted) c.warn else c.textMuted,
                                )
                            }
                            when (p.video) {
                                VideoStatus.Ready -> IconCircle(ClipMiIcons.Trash, "Hapus video sumber ${p.title}", { deleteSource = p }, size = 40.dp, background = Color.Transparent, border = false, tint = c.bad)
                                VideoStatus.Deleted, VideoStatus.Failed -> IconCircle(
                                    if (p.isYouTube) ClipMiIcons.Download else ClipMiIcons.Refresh,
                                    if (p.isYouTube) "Unduh ulang video" else "Pilih ulang video",
                                    {
                                        if (p.isYouTube) {
                                            if (downloads.active == null) {
                                                downloads.start(p.id)
                                                notifier.show("Video diunduh ulang. Biarkan aplikasi tetap terbuka.")
                                            } else {
                                                notifier.show("Satu video diunduh pada satu waktu. Tunggu unduhan yang berjalan selesai.")
                                            }
                                        } else {
                                            recoverLocal = p
                                        }
                                    },
                                    size = 40.dp, background = Color.Transparent, border = false,
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }
            Text("Cadangan dan Paket Standar Tim ada di Pengaturan › Data.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }

    deleteSource?.let { p ->
        ConfirmDialog(
            "Hapus video sumber?",
            "Video sumber \"${p.title}\" dihapus dari iPhone (${sizes?.get(p.id)?.let(::formatBytes).orEmpty()}). Transkrip, klip, dan hasil render tetap ada; untuk render ulang, " +
                (if (p.isYouTube) "videonya diunduh ulang." else "videonya dipilih ulang dari galeri."),
            "Hapus", danger = true,
            onConfirm = {
                deleteSource = null
                scope.launch {
                    val freed = repo.deleteSource(p.id)
                    notifier.show(if (freed > 0) "Video sumber dihapus, ${formatBytes(freed)} dibebaskan." else "Video sumber tidak bisa dihapus saat klipnya sedang dirender.")
                    refresh++
                }
            },
            onDismiss = { deleteSource = null },
        )
    }
    recoverLocal?.let { p ->
        LocalVideoSheet(onDismiss = { recoverLocal = null }, onPick = { fromFiles ->
            recoverLocal = null
            scope.launch {
                localVideos.recover(p.id, fromFiles)
                    .onSuccess { ok -> if (ok) { notifier.show("Video sumber dipasang lagi."); refresh++ } }
                    .onFailure { notifier.show(it.message ?: "Video tidak bisa dipakai.") }
            }
        })
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text("$label $value", style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textBody)
    }
}
