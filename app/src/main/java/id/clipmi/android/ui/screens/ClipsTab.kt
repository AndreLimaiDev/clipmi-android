package id.clipmi.android.ui.screens

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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
fun ClipsTab(container: AppContainer, project: ProjectEntity, hasTranscript: Boolean, playRange: (Double, Double) -> Unit, onOpenEditor: (Long) -> Unit = {}) {
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
            AnalysisCard(container, project)
        }

        if (clips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewFilter.entries.forEach { f ->
                    FilterPill("${f.label} ${clips.count(f::matches)}", selected = filter == f, height = 32.dp, onClick = { filter = f })
                }
            }
            Text("Geser kartu ke kanan untuk memilih, ke kiri untuk membuang.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }

        clips.filter(filter::matches).forEach { clip ->
            ClipCard(
                container, clip, series.firstOrNull { it.id == clip.clipSeriesId }?.title,
                onPlay = { playRange(clip.startTime, clip.endTime) },
                onEdit = { editing = clip },
                onOpen = { onOpenEditor(clip.id) },
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
        }

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
private fun AnalysisCard(container: AppContainer, project: ProjectEntity) {
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
        Field(
            reply, { reply = it; replyError = null },
            label = "Balasan JSON dari AI",
            placeholder = "{\"clips\": [ … ]}",
            error = replyError,
            minLines = 4,
            maxLines = 8,
        )
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
) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    val c = ClipMi.colors

    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.clip(MaterialTheme.shapes.large),
        backgroundContent = {
            val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toStart = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val (bg, fg) = when {
                toEnd -> (if (c.isDark) Color(0xFF065F46) else c.accentSoft) to (if (c.isDark) Color(0xFFD1FAE5) else c.accent)
                toStart -> (if (c.isDark) Color(0xFF7F1D1D) else c.badSoft) to (if (c.isDark) Color(0xFFFECACA) else c.bad)
                else -> Color.Transparent to Color.Transparent
            }
            Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 22.dp), contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart) {
                if (toEnd || toStart) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (toEnd) ClipMiIcons.Check else ClipMiIcons.Close, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                        Text(if (toEnd) "Pilih" else "Buang", color = fg, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
                    }
                }
            }
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onReview(ReviewStatus.Selected)
                SwipeToDismissBoxValue.EndToStart -> onReview(ReviewStatus.Rejected)
                else -> Unit
            }
            scope.launch { swipe.reset() }
        },
    ) {
        ClipMiCard(onClick = onOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreRing(clip.score)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.titleSmall, color = c.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Timecode.format(clip.startTime)} – ${Timecode.format(clip.endTime)} · ${Timecode.format(clip.duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                        when (clip.review) {
                            ReviewStatus.Selected -> StatusBadge("Terpilih", Tone.Good)
                            ReviewStatus.Rejected -> StatusBadge("Dibuang", Tone.Bad)
                            ReviewStatus.Candidate -> Unit
                        }
                        if (clip.source == ClipSource.MANUAL) StatusBadge("Manual", Tone.Idle)
                    }
                    seriesTitle?.let { Text("Seri: $it", style = MaterialTheme.typography.labelMedium, color = c.accent) }
                }
            }
            clip.hook?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = c.textBody) }
            clip.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            container.analysis.decodeList(clip.hashtags).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = c.accent)
            }
            container.analysis.decodeList(clip.keywords).takeIf { it.isNotEmpty() }?.let {
                Text("Kata kunci: " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            clip.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = c.textMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(ClipMiIcons.Play, "Putar klip", onPlay, size = 40.dp, background = c.panel)
                when (clip.review) {
                    ReviewStatus.Candidate -> {
                        SecondaryButton("Pilih", onClick = { onReview(ReviewStatus.Selected) }, icon = ClipMiIcons.Check, height = 40.dp, contentColor = c.accent, modifier = Modifier.weight(1f))
                        IconCircle(ClipMiIcons.Close, "Buang klip", { onReview(ReviewStatus.Rejected) }, size = 40.dp, background = c.panel)
                    }
                    ReviewStatus.Selected -> SecondaryButton("Batal pilih", onClick = { onReview(ReviewStatus.Candidate) }, height = 40.dp, modifier = Modifier.weight(1f))
                    ReviewStatus.Rejected -> SecondaryButton("Pulihkan", onClick = { onReview(ReviewStatus.Candidate) }, height = 40.dp, modifier = Modifier.weight(1f))
                }
                IconCircle(ClipMiIcons.Edit, "Sunting teks klip", onEdit, size = 40.dp, background = c.panel)
                IconCircle(ClipMiIcons.Copy, "Salin judul, caption, dan hashtag", onCopy, size = 40.dp, background = c.panel)
            }
            if (clip.review == ReviewStatus.Selected || clip.render != RenderStatus.NotRendered) RenderRow(container, clip)
        }
    }
}

