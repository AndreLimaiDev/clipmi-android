package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.launch

/** Warna utama ClipMI (sama dengan token Android: emerald). */
private val Accent = Color(0xFF34D399)
private val AccentDark = Color(0xFF047857)
private val Danger = Color(0xFFE5484D)

@Composable
fun App(repo: Repository, engine: MediaEngine) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Accent, onPrimary = Color(0xFF04342C), background = Color(0xFF0B0F0E), surface = Color(0xFF111614))
    } else {
        lightColorScheme(primary = AccentDark, onPrimary = Color.White, background = Color(0xFFF4F6F5), surface = Color.White)
    }
    val scope = rememberCoroutineScope()
    val downloads = remember { Downloads(repo, engine, scope) }
    val renders = remember { Renders(IosRenderer(repo, engine), scope) }
    var opened by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var backingUp by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        restored = finishRestore(repo)
        repo.recoverInterrupted()
    }

    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            val project = opened
            val clip = editing
            when {
                clip != null -> ClipEditorScreen(repo, renders, clip, onBack = { editing = null })
                backingUp -> BackupScreen(repo, engine, onBack = { backingUp = false })
                project == null -> ProjectListScreen(
                    repo, downloads, restored, onOpen = { opened = it }, onBackup = { backingUp = true }, onDismissRestored = { restored = null },
                )
                else -> ProjectScreen(repo, downloads, renders, project, onBack = { opened = null }, onEdit = { editing = it })
            }
        }
    }
}

