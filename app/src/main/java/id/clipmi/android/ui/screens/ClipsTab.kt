package id.clipmi.android.ui.screens

import id.clipmi.android.ui.components.ActionChip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ClipSource
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.domain.AiConfig
import id.clipmi.android.work.RenderQueue
import id.clipmi.android.work.RenderSafety
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconBadge
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.ChatPaste
import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.util.Locale

private val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id"))

/** Tab Klip: analisis (Mode API dan lewat chat AI), lalu kandidat klip untuk ditinjau (padanan halaman Klip desktop). */
@Composable
fun ClipsTab(container: AppContainer, project: ProjectEntity, hasTranscript: Boolean, playRange: (Double, Double) -> Unit, onOpenEditor: (Long) -> Unit = {}, onSchedule: (Long) -> Unit = {}) {
    val clips by container.database.clips().observe(project.id).collectAsState(initial = emptyList())
    val series by container.database.series().observe(project.id).collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.Active) }
    var editing by remember { mutableStateOf<ClipEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    val c = ClipMi.colors

    Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (project.transcript != TranscriptStatus.Ready || !hasTranscript) {
            ClipMiCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(ClipMiIcons.Sparkle, Tone.Idle)
                    Text("Analisis AI dimulai setelah transkrip tersedia.", style = MaterialTheme.typography.bodyMedium, color = c.textBody)
                }
            }
        } else {
            AnalysisCard(container, project, compact = clips.isNotEmpty())
        }

        if (clips.isNotEmpty()) {
            // Filter yang kosong disembunyikan (kecuali Aktif); bila filter terpilih jadi kosong, kembali ke Aktif.
            val counts = ReviewFilter.entries.associateWith { f -> clips.count(f::matches) }
            LaunchedEffect(counts[filter]) { if (filter != ReviewFilter.Active && counts[filter] == 0) filter = ReviewFilter.Active }
            if (counts.count { (f, n) -> f == ReviewFilter.Active || n > 0 } > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewFilter.entries.filter { it == ReviewFilter.Active || counts.getValue(it) > 0 }.forEach { f ->
                        FilterPill("${f.label} ${counts.getValue(f)}", selected = filter == f, height = 32.dp, onClick = { filter = f })
                    }
                }
            }
        } else if (hasTranscript) {
            Text("Tekan lama segmen di Transkrip untuk membuat klip sendiri.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }

        // Kunci per klip: status geser/terbuka tiap kartu milik klipnya sendiri, tidak berpindah ke klip lain.
        clips.filter(filter::matches).forEach { clip -> key(clip.id) {
            ClipCard(
                container, clip, series.firstOrNull { it.id == clip.clipSeriesId }?.title,
                onPlay = { playRange(clip.startTime, clip.endTime) },
                onEdit = { editing = clip },
                onOpen = { onOpenEditor(clip.id) },
                onSchedule = { onSchedule(clip.id) },
                onCopy = {
                    copy(context, ClipText.shareText(clip.title, clip.partNumber, clip.caption, container.analysis.decodeList(clip.hashtags)))
                    notifier.show("Judul, caption, dan hashtag disalin.")
                },
                onReview = { status ->
                    val before = clip.review
                    scope.launch { container.database.clips().setReview(clip.id, status.value) }
                    if (status == ReviewStatus.Rejected) {
                        notifier.show("Klip dibuang", action = "Urungkan") { scope.launch { container.database.clips().setReview(clip.id, before.value) } }
                    }
                },
            )
        } }

        if (clips.any { it.source == ClipSource.AI && it.review == ReviewStatus.Candidate }) {
            GhostButton("Hapus kandidat yang belum ditinjau", onClick = { confirmClear = true }, danger = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    editing?.let { clip -> ClipEditSheet(container, clip, onDone = { editing = null }) }
    if (confirmClear) {
        ConfirmDialog(
            "Hapus kandidat?",
            "Kandidat AI yang belum dipilih atau dibuang dihapus. Klip terpilih dan klip manual tetap ada.",
            "Hapus", danger = true,
            onConfirm = {
                confirmClear = false
                scope.launch {
                    val count = container.database.clips().deleteUnreviewed(project.id)
                    notifier.show("$count kandidat yang belum ditinjau dihapus.")
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

private enum class ReviewFilter(val label: String) {
    Active("Aktif"),
    Selected("Terpilih"),
    Rejected("Dibuang");

    fun matches(clip: ClipEntity) = when (this) {
        Active -> clip.review != ReviewStatus.Rejected
        Selected -> clip.review == ReviewStatus.Selected
        Rejected -> clip.review == ReviewStatus.Rejected
    }
}

/** Kartu "Pilih momen dengan AI": Mode API (otomatis) atau lewat chat AI (salin prompt, tempel balasan). */
@Composable
private fun AnalysisCard(container: AppContainer, project: ProjectEntity, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    val c = ClipMi.colors
    var manual by rememberSaveable { mutableStateOf(false) }
    val prompts by produceState(emptyList<String>(), project.transcriptStatus, project.updatedAt) { value = container.analysis.prompts(project.id) }
    val config by produceState<AiConfig?>(null, project.analysisStatus) { value = container.settings.analysis() }
    val resumable by produceState<List<Int>>(emptyList(), prompts, project.analysisParts, project.analysisStatus) {
        value = if (AnalysisStatus.isActive(project.analysisStatus) || prompts.size < 2) emptyList() else {
            val done = container.analysis.completedParts(project.id, prompts)
            if (done.isEmpty()) emptyList() else prompts.indices.filter { it !in done }
        }
    }
    val active = AnalysisStatus.isActive(project.analysisStatus)
    val cfg = config

    // Sudah ada klip dan tidak ada yang berjalan/tersisa: cukup dua tombol kecil, analisis ulang dikonfirmasi dulu.
    if (compact && !active && project.analysisMessage == null && resumable.isEmpty()) {
        var confirmAgain by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cfg != null) ActionChip("Analisis lagi", { confirmAgain = true }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Sparkle, enabled = prompts.isNotEmpty())
            ActionChip("Lewat chat AI", { manual = true }, Modifier.weight(1f), height = 40.dp)
        }
        if (confirmAgain && cfg != null) {
            val estimate = container.analysis.estimate(prompts, cfg, null)
            ConfirmDialog(
                "Analisis lagi?",
                listOfNotNull(cfg.provider.label.substringBefore(" ("), "±${number.format(estimate.tokens)} token", estimate.cost?.let { "±$" + "%.${if (it < 0.1) 3 else 2}f".format(Locale.ROOT, it) }).joinToString(" · "),
                "Analisis",
                onConfirm = { confirmAgain = false; scope.launch { container.analysis.start(project.id) } },
                onDismiss = { confirmAgain = false },
            )
        }
        if (manual) ManualAnalysisSheet(container, project, prompts, onDismiss = { manual = false })
        return
    }

    ClipMiCard(spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ClipMiIcons.Sparkle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pilih momen dengan AI", style = MaterialTheme.typography.titleSmall, color = c.text)
                val subtitle = if (cfg == null) {
                    "Mode API belum diatur · lewat chat AI tetap gratis"
                } else {
                    val estimate = container.analysis.estimate(prompts, cfg, resumable.ifEmpty { null })
                    listOfNotNull(
                        cfg.provider.label.substringBefore(" ("),
                        "±${number.format(estimate.tokens)} token",
                        estimate.cost?.let { "±$" + "%.${if (it < 0.1) 3 else 2}f".format(Locale.ROOT, it) },
                        if (resumable.isNotEmpty()) "${resumable.size} bagian tersisa" else null,
                    ).joinToString(" · ")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        if (active) {
            ProgressBar(null, color = c.busy)
            project.analysisMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody) }
            SecondaryButton("Batalkan", onClick = { scope.launch { container.analysis.cancel(project.id) } }, height = 44.dp, modifier = Modifier.fillMaxWidth())
        } else {
            project.analysisMessage?.let { message ->
                val color = when (project.analysisStatus) {
                    AnalysisStatus.DONE -> c.accent
                    AnalysisStatus.FAILED -> c.bad
                    else -> c.textMuted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconCircle(ClipMiIcons.Close, "Tutup pesan", { scope.launch { container.analysis.dismiss(project.id) } }, size = 36.dp, background = Color.Transparent, border = false, tint = c.textMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cfg != null) {
                    if (resumable.isNotEmpty()) {
                        PrimaryButton("Lanjutkan", onClick = { scope.launch { container.analysis.start(project.id, resume = true) } }, height = 44.dp, modifier = Modifier.weight(1f))
                    } else {
                        PrimaryButton(
                            "Analisis otomatis", enabled = prompts.isNotEmpty(), height = 44.dp, modifier = Modifier.weight(1f),
                            onClick = { scope.launch { container.analysis.start(project.id) } },
                        )
                    }
                }
                SecondaryButton("Lewat chat AI", onClick = { manual = true }, height = 44.dp, modifier = Modifier.weight(1f))
            }
            if (cfg != null && resumable.isNotEmpty()) {
                GhostButton("Analisis ulang dari awal", onClick = { scope.launch { container.analysis.start(project.id) } })
            }
        }
    }

    if (manual) ManualAnalysisSheet(container, project, prompts, onDismiss = { manual = false })
}

/** Mode manual (tanpa biaya API): salin/bagikan prompt ke aplikasi chat AI, lalu tempel balasan JSON-nya. */
@Composable
private fun ManualAnalysisSheet(container: AppContainer, project: ProjectEntity, prompts: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    val c = ClipMi.colors
    var reply by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val tokens = prompts.sumOf { PromptBuilder.estimateTokens(it) }

    ClipMiSheet(
        onDismiss,
        title = "Lewat chat AI",
        subtitle = "Salin atau bagikan prompt ke Claude, ChatGPT, atau Gemini, lalu tempel balasan JSON-nya di sini." +
            (if (prompts.size > 1) " Transkrip panjang dibagi ${prompts.size} bagian; kirim satu per satu." else "") +
            " Total ±${number.format(tokens)} token.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            prompts.forEachIndexed { i, prompt ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (prompts.size > 1) "Bagian ${i + 1}" else "Prompt", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text("${number.format(PromptBuilder.estimateTokens(prompt))} token", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    IconCircle(ClipMiIcons.Copy, "Salin prompt", {
                        notifier.show(if (copy(context, prompt)) "Prompt disalin. Tempel ke aplikasi chat AI." else TOO_LARGE)
                    }, size = 40.dp, background = c.panel)
                    IconCircle(ClipMiIcons.Share, "Bagikan prompt", { if (!share(context, prompt)) notifier.show(TOO_LARGE) }, size = 40.dp, background = c.panel)
                }
            }
        }
        // Centang hijau di pojok kanan bawah begitu balasan terbaca sebagai JSON klip (termasuk salinan ChatGPT yang
        // perlu dirapikan), sebelum Simpan kandidat.
        val readable = remember(reply) {
            reply.isNotBlank() && listOf(reply, ChatPaste.clean(reply)).any { runCatching { ClipImporter.decode(it) }.isSuccess }
        }
        Box {
            Field(
                reply, { reply = it; replyError = null },
                label = "Balasan JSON dari AI",
                placeholder = "{\"clips\": [ … ]}",
                error = replyError,
                minLines = 4,
                maxLines = 8,
            )
            if (readable && replyError == null) {
                Icon(
                    ClipMiIcons.CircleCheck, "Balasan JSON terbaca", tint = c.accent,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp).size(22.dp).background(c.bg, CircleShape),
                )
            }
        }
        importErrors.takeIf { it.isNotEmpty() }?.let { errors ->
            Text("Ditolak:\n" + errors.joinToString("\n"), color = c.bad, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Tempel", icon = ClipMiIcons.Copy, modifier = Modifier.weight(1f), onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { reply = it; replyError = null }
            })
            PrimaryButton("Simpan kandidat", modifier = Modifier.weight(1f), onClick = {
                importErrors = emptyList()
                if (reply.isBlank()) {
                    replyError = "Tempel balasan JSON dari AI dulu."
                    return@PrimaryButton
                }
                scope.launch {
                    try {
                        val result = container.analysis.importReply(project.id, reply)
                        importErrors = result.errors
                        notifier.show(result.message())
                        if (result.created > 0) {
                            reply = ""
                            if (result.errors.isEmpty()) onDismiss()
                        }
                    } catch (e: AnalysisResultException) {
                        replyError = e.message
                    }
                }
            })
        }
    }
}