/** Status render klip: render, antre/progres (bisa dibatalkan), gagal, atau hasil siap dibagikan. */
@Composable
private fun RenderRow(container: AppContainer, clip: ClipEntity) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (clip.render) {
            RenderStatus.Queued, RenderStatus.Rendering -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (clip.render == RenderStatus.Queued) clip.renderError ?: RenderSafety.batteryReason(context) ?: "Antre render…" else "Merender ${clip.renderProgress ?: 0}%",
                        style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f),
                    )
                    GhostButton("Batalkan", onClick = { scope.launch { RenderQueue.cancel(context, clip) } })
                }
                ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = c.busy)
                Text("Merender di latar · HP boleh dipakai", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            RenderStatus.Done -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(if (clip.changedAfterRender) "Hasil lama" else "Siap unggah", if (clip.changedAfterRender) Tone.Warn else Tone.Good)
                    Text("Galeri › Movies/ClipMI", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f))
                }
                if (clip.changedAfterRender) {
                    Text("Klip diubah setelah dirender; render ulang agar videonya ikut berubah.", style = MaterialTheme.typography.bodySmall, color = c.warn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Bagikan", icon = ClipMiIcons.Share, height = 44.dp, modifier = Modifier.weight(1f), onClick = {
                        val text = ClipText.shareText(clip.title, clip.partNumber, clip.caption, container.analysis.decodeList(clip.hashtags))
                        copy(context, text)
                        if (shareVideo(context, clip.outputPath, clip.title)) notifier.show("Caption & hashtag disalin. Tempel di aplikasi tujuan.")
                        else notifier.show("File hasil tidak ditemukan. Render ulang klip ini.")
                    })
                    IconCircle(ClipMiIcons.Play, "Putar hasil", { if (!openVideo(context, clip.outputPath)) notifier.show("File hasil tidak ditemukan. Render ulang klip ini.") }, size = 44.dp, background = c.panel)
                    IconCircle(ClipMiIcons.Refresh, "Render ulang", { scope.launch { RenderQueue.render(context, clip) } }, size = 44.dp, background = c.panel)
                }
            }
            RenderStatus.Failed, RenderStatus.NotRendered -> {
                clip.renderError?.takeIf { clip.render == RenderStatus.Failed }?.let {
                    Text("Render gagal: $it", style = MaterialTheme.typography.bodySmall, color = c.bad)
                }
                PrimaryButton(
                    if (clip.render == RenderStatus.Failed) "Coba render lagi" else "Render klip",
                    icon = ClipMiIcons.Film, height = 44.dp, modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { RenderQueue.render(context, clip) } },
                )
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

/** Skor AI dalam cincin: hijau ≥ 80, biru ≥ 60, abu-abu di bawahnya. */
@Composable
private fun ScoreRing(score: Int?) {
    val c = ClipMi.colors
    val color = when {
        score == null -> c.lineStrong
        score >= 80 -> c.accent
        score >= 60 -> c.busy
        else -> c.textMuted
    }
    Box(Modifier.size(46.dp).border(3.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(score?.toString() ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.text)
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
