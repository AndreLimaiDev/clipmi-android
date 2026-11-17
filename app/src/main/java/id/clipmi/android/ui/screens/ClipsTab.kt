package id.clipmi.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ClipSource
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.ui.components.StatusChip
import id.clipmi.android.ui.components.Tone
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id"))

/** Tab Klip: analisis (mode manual dan Mode API), lalu kandidat klip untuk ditinjau (padanan halaman Klip desktop). */
@Composable
fun ClipsTab(container: AppContainer, project: ProjectEntity, hasTranscript: Boolean, playRange: (Double, Double) -> Unit) {
    val clips by container.database.clips().observe(project.id).collectAsState(initial = emptyList())
    val series by container.database.series().observe(project.id).collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("active") }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ClipEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (project.transcript != TranscriptStatus.Ready || !hasTranscript) {
            Text("Transkrip belum siap. Analisis AI dimulai setelah transkrip tersedia.", style = MaterialTheme.typography.bodyMedium)
        } else {
            AnalysisPanel(container, project, onNotice = { notice = it })
        }

        val counts = mapOf(
            "active" to clips.count { it.review != ReviewStatus.Rejected },
            "selected" to clips.count { it.review == ReviewStatus.Selected },
            "rejected" to clips.count { it.review == ReviewStatus.Rejected },
        )
        if (clips.isNotEmpty()) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("active" to "Aktif", "selected" to "Terpilih", "rejected" to "Dibuang").forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text("$label (${counts[key]})") })
                }
            }
            Text(
                "Geser kartu ke kanan untuk memilih, ke kiri untuk membuang.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val shown = clips.filter {
            when (filter) {
                "selected" -> it.review == ReviewStatus.Selected
                "rejected" -> it.review == ReviewStatus.Rejected
                else -> it.review != ReviewStatus.Rejected
            }
        }
        shown.forEach { clip ->
            ClipCard(
                container, clip, series.firstOrNull { it.id == clip.clipSeriesId }?.title,
                onPlay = { playRange(clip.startTime, clip.endTime) },
                onEdit = { editing = clip },
                onCopy = {
                    copy(context, ClipText.shareText(clip.title, clip.partNumber, clip.caption, container.analysis.decodeList(clip.hashtags)))
                    notice = "Judul, caption, dan hashtag disalin."
                },
            )
        }

        if (clips.any { it.source == ClipSource.AI && it.review == ReviewStatus.Candidate }) {
            TextButton(onClick = {
                scope.launch {
                    val count = container.database.clips().deleteUnreviewed(project.id)
                    notice = "$count kandidat yang belum ditinjau dihapus."
                }
            }) { Text("Hapus kandidat yang belum ditinjau", color = MaterialTheme.colorScheme.error) }
        }
    }

    editing?.let { clip -> ClipEditDialog(container, clip, onDone = { editing = null }) }
    notice?.let {
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, text = { Text(it) })
    }
}