/**
 * Kartu klip: ringkas (skor, judul, ringkasan, durasi dan status); ketuk untuk detail dan aksi. Geser ke kanan untuk
 * memilih (atau memulihkan), ke kiri untuk membuang (atau membatalkan pilihan). Aksi geser baru jalan bila kartu
 * dilepas setelah digeser lebih dari separuh lebarnya, dan hanya untuk kartu itu.
 */
@Composable
private fun ClipCard(
    container: AppContainer,
    clip: ClipEntity,
    seriesTitle: String?,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onReview: (ReviewStatus) -> Unit,
    onOpen: () -> Unit = {},
    onSchedule: () -> Unit = {},
) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable(clip.id) { mutableStateOf(false) }

    // Arah geser yang berlaku untuk status klip ini (null = arah itu tidak dipakai).
    val toRight: Pair<String, ReviewStatus>? = when (clip.review) {
        ReviewStatus.Candidate -> "Pilih" to ReviewStatus.Selected
        ReviewStatus.Rejected -> "Pulihkan" to ReviewStatus.Candidate
        else -> null
    }
    val toLeft: Pair<String, ReviewStatus>? = when (clip.review) {
        ReviewStatus.Candidate -> "Buang" to ReviewStatus.Rejected
        ReviewStatus.Selected -> "Batal pilih" to ReviewStatus.Candidate
        else -> null
    }
    var width by remember { mutableIntStateOf(0) }
    var dragX by remember(clip.id, clip.review) { mutableFloatStateOf(0f) }
    val threshold = width * 0.5f
    val armed = width > 0 && abs(dragX) >= threshold
    LaunchedEffect(armed) { if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clip(MaterialTheme.shapes.large)) {
        // Latar di balik kartu: label aksi; warnanya penuh begitu geseran melewati batas.
        val action = if (dragX > 0) toRight else if (dragX < 0) toLeft else null
        if (action != null) {
            val good = action.second != ReviewStatus.Rejected
            val soft = if (good) c.accentSoft else c.badSoft
            val strong = if (good) c.accent else c.bad
            Box(
                Modifier.matchParentSize().background(if (armed) strong else soft).padding(horizontal = 22.dp),
                contentAlignment = if (dragX < 0) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(action.first, color = if (armed) Color.White else strong, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = dragX + delta
                        // Arah yang tidak dipakai hanya bergeser sedikit (tanda tidak ada aksi).
                        val max = width.toFloat()
                        dragX = when {
                            next > 0 && toRight == null -> (next).coerceAtMost(max * 0.08f)
                            next < 0 && toLeft == null -> (next).coerceAtLeast(-max * 0.08f)
                            else -> next.coerceIn(-max, max)
                        }
                    },
                    onDragStopped = {
                        val release = dragX
                        val target = if (abs(release) >= threshold && width > 0) (if (release > 0) toRight else toLeft) else null
                        if (target != null) onReview(target.second)
                        scope.launch { animate(release, 0f) { value, _ -> dragX = value } }
                    },
                ),
        ) {
            ClipMiCard(onClick = { expanded = !expanded }, spacing = 10.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScoreBadge(clip.score)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
                        (clip.reason ?: clip.caption)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                        }
                        ClipMeta(clip, seriesTitle, expanded)
                    }
                }
                if (!expanded) return@ClipMiCard
                clip.hook?.let { HookLine(it) }
                ClipActions(container, clip, onPlay, onEdit, onCopy, onReview, onOpen, onSchedule)
            }
        }
    }
}

