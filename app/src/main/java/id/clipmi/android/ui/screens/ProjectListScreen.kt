package id.clipmi.android.ui.screens

import id.clipmi.android.data.RenderStatus
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.FlowRow
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.FloatingPill
import id.clipmi.android.ui.components.IconBadge
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.ScreenHeader
import id.clipmi.android.ui.components.SearchField
import id.clipmi.android.ui.components.StatTile
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.components.formatBytes
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filter Beranda; urutan sama dengan baris chip. */
private enum class ProjectFilter(val label: String) {
    All("Semua"),
    Busy("Diproses"),
    Ready("Siap"),
    Action("Perlu tindakan"),
    Failed("Gagal"),
    Local("Dari galeri");

    fun matches(p: ProjectEntity) = when (this) {
        All -> true
        Busy -> p.isBusy()
        Ready -> p.video == VideoStatus.Ready && p.transcript == TranscriptStatus.Ready
        Action -> !p.isBusy() && !p.isFailed() && (p.transcript == TranscriptStatus.NeedsTranscript || p.video == VideoStatus.Deleted)
        Failed -> p.isFailed()
        Local -> !p.isYouTube
    }
}

private fun ProjectEntity.isBusy() =
    video == VideoStatus.Downloading || video == VideoStatus.Processing || video == VideoStatus.Pending ||
        transcript == TranscriptStatus.Fetching || AnalysisStatus.isActive(analysisStatus)

private fun ProjectEntity.isFailed() = video == VideoStatus.Failed || transcript == TranscriptStatus.Failed

