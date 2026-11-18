package id.clipmi.android.ui.screens

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

/** Satu label status terpenting untuk kartu project. */
private fun ProjectEntity.badge(): Pair<String, Tone> = when {
    video == VideoStatus.Failed || transcript == TranscriptStatus.Failed -> "Gagal" to Tone.Bad
    video == VideoStatus.Downloading -> "Mengunduh ${videoProgress ?: 0}%" to Tone.Busy
    video == VideoStatus.Processing -> "Memproses video" to Tone.Busy
    video == VideoStatus.Pending -> "Menunggu" to Tone.Idle
    transcript == TranscriptStatus.Fetching -> (if (transcriptionMessage != null) "Transkripsi" else "Mengambil transkrip") to Tone.Busy
    AnalysisStatus.isActive(analysisStatus) -> "Analisis AI" to Tone.Busy
    transcript == TranscriptStatus.NeedsTranscript -> "Butuh transkrip" to Tone.Warn
    video == VideoStatus.Deleted -> "Video sumber dihapus" to Tone.Warn
    transcript == TranscriptStatus.Ready -> "Transkrip siap" to Tone.Good
    else -> transcript.label to Tone.Idle
}

@Composable
fun ProjectListScreen(container: AppContainer, onOpen: (Long) -> Unit, onOpenResults: () -> Unit = {}) {
    val projects by container.database.projects().observeAll().collectAsState(initial = null)
    val clipCounts by container.database.clips().observeCounts().collectAsState(initial = emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ProjectFilter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val sizes by produceState(emptyMap<Long, Long>(), projects) {
        val list = projects ?: return@produceState
        value = withContext(Dispatchers.IO) { list.associate { it.id to container.workspace.projectSize(it.id) } }
    }

    Box(Modifier.fillMaxSize()) {
        val all = projects
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FloatingNavSpace + 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScreenHeader("Project", Modifier.padding(horizontal = ScreenGutter), eyebrow = "ClipMI") {
                        IconCircle(ClipMiIcons.Film, "Hasil render", onClick = onOpenResults)
                        IconCircle(
                            if (searching) ClipMiIcons.Close else ClipMiIcons.Search,
                            if (searching) "Tutup pencarian" else "Cari project",
                            onClick = { searching = !searching; if (!searching) query = "" },
                        )
                    }
                    AnimatedVisibility(searching) {
                        SearchField(query, { query = it }, "Cari judul project", Modifier.padding(horizontal = ScreenGutter), onClear = { query = "" })
                    }
                    if (!all.isNullOrEmpty()) {
                        Row(Modifier.padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(all.size.toString(), "project", Modifier.weight(1f))
                            StatTile(clipCounts.sumOf { it.total }.toString(), "kandidat klip", Modifier.weight(1f))
                            val (value, unit) = formatBytes(sizes.values.sum()).split(' ').let { it[0] to it.getOrNull(1) }
                            StatTile(value, "dipakai", Modifier.weight(1f), unit = unit)
                        }
                        ChipRow {
                            ProjectFilter.entries.forEach { f ->
                                val count = all.count(f::matches)
                                if (f == ProjectFilter.All || count > 0 || filter == f) {
                                    FilterPill("${f.label} · $count", selected = filter == f, onClick = { filter = f })
                                }
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
                        "Tempel link YouTube atau pilih video dari galeri. Dari aplikasi YouTube, ketuk Bagikan → ClipMI.",
                        Modifier.heightIn(min = 420.dp),
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
        FloatingPill(
            "Project baru", ClipMiIcons.Plus, onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = ScreenGutter, bottom = FloatingNavSpace),
        )
    }

    if (creating) NewProjectSheet(container, onDismiss = { creating = false }, onCreated = { id -> creating = false; id?.let(onOpen) })
}

@Composable
private fun ProjectCard(container: AppContainer, project: ProjectEntity, clipCount: Int, size: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    ClipMiCard(modifier, onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(116.dp).height(76.dp).clip(RoundedCornerShape(16.dp))) {
                FileImage(container.workspace.file(project.thumbnailPath), Modifier.fillMaxSize())
                project.durationSeconds?.let {
                    Text(
                        Timecode.format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (project.isYouTube) "YouTube" else "Galeri", size.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (clipCount > 0) StatusBadge("$clipCount klip", Tone.Good)
                    val (label, tone) = project.badge()
                    StatusBadge(label, tone)
                }
            }
        }
        if (project.video == VideoStatus.Downloading) {
            ProgressBar(project.videoProgress?.let { it / 100f })
        } else if (project.video == VideoStatus.Processing || project.transcript == TranscriptStatus.Fetching) {
            ProgressBar(null, color = c.busy)
        }
    }
}

/** Project baru (panel bawah): tempel satu/beberapa link YouTube, atau pilih video dari galeri. */
@Composable
private fun NewProjectSheet(container: AppContainer, onDismiss: () -> Unit, onCreated: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    var links by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.actions.createFromLocal(uri) }
                .onSuccess(onCreated)
                .onFailure { notifier.show("Video tidak bisa dibuka: ${it.message}") }
        }
    }
    val c = ClipMi.colors

    ClipMiSheet(onDismiss, title = "Project baru", subtitle = "Dari link YouTube atau video di HP. Hanya konten yang sudah diizinkan pemiliknya.") {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
            Text("atau", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.panel)
                .border(1.dp, c.lineStrong, RoundedCornerShape(20.dp))
                .clickable(role = Role.Button) { pickVideo.launch(arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/*")) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(ClipMiIcons.Image, size = 40.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pilih video dari galeri", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text("MP4, MOV, MKV", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
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