/** Mode manual (salin/bagikan prompt, tempel balasan) dan Mode API. */
@Composable
private fun AnalysisPanel(container: AppContainer, project: ProjectEntity, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var reply by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val prompts by produceState(emptyList<String>(), project.transcriptStatus, project.updatedAt) { value = container.analysis.prompts(project.id) }
    val config by produceState<id.clipmi.android.domain.AiConfig?>(null, project.analysisStatus) { value = container.settings.analysis() }
    val resumable by produceState<List<Int>>(emptyList(), prompts, project.analysisParts, project.analysisStatus) {
        value = if (AnalysisStatus.isActive(project.analysisStatus) || prompts.size < 2) emptyList() else {
            val done = container.analysis.completedParts(project.id, prompts)
            if (done.isEmpty()) emptyList() else prompts.indices.filter { it !in done }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mode manual (tanpa biaya API)", style = MaterialTheme.typography.titleSmall)
            val tokens = prompts.sumOf { PromptBuilder.estimateTokens(it) }
            Text(
                "Salin atau bagikan prompt ke Claude, ChatGPT, atau Gemini, lalu tempel balasan JSON-nya di bawah." +
                    (if (prompts.size > 1) " Transkrip panjang dibagi ${prompts.size} bagian; kirim satu per satu." else "") +
                    " Total sekitar ${number.format(tokens)} token.",
                style = MaterialTheme.typography.bodySmall,
            )
            prompts.forEachIndexed { i, prompt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (prompts.size > 1) "Bagian ${i + 1}" else "Prompt") + " · ${number.format(PromptBuilder.estimateTokens(prompt))} token",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onNotice(if (copy(context, prompt)) "Prompt disalin. Tempel ke aplikasi chat AI." else TOO_LARGE)
                    }) { Text("Salin") }
                    TextButton(onClick = { if (!share(context, prompt)) onNotice(TOO_LARGE) }) { Text("Bagikan") }
                }
            }
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it; replyError = null },
                label = { Text("Tempel balasan JSON") },
                isError = replyError != null,
                supportingText = replyError?.let { { Text(it) } },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { reply = it; replyError = null }
                }) { Text("Tempel dari clipboard") }
                Button(onClick = {
                    importErrors = emptyList()
                    if (reply.isBlank()) {
                        replyError = "Tempel balasan JSON dari AI dulu."
                        return@Button
                    }
                    scope.launch {
                        try {
                            val result = container.analysis.importReply(project.id, reply)
                            importErrors = result.errors
                            onNotice(result.message())
                            if (result.created > 0) reply = ""
                        } catch (e: AnalysisResultException) {
                            replyError = e.message
                        }
                    }
                }) { Text("Simpan kandidat") }
            }
            importErrors.takeIf { it.isNotEmpty() }?.let { errors ->
                Text("Ditolak:\n" + errors.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Mode API", style = MaterialTheme.typography.titleSmall)
            val active = AnalysisStatus.isActive(project.analysisStatus)
            val cfg = config
            when {
                active -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    project.analysisMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { scope.launch { container.analysis.cancel(project.id) } }) { Text("Batalkan") }
                }
                cfg == null -> Text("Atur penyedia AI dan API key di Pengaturan → Analisis AI untuk analisis otomatis.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    project.analysisMessage?.let { message ->
                        val color = when (project.analysisStatus) {
                            AnalysisStatus.DONE -> Color(0xFF15803D)
                            AnalysisStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(message, color = color, style = MaterialTheme.typography.bodySmall)
                    }
                    val estimate = container.analysis.estimate(prompts, cfg, resumable.ifEmpty { null })
                    Text(
                        "${cfg.provider.label} · ${cfg.model} · sekitar ${number.format(estimate.tokens)} token" +
                            (estimate.cost?.let { ", ±$" + "%.${if (it < 0.1) 3 else 2}f".format(Locale.ROOT, it) } ?: "") +
                            (if (resumable.isNotEmpty()) " untuk ${resumable.size} bagian tersisa" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (resumable.isNotEmpty()) {
                            Button(onClick = { scope.launch { container.analysis.start(project.id, resume = true) } }) { Text("Lanjutkan") }
                        }
                        OutlinedButton(enabled = prompts.isNotEmpty(), onClick = { scope.launch { container.analysis.start(project.id) } }) {
                            Text("Analisis dengan ${cfg.provider.label.substringBefore(" (")}")
                        }
                        if (project.analysisMessage != null) TextButton(onClick = { scope.launch { container.analysis.dismiss(project.id) } }) { Text("Tutup pesan") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(container: AppContainer, clip: ClipEntity, seriesTitle: String?, onPlay: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    val setReview: (ReviewStatus) -> Unit = { status -> scope.launch { container.database.clips().setReview(clip.id, status.value) } }

    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            val (color, label) = when (swipe.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF15803D) to "Pilih"
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error to "Buang"
                else -> Color.Transparent to ""
            }
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> setReview(ReviewStatus.Selected)
                SwipeToDismissBoxValue.EndToStart -> setReview(ReviewStatus.Rejected)
                else -> Unit
            }
            scope.launch { swipe.reset() }
        },
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    clip.score?.let { StatusChip("Skor $it", if (it >= 80) Tone.Good else if (it >= 60) Tone.Busy else Tone.Idle) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Timecode.format(clip.startTime)}–${Timecode.format(clip.endTime)} · ${Timecode.format(clip.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (clip.review) {
                        ReviewStatus.Selected -> StatusChip("Terpilih", Tone.Good)
                        ReviewStatus.Rejected -> StatusChip("Dibuang", Tone.Bad)
                        ReviewStatus.Candidate -> Unit
                    }
                    if (clip.source == ClipSource.MANUAL) StatusChip("Manual", Tone.Idle)
                }
                seriesTitle?.let { Text("Seri: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                clip.hook?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                clip.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                container.analysis.decodeList(clip.hashtags).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                container.analysis.decodeList(clip.keywords).takeIf { it.isNotEmpty() }?.let {
                    Text("Kata kunci: " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                clip.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPlay) { Text("Putar") }
                    when (clip.review) {
                        ReviewStatus.Candidate -> {
                            TextButton(onClick = { setReview(ReviewStatus.Selected) }) { Text("Pilih") }
                            TextButton(onClick = { setReview(ReviewStatus.Rejected) }) { Text("Buang") }
                        }
                        ReviewStatus.Selected -> TextButton(onClick = { setReview(ReviewStatus.Candidate) }) { Text("Batal pilih") }
                        ReviewStatus.Rejected -> TextButton(onClick = { setReview(ReviewStatus.Candidate) }) { Text("Pulihkan") }
                    }
                    TextButton(onClick = onEdit) { Text("Sunting") }
                    TextButton(onClick = onCopy) { Text("Salin teks") }
                }
            }
        }
    }
}

/** Sunting teks kandidat (judul, hook, caption, hashtag, kata kunci) atau hapus klip. */
@Composable
private fun ClipEditDialog(container: AppContainer, clip: ClipEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(clip.title) }
    var hook by remember { mutableStateOf(clip.hook.orEmpty()) }
    var caption by remember { mutableStateOf(clip.caption.orEmpty()) }
    var hashtags by remember { mutableStateOf(container.analysis.decodeList(clip.hashtags).joinToString(" ")) }
    var keywords by remember { mutableStateOf(container.analysis.decodeList(clip.keywords).joinToString(", ")) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Sunting klip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hook, { hook = it }, label = { Text("Hook (teks pembuka di layar)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caption, { caption = it }, label = { Text("Caption") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hashtags, { hashtags = it }, label = { Text("Hashtag") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Kata kunci (pisahkan dengan koma)") }, modifier = Modifier.fillMaxWidth())
                if (confirmDelete) {
                    Text("Ketuk Hapus sekali lagi untuk menghapus klip ini.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                scope.launch {
                    val tags = id.clipmi.core.analysis.ClipImporter.hashtags(kotlinx.serialization.json.JsonPrimitive(hashtags))
                    val words = Keywords.clean(keywords.split(','))
                    container.database.clips().update(
                        clip.copy(
                            title = title.trim(),
                            hook = hook.trim().ifEmpty { null },
                            caption = caption.trim().ifEmpty { null },
                            hashtags = container.analysis.encodeList(tags),
                            keywords = words.takeIf { it.isNotEmpty() }?.let(container.analysis::encodeList),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    onDone()
                }
            }) { Text("Simpan") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (!confirmDelete) confirmDelete = true else scope.launch { container.database.clips().delete(clip.id); onDone() }
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Batal") }
            }
        },
    )
}

private const val TOO_LARGE = "Prompt terlalu besar untuk disalin/dibagikan sekaligus di HP. Kecilkan \"Batas panjang prompt\" di Pengaturan → Analisis AI agar transkrip dibagi menjadi beberapa bagian."

/** Batas aman ukuran teks lewat clipboard/intent (Binder ±1 MB, teks UTF-16). */
private const val MAX_TRANSFER_CHARS = 300_000

private fun copy(context: Context, text: String): Boolean {
    if (text.length > MAX_TRANSFER_CHARS) return false
    return runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClipMI", text)) }.isSuccess
}

private fun share(context: Context, text: String): Boolean {
    if (text.length > MAX_TRANSFER_CHARS) return false
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return runCatching { context.startActivity(Intent.createChooser(send, "Bagikan prompt ke aplikasi chat AI")) }.isSuccess
}