@Composable
private fun ProjectListScreen(
    repo: Repository, downloads: Downloads, restored: String?, onOpen: (Long) -> Unit, onBackup: () -> Unit, onDismissRestored: () -> Unit,
) {
    val projects by remember { repo.db.projects().observeAll() }.collectAsState(emptyList())
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    fun start() {
        scope.launch {
            val created = repo.createYouTube(link.trim())
            if (created == null) {
                linkError = "Bukan link video YouTube."
                return@launch
            }
            link = ""
            val (id, isNew) = created
            if (isNew) downloads.start(id) else onOpen(id)
        }
    }

    val c = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ClipMI", color = c.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Project", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = c.onBackground)
                }
                Text(
                    "Cadangan", color = c.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onBackup).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (restored != null) {
            item {
                Card(Modifier.clickable(onClick = onDismissRestored)) {
                    Text(restored, color = c.primary, fontWeight = FontWeight.SemiBold)
                    Text("Ketuk untuk menutup.", fontSize = 12.sp, color = c.muted)
                }
            }
        }
        item {
            Card {
                Text("Link YouTube", fontWeight = FontWeight.Bold, color = c.onSurface)
                OutlinedTextField(
                    link, { link = it; linkError = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://youtu.be/…") },
                    singleLine = true,
                    isError = linkError != null,
                    supportingText = linkError?.let { { Text(it) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Tempel", Modifier.weight(1f)) { clipboard.getText()?.text?.let { link = it; linkError = null } }
                    PrimaryButton("Buat project", Modifier.weight(1f), enabled = downloads.active == null && link.isNotBlank()) { start() }
                }
                if (downloads.active != null) {
                    Text("Satu video diunduh pada satu waktu. Biarkan aplikasi tetap terbuka sampai selesai.", fontSize = 12.sp, color = c.muted)
                }
            }
        }
        if (projects.isEmpty()) {
            item {
                Text("Belum ada project. Tempel link video kajian di atas.", color = c.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(top = 24.dp))
            }
        }
        items(projects, key = { it.id }) { project ->
            Card(Modifier.clickable { onOpen(project.id) }) {
                Text(project.title, fontWeight = FontWeight.Bold, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(projectMeta(project), fontSize = 13.sp, color = c.muted)
                ProjectStatus(project, downloads)
            }
        }
    }
}

private fun projectMeta(project: ProjectEntity) = listOfNotNull(
    if (project.isYouTube) "YouTube" else "Video",
    project.durationSeconds?.let { Timecode.format(it) },
).joinToString(" · ")

/** Progres unduhan, atau status video dan transkrip. */
@Composable
private fun ProjectStatus(project: ProjectEntity, downloads: Downloads) {
    val c = MaterialTheme.colorScheme
    if (downloads.active == project.id) {
        LinearProgressIndicator(progress = { downloads.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.primary)
        Text(downloads.status, fontSize = 13.sp, color = c.muted)
        return
    }
    val video = when (project.video) {
        VideoStatus.Ready -> "Video siap" to c.primary
        VideoStatus.Failed -> "Video gagal" to Danger
        else -> "Video ${project.video.label.lowercase()}" to c.muted
    }
    val transcript = when (project.transcript) {
        TranscriptStatus.Ready -> "Transkrip siap" to c.primary
        TranscriptStatus.Failed -> "Transkrip gagal" to Danger
        TranscriptStatus.NeedsTranscript -> "Tanpa subtitle Indonesia" to c.muted
        else -> "Transkrip ${project.transcript.label.lowercase()}" to c.muted
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, color) in listOf(video, transcript)) {
            Text(label, fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
    project.errorMessage?.let { Text(it, fontSize = 12.sp, color = Danger, maxLines = 4, overflow = TextOverflow.Ellipsis) }
}

private enum class ProjectTab(val label: String) { Clips("Klip"), Transcript("Transkrip") }

@Composable
private fun ProjectScreen(repo: Repository, downloads: Downloads, renders: Renders, id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val project by remember(id) { repo.db.projects().observe(id) }.collectAsState(null)
    val clips by remember(id) { repo.db.clips().observe(id) }.collectAsState(emptyList())
    val segments by remember(id) { repo.db.segments().observe(id) }.collectAsState(emptyList())
    var tab by remember { mutableStateOf(ProjectTab.Clips) }
    val c = MaterialTheme.colorScheme
    val current = project

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "‹ Project", color = c.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
                )
                Text(current?.title ?: "", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = c.onBackground)
                if (current != null) Text(projectMeta(current), fontSize = 13.sp, color = c.muted)
            }
        }
        if (current == null) return@LazyColumn
        item {
            Card {
                ProjectStatus(current, downloads)
                val failed = current.video == VideoStatus.Failed || current.transcript == TranscriptStatus.Failed
                if (failed && current.isYouTube && downloads.active == null) {
                    SecondaryButton("Unduh ulang", Modifier.fillMaxWidth()) { downloads.start(current.id) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (entry in ProjectTab.entries) {
                    val selected = entry == tab
                    val label = if (entry == ProjectTab.Clips) "${entry.label} (${clips.size})" else "${entry.label} (${segments.size})"
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) c.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { tab = entry }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) c.primary else c.muted)
                    }
                }
            }
        }
        when (tab) {
            ProjectTab.Clips -> {
                item { AnalysisCard(repo, current) }
                if (clips.isEmpty()) {
                    item { Text("Belum ada klip.", color = c.muted, modifier = Modifier.padding(top = 8.dp)) }
                }
                items(clips, key = { it.id }) { ClipCard(repo, renders, it, onEdit) }
            }
            ProjectTab.Transcript -> {
                if (segments.isEmpty()) {
                    item { Text("Transkrip belum ada.", color = c.muted, modifier = Modifier.padding(top = 8.dp)) }
                }
                items(segments, key = { it.id }) { SegmentRow(it) }
            }
        }
    }
}