@Composable
fun ProjectListScreen(container: AppContainer, onOpen: (Long) -> Unit, onOpenResults: () -> Unit = {}) {
    val projects by container.database.projects().observeAll().collectAsState(initial = null)
    val clipCounts by container.database.clips().observeCounts().collectAsState(initial = emptyList())
    val rendered by container.database.clips().observeRendered().collectAsState(initial = emptyList())
    var linking by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ProjectFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.actions.createFromLocal(uri) }
                .onSuccess { it?.let(onOpen) }
                .onFailure { notifier.show("Video tidak bisa dibuka: ${it.message}") }
        }
    }
    val sizes by produceState(emptyMap<Long, Long>(), projects) {
        val list = projects ?: return@produceState
        value = withContext(Dispatchers.IO) { list.associate { it.id to container.workspace.projectSize(it.id) } }
    }

    val all = projects
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingNavSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ScreenHeader("Project", Modifier.padding(horizontal = ScreenGutter))
                // Dua pintu masuk langsung: link YouTube (panel tempel) atau video dari galeri (langsung pemilih file).
                Row(Modifier.padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntryCard(ClipMiIcons.Link, "Link YouTube", "Tempel link", Modifier.weight(1f)) { linking = true }
                    EntryCard(ClipMiIcons.Image, "Dari galeri", "MP4, MOV, MKV", Modifier.weight(1f)) {
                        pickVideo.launch(arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/*"))
                    }
                }
                if (rendered.isNotEmpty()) {
                    val done = rendered.count { it.render == RenderStatus.Done }
                    val busy = rendered.count { it.render.isBusy }
                    ClipMiCard(Modifier.padding(horizontal = ScreenGutter), onClick = onOpenResults, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(ClipMiIcons.Film, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Hasil render", style = MaterialTheme.typography.titleSmall, color = ClipMi.colors.text)
                                Text(
                                    listOfNotNull("$done video siap", busy.takeIf { it > 0 }?.let { "$it sedang dirender" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClipMi.colors.textMuted,
                                )
                            }
                            Icon(ClipMiIcons.Chevron, null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (!all.isNullOrEmpty()) {
                    if (all.size > 3) {
                        SearchField(query, { query = it }, "Cari judul project", Modifier.padding(horizontal = ScreenGutter), onClear = { query = "" })
                    }
                    val filters = ProjectFilter.entries.filter { f -> f == ProjectFilter.All || filter == f || all.any(f::matches) }
                    if (filters.size > 1) {
                        ChipRow {
                            filters.forEach { f -> FilterPill("${f.label} · ${all.count(f::matches)}", selected = filter == f, onClick = { filter = f }) }
                        }
                    }
                }
            }
        }
        when {
            all == null -> Unit
            all.isEmpty() -> item {
                EmptyState(
                    "Belum ada project",
                    "Mulai dari link YouTube atau video di galeri. Dari aplikasi YouTube, ketuk Bagikan → ClipMI.",
                    Modifier.heightIn(min = 320.dp),
                    icon = ClipMiIcons.Film,
                )
            }
            else -> {
                val shown = all.filter { filter.matches(it) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
                if (shown.isEmpty()) item {
                    Text(
                        if (query.isNotBlank()) "Tidak ada project berjudul \"${query.trim()}\"." else "Tidak ada project di filter ini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClipMi.colors.textMuted,
                        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 24.dp),
                    )
                }
                items(shown, key = { it.id }) { p ->
                    ProjectCard(
                        container, p,
                        clipCount = clipCounts.firstOrNull { it.projectId == p.id }?.total ?: 0,
                        size = sizes[p.id] ?: 0,
                        onClick = { onOpen(p.id) },
                        modifier = Modifier.padding(horizontal = ScreenGutter).animateItem(),
                    )
                }
            }
        }
    }

    if (linking) NewProjectSheet(container, onDismiss = { linking = false }, onCreated = { id -> linking = false; id?.let(onOpen) })
}

/** Pintu masuk project baru di atas daftar. */
@Composable
private fun EntryCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = ClipMi.colors
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Satu tahap project di kartu: centang bila selesai, titik berwarna bila sedang berjalan atau perlu tindakan. */
private data class Step(val label: String, val tone: Tone)

private fun ProjectEntity.steps(clipCount: Int): List<Step> = listOf(
    when (video) {
        VideoStatus.Ready -> Step("Video", Tone.Good)
        VideoStatus.Downloading -> Step("Video ${videoProgress ?: 0}%", Tone.Busy)
        VideoStatus.Processing, VideoStatus.Pending -> Step("Video", Tone.Busy)
        VideoStatus.Failed -> Step("Video gagal", Tone.Bad)
        VideoStatus.Deleted -> Step("Video dihapus", Tone.Warn)
    },
    when (transcript) {
        TranscriptStatus.Ready -> Step("Transkrip", Tone.Good)
        TranscriptStatus.Fetching -> Step(if (transcriptionMessage != null) "Transkripsi" else "Transkrip", Tone.Busy)
        TranscriptStatus.NeedsTranscript -> Step("Butuh transkrip", Tone.Warn)
        TranscriptStatus.Failed -> Step("Transkrip gagal", Tone.Bad)
        else -> Step(transcript.label, Tone.Idle)
    },
    when {
        AnalysisStatus.isActive(analysisStatus) -> Step("Analisis AI", Tone.Busy)
        clipCount > 0 -> Step("$clipCount klip", Tone.Good)
        else -> Step("Belum ada klip", Tone.Idle)
    },
)

@Composable
private fun ProjectCard(container: AppContainer, project: ProjectEntity, clipCount: Int, size: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    ClipMiCard(modifier, onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).height(72.dp).clip(RoundedCornerShape(14.dp))) {
                FileImage(container.workspace.file(project.thumbnailPath), Modifier.fillMaxSize())
                project.durationSeconds?.let {
                    Text(
                        Timecode.format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (project.isYouTube) "YouTube" else "Galeri", size.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    project.steps(clipCount).forEach { step -> StepMark(step) }
                }
            }
        }
        if (project.video == VideoStatus.Downloading) {
            ProgressBar(project.videoProgress?.let { it / 100f })
        } else if (project.video == VideoStatus.Processing || project.transcript == TranscriptStatus.Fetching || AnalysisStatus.isActive(project.analysisStatus)) {
            ProgressBar(null, color = c.busy)
        }
    }
}

@Composable
private fun StepMark(step: Step) {
    val c = ClipMi.colors
    val color = when (step.tone) {
        Tone.Good -> c.accent
        Tone.Busy -> c.busy
        Tone.Warn -> c.warn
        Tone.Bad -> c.bad
        Tone.Idle -> c.textMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (step.tone == Tone.Good) {
            Icon(ClipMiIcons.Check, null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Text(step.label, style = MaterialTheme.typography.labelMedium, color = if (step.tone == Tone.Good) c.textBody else color, maxLines = 1)
    }
}

/** Project baru dari link YouTube (panel bawah): satu atau beberapa link sekaligus. */
@Composable
private fun NewProjectSheet(container: AppContainer, onDismiss: () -> Unit, onCreated: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    var links by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val c = ClipMi.colors

    ClipMiSheet(onDismiss, title = "Project dari YouTube", subtitle = "Hanya konten yang sudah diizinkan pemiliknya.") {
        Field(
            links, { links = it; error = null },
            label = "Link YouTube",
            placeholder = "https://youtu.be/…",
            supporting = "Bisa beberapa link sekaligus, satu per baris (maks. 30).",
            error = error,
            maxLines = 5,
            leadingIcon = ClipMiIcons.Link,
            trailing = {
                Box(
                    Modifier.padding(end = 6.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(c.panel)
                        .clickable(role = Role.Button) {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                                links = if (links.isBlank()) it.trim() else links.trimEnd() + "\n" + it.trim()
                                error = null
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Tempel", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = c.text) }
            },
        )
        PrimaryButton(
            if (busy) "Membuat…" else "Buat project",
            enabled = links.isNotBlank() && !busy,
            height = 52.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    val result = container.actions.createFromYouTube(links)
                    busy = false
                    when {
                        result.created.isEmpty() -> error = result.skipped.joinToString("; ").ifEmpty { "Tidak ada link video YouTube yang dikenali." }
                        result.created.size == 1 -> onCreated(result.created[0])
                        else -> {
                            notifier.show("${result.created.size} project dibuat" + if (result.skipped.isNotEmpty()) "; ${result.skipped.size} link dilewati." else ".")
                            onCreated(null)
                        }
                    }
                }
            },
        )
        Text(
            "Tip: dari aplikasi YouTube, ketuk Bagikan → ClipMI.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