/** Baris kecil di bawah ringkasan: durasi (atau rentang waktu saat dibuka), status pilih, render, manual, seri. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipMeta(clip: ClipEntity, seriesTitle: String?, expanded: Boolean) {
    val c = ClipMi.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            if (expanded) "${Timecode.format(clip.startTime)} – ${Timecode.format(clip.endTime)} · ${Timecode.format(clip.duration)}" else Timecode.format(clip.duration),
            style = MaterialTheme.typography.labelMedium, color = c.textMuted, maxLines = 1,
        )
        when (clip.review) {
            ReviewStatus.Selected -> StatusBadge("Terpilih", Tone.Good)
            ReviewStatus.Rejected -> StatusBadge("Dibuang", Tone.Bad)
            else -> Unit
        }
        when (clip.render) {
            RenderStatus.Queued -> StatusBadge("Antre render", Tone.Busy)
            RenderStatus.Rendering -> StatusBadge("Render ${clip.renderProgress ?: 0}%", Tone.Busy)
            RenderStatus.Done -> StatusBadge(if (clip.changedAfterRender) "Hasil lama" else "Siap unggah", if (clip.changedAfterRender) Tone.Warn else Tone.Good)
            RenderStatus.Failed -> StatusBadge("Render gagal", Tone.Bad)
            else -> Unit
        }
        if (clip.source == ClipSource.MANUAL) StatusBadge("Manual", Tone.Idle)
    }
    if (expanded) seriesTitle?.let { Text("Seri: $it", style = MaterialTheme.typography.labelMedium, color = c.accent) }
}

/** Hook (teks pembuka di layar) dengan garis aksen di kiri. */
@Composable
private fun HookLine(hook: String) {
    val c = ClipMi.colors
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = 0.5f)))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Hook", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Text(hook, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
    }
}