/** Analisis manual: prompt disalin ke chat AI, balasan JSON ditempel lalu disimpan sebagai kandidat. */
@Composable
private fun AnalysisCard(repo: Repository, project: ProjectEntity) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var prompts by remember { mutableStateOf<List<String>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(project.id, project.transcriptStatus) { prompts = repo.prompts(project.id) }

    Card {
        Text("Pilih momen dengan AI", fontWeight = FontWeight.Bold, color = c.onSurface)
        if (project.transcript != TranscriptStatus.Ready || prompts.isEmpty()) {
            Text("Transkrip belum siap.", fontSize = 13.sp, color = c.muted)
            return@Card
        }
        Text(
            "Salin prompt, tempel di ChatGPT atau Gemini, lalu tempel balasan JSON-nya di bawah." +
                if (prompts.size > 1) " Transkrip panjang dibagi ${prompts.size} bagian; kirim satu per satu." else "",
            fontSize = 13.sp, color = c.muted,
        )
        prompts.forEachIndexed { index, prompt ->
            val label = if (prompts.size == 1) "Salin prompt" else "Salin bagian ${index + 1} dari ${prompts.size}"
            SecondaryButton(label, Modifier.fillMaxWidth()) {
                clipboard.setText(AnnotatedString(prompt))
                message = "Prompt${if (prompts.size > 1) " bagian ${index + 1}" else ""} disalin." to true
            }
        }
        OutlinedTextField(
            reply, { reply = it; message = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tempel balasan JSON di sini") },
            minLines = 4,
            maxLines = 8,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Tempel", Modifier.weight(1f)) { clipboard.getText()?.text?.let { reply = it; message = null } }
            PrimaryButton("Simpan kandidat", Modifier.weight(1f), enabled = reply.isNotBlank()) {
                scope.launch {
                    message = try {
                        val outcome = repo.importReply(project.id, reply)
                        reply = ""
                        outcome.message() to true
                    } catch (e: AnalysisResultException) {
                        (e.message ?: "Balasan tidak berisi JSON klip.") to false
                    }
                }
            }
        }
        message?.let { (text, ok) -> Text(text, fontSize = 13.sp, color = if (ok) c.primary else Danger) }
    }
}

@Composable
private fun ClipCard(repo: Repository, renders: Renders, clip: ClipEntity, onEdit: (Long) -> Unit) {
    val c = MaterialTheme.colorScheme
    Card {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(clip.title, fontWeight = FontWeight.Bold, color = c.onSurface, modifier = Modifier.weight(1f))
            clip.score?.let {
                Text(
                    "$it", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.primary.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        val seconds = (clip.endTime - clip.startTime).toLong()
        Text("${Timecode.format(clip.startTime)} – ${Timecode.format(clip.endTime)} · $seconds dtk", fontSize = 13.sp, color = c.muted)
        clip.hook?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = c.onSurface) }
        val tags = repo.hashtags(clip)
        if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#" + it.removePrefix("#") }, fontSize = 12.sp, color = c.muted)
        SecondaryButton("Edit klip", Modifier.fillMaxWidth()) { onEdit(clip.id) }
        RenderRow(renders, clip)
    }
}

@Composable
private fun SegmentRow(segment: TranscriptSegmentEntity) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Timecode.format(segment.startTime), fontSize = 12.sp, color = c.primary, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Text(segment.text, fontSize = 14.sp, color = c.onBackground, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).then(modifier).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(label) }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp)) { Text(label) }
}

private val ColorScheme.muted: Color get() = onSurface.copy(alpha = 0.6f)

/** Tombol render, progres, dan status hasil (tersimpan di Photos / gagal). */
@Composable
private fun RenderRow(renders: Renders, clip: ClipEntity) {
    val c = MaterialTheme.colorScheme
    if (renders.active == clip.id) {
        LinearProgressIndicator(progress = { renders.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka",
                fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f),
            )
            SecondaryButton("Batal") { renders.cancel() }
        }
        return
    }
    when (RenderStatus.from(clip.renderStatus)) {
        RenderStatus.Done -> Text("Sudah dirender · tersimpan di Photos", fontSize = 13.sp, color = c.primary, fontWeight = FontWeight.SemiBold)
        RenderStatus.Failed -> Text(clip.renderError ?: "Render gagal.", fontSize = 12.sp, color = Danger, maxLines = 4, overflow = TextOverflow.Ellipsis)
        else -> {}
    }
    val label = if (RenderStatus.from(clip.renderStatus) == RenderStatus.Done) "Render ulang" else "Render"
    PrimaryButton(label, Modifier.fillMaxWidth(), enabled = renders.active == null) { renders.start(clip.id) }
}