/** Aksi kartu yang dibuka: tombol kotak membulat (bukan pil), sesuai status render. */
@Composable
private fun ClipActions(
    container: AppContainer,
    clip: ClipEntity,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onReview: (ReviewStatus) -> Unit,
    onOpen: () -> Unit,
    onSchedule: () -> Unit,
) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val canRender = clip.review == ReviewStatus.Selected || clip.render != RenderStatus.NotRendered

    when (clip.render) {
        RenderStatus.Queued, RenderStatus.Rendering -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (clip.render == RenderStatus.Queued) clip.renderError ?: RenderSafety.batteryReason(context) ?: "Antre render…" else "Merender ${clip.renderProgress ?: 0}%",
                    style = MaterialTheme.typography.bodySmall, color = c.textBody, modifier = Modifier.weight(1f),
                )
                GhostButton("Batalkan", onClick = { scope.launch { RenderQueue.cancel(context, clip) } })
            }
            ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = c.busy)
        }
        RenderStatus.Failed -> clip.renderError?.let { Text("Render gagal: $it", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        else -> Unit
    }
    if (clip.render == RenderStatus.Done) {
        // Setelah render: langsung jadwalkan atau bagikan.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Jadwalkan", onSchedule, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Calendar, primary = true)
            ActionChip("Bagikan", {
                val text = ClipText.shareText(clip.title, clip.partNumber, clip.caption, container.analysis.decodeList(clip.hashtags))
                copy(context, text)
                notifier.show(if (shareVideo(context, clip.outputPath, clip.title)) "Caption & hashtag disalin. Tempel di aplikasi tujuan." else "File hasil tidak ditemukan. Render ulang klip ini.")
            }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Share)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionChip("Putar", onPlay, height = 40.dp, icon = ClipMiIcons.Play)
        ActionChip("Editor", onOpen, height = 40.dp, icon = ClipMiIcons.Scissors)
        if (canRender && clip.render != RenderStatus.Queued && clip.render != RenderStatus.Rendering) {
            val label = when (clip.render) { RenderStatus.Done -> "Render ulang"; RenderStatus.Failed -> "Coba lagi"; else -> "Render" }
            ActionChip(label, { scope.launch { RenderQueue.render(context, clip) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Film, primary = clip.render == RenderStatus.NotRendered)
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            ActionChip("", { menu = true }, height = 40.dp, icon = ClipMiIcons.More)
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
                if (clip.render == RenderStatus.Done) {
                    DropdownMenuItem(text = { Text("Putar hasil render") }, leadingIcon = { Icon(ClipMiIcons.Play, null, Modifier.size(18.dp)) }, onClick = {
                        menu = false
                        if (!openVideo(context, clip.outputPath)) notifier.show("File hasil tidak ditemukan. Render ulang klip ini.")
                    })
                }
                DropdownMenuItem(text = { Text("Sunting teks") }, leadingIcon = { Icon(ClipMiIcons.Edit, null, Modifier.size(18.dp)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Salin judul & caption") }, leadingIcon = { Icon(ClipMiIcons.Copy, null, Modifier.size(18.dp)) }, onClick = { menu = false; onCopy() })
                when (clip.review) {
                    ReviewStatus.Candidate -> {
                        DropdownMenuItem(text = { Text("Pilih") }, leadingIcon = { Icon(ClipMiIcons.Check, null, Modifier.size(18.dp)) }, onClick = { menu = false; onReview(ReviewStatus.Selected) })
                        DropdownMenuItem(text = { Text("Buang", color = c.bad) }, leadingIcon = { Icon(ClipMiIcons.Close, null, Modifier.size(18.dp), tint = c.bad) }, onClick = { menu = false; onReview(ReviewStatus.Rejected) })
                    }
                    ReviewStatus.Selected -> DropdownMenuItem(text = { Text("Batal pilih") }, leadingIcon = { Icon(ClipMiIcons.Close, null, Modifier.size(18.dp)) }, onClick = { menu = false; onReview(ReviewStatus.Candidate) })
                    ReviewStatus.Rejected -> DropdownMenuItem(text = { Text("Pulihkan") }, leadingIcon = { Icon(ClipMiIcons.Refresh, null, Modifier.size(18.dp)) }, onClick = { menu = false; onReview(ReviewStatus.Candidate) })
                }
            }
        }
    }
}

/** Membuka lembar bagikan Android dengan video hasil terlampir (TikTok, Instagram, YouTube, WhatsApp, …). */
internal fun shareVideo(context: Context, output: String?, title: String?): Boolean {
    val uri = output?.let(Uri::parse) ?: return false
    if (!exists(context, uri)) return false
    return runCatching { context.startActivity(videoChooser(context, videoSend(uri, title))) }.isSuccess
}

/** Intent kirim video hasil; judul klip dipakai sebagai nama yang tampil di pratinjau lembar bagikan. */
internal fun videoSend(uri: Uri, title: String?): Intent =
    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
        clipData = ClipData(title ?: "", arrayOf("video/mp4"), ClipData.Item(uri))
        title?.let { putExtra(Intent.EXTRA_TITLE, it) }
    }

/** Pemilih aplikasi tanpa ClipMI sendiri (ClipMI menerima video kiriman untuk membuat project baru). */
internal fun videoChooser(context: Context, send: Intent): Intent =
    Intent.createChooser(send, "Bagikan klip")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, id.clipmi.android.MainActivity::class.java)))

internal fun openVideo(context: Context, output: String?): Boolean {
    val uri = output?.let(Uri::parse) ?: return false
    if (!exists(context, uri)) return false
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(view) }.isSuccess
}

internal fun exists(context: Context, uri: Uri): Boolean =
    runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

/** Skor AI dalam kotak membulat: hijau ≥ 80, biru ≥ 60, abu-abu di bawahnya. */
@Composable
private fun ScoreBadge(score: Int?) {
    val c = ClipMi.colors
    val color = when {
        score == null -> c.textMuted
        score >= 80 -> c.accent
        score >= 60 -> c.busy
        else -> c.textMuted
    }
    Column(
        Modifier.width(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(score?.toString() ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = color)
        Text("skor", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = color.copy(alpha = 0.8f))
    }
}

/** Sunting teks kandidat (judul, hook, caption, hashtag, kata kunci) atau hapus klip. */
@Composable
private fun ClipEditSheet(container: AppContainer, clip: ClipEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var title by remember { mutableStateOf(clip.title) }
    var hook by remember { mutableStateOf(clip.hook.orEmpty()) }
    var caption by remember { mutableStateOf(clip.caption.orEmpty()) }
    var hashtags by remember { mutableStateOf(container.analysis.decodeList(clip.hashtags).joinToString(" ")) }
    var keywords by remember { mutableStateOf(container.analysis.decodeList(clip.keywords).joinToString(", ")) }
    var confirmDelete by remember { mutableStateOf(false) }

    ClipMiSheet(onDone, title = "Sunting klip", subtitle = "${Timecode.format(clip.startTime)} – ${Timecode.format(clip.endTime)} · ${Timecode.format(clip.duration)}") {
        Field(title, { title = it }, label = "Judul", error = if (title.isBlank()) "Judul wajib diisi." else null)
        Field(hook, { hook = it }, label = "Hook", supporting = "Teks pembuka di layar, 3 detik pertama.")
        Field(caption, { caption = it }, label = "Caption", minLines = 3, maxLines = 8)
        Field(hashtags, { hashtags = it }, label = "Hashtag", placeholder = "#kajian #islam")
        Field(keywords, { keywords = it }, label = "Kata kunci", supporting = "Pisahkan dengan koma; disorot di subtitle.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Hapus klip", onClick = { confirmDelete = true }, contentColor = ClipMi.colors.bad, modifier = Modifier.weight(1f))
            PrimaryButton("Simpan", enabled = title.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    val tags = ClipImporter.hashtags(JsonPrimitive(hashtags))
                    val words = Keywords.clean(keywords.split(','))
                    // Judul (bilah judul) dan hook tampil di video, jadi mengubahnya menandai hasil render sebagai lama.
                    container.database.clips().updateText(
                        clip.id,
                        title = title.trim(),
                        hook = hook.trim().ifEmpty { null },
                        caption = caption.trim().ifEmpty { null },
                        hashtags = container.analysis.encodeList(tags),
                        keywords = words.takeIf { it.isNotEmpty() }?.let(container.analysis::encodeList),
                        changesRender = title.trim() != clip.title || hook.trim().ifEmpty { null } != clip.hook,
                    )
                    onDone()
                }
            })
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Hapus klip ini?", "Klip \"${clip.title}\" dihapus permanen dari project.", "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.database.clips().delete(clip.id)
                    notifier.show("Klip dihapus.")
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private const val TOO_LARGE = "Prompt terlalu besar untuk disalin/dibagikan sekaligus di HP. Kecilkan \"Batas panjang prompt\" di Pengaturan → Analisis AI agar transkrip dibagi menjadi beberapa bagian."

/** Batas aman ukuran teks lewat clipboard/intent (Binder ±1 MB, teks UTF-16). */
private const val MAX_TRANSFER_CHARS = 300_000

internal fun copy(context: Context, text: String): Boolean {
    if (text.length > MAX_TRANSFER_CHARS) return false
    return runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClipMI", text)) }.isSuccess
}

private fun share(context: Context, text: String): Boolean {
    if (text.length > MAX_TRANSFER_CHARS) return false
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return runCatching { context.startActivity(Intent.createChooser(send, "Bagikan prompt ke aplikasi chat AI")) }.isSuccess
}
