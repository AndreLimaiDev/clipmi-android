package id.clipmi.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.StatusChip
import id.clipmi.android.ui.components.tone
import id.clipmi.android.work.TranscribeWorker
import id.clipmi.core.transcript.Timecode
import id.clipmi.core.youtube.TranscriptSource
import kotlinx.coroutines.launch

/** Konfirmasi yang sedang ditampilkan: teks dan aksinya. */
private data class Confirm(val title: String, val body: String, val action: suspend () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(container: AppContainer, projectId: Long, onBack: () -> Unit) {
    val project by container.database.projects().observe(projectId).collectAsState(initial = null)
    val segments by container.database.segments().observe(projectId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val transcriptionReady by produceState(false, project?.transcriptStatus) { value = container.settings.transcription() != null }

    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val videoFile = project?.let { container.workspace.file(it.previewPath ?: it.videoPath) }?.takeIf { project?.video == VideoStatus.Ready && it.isFile }
    LaunchedEffect(videoFile?.path) {
        if (videoFile != null) {
            player.setMediaItem(MediaItem.fromUri(videoFile.toURI().toString()))
            player.prepare()
        }
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            notice = runCatching { container.actions.importSubtitle(projectId, uri) }.fold(
                { "Transkrip diimpor: ${it.segmentCount} segmen" + if (it.correctionCount > 0) ", ${it.correctionCount} kata dikoreksi kamus." else "." },
                { it.message ?: "File subtitle tidak bisa dibaca." },
            )
        }
    }

    val p = project ?: return
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(p.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") } },
            actions = {
                IconButton(onClick = {
                    confirm = Confirm("Hapus project?", "Project, transkrip, dan video sumbernya di HP ini dihapus permanen.") {
                        container.actions.delete(projectId)
                        onBack()
                    }
                }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus project") }
            },
        )

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                if (videoFile != null) {
                    AndroidView(
                        factory = { PlayerView(it).apply { this.player = player } },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                } else {
                    FileImage(container.workspace.file(p.thumbnailPath), Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(16.dp))
                }
            }
            item { StatusSection(p, segments.size, transcriptionReady, onRetry = { scope.launch { container.actions.retry(projectId) } }) }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val open = { pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")) }
                        if (segments.isEmpty()) open() else confirm = Confirm("Ganti transkrip?", "Transkrip sekarang (${segments.size} segmen) diganti isi file subtitle.") { open() }
                    }) { Text("Impor SRT/VTT") }

                    // Transkripsi API sedang berjalan/menunggu (pengambilan subtitle YouTube tidak memakai pesan ini).
                    if (p.transcript == TranscriptStatus.Fetching && p.transcriptionMessage != null && p.video == VideoStatus.Ready) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val restore = if (segments.isNotEmpty()) TranscriptStatus.Ready else TranscriptStatus.NeedsTranscript
                                container.actions.cancelTranscription(projectId, restore)
                            }
                        }) { Text("Batalkan transkripsi") }
                    } else {
                        OutlinedButton(
                            enabled = p.video == VideoStatus.Ready && transcriptionReady,
                            onClick = {
                                val start: suspend () -> Unit = { container.actions.startTranscription(projectId) }
                                if (segments.isEmpty()) scope.launch { start() }
                                else confirm = Confirm(
                                    "Transkripsi ulang lewat API?",
                                    "Transkrip sekarang diganti hasil transkripsi API. Biaya API ditagih penyedia sesuai panjang audio.",
                                    start,
                                )
                            },
                        ) { Text("Transkripsi API") }
                    }

                    OutlinedButton(enabled = segments.isNotEmpty(), onClick = {
                        scope.launch {
                            val count = container.transcripts.applyCorrections(projectId)
                            notice = if (count > 0) "$count kata dikoreksi kamus." else "Tidak ada kata yang perlu dikoreksi."
                        }
                    }) { Text("Terapkan kamus") }
                }
            }
            if (segments.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Cari di transkrip") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                val shown = if (query.isBlank()) segments else segments.filter { it.text.contains(query.trim(), ignoreCase = true) }
                if (query.isNotBlank()) item {
                    Text("${shown.size} segmen cocok", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(shown, key = { it.id }) { segment ->
                    SegmentRow(segment, query.trim()) {
                        if (videoFile != null) {
                            player.seekTo((segment.startTime * 1000).toLong())
                            player.play()
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { c.action() } }) { Text("Lanjutkan") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } },
        )
    }
    notice?.let {
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, text = { Text(it) })
    }
}

@Composable
private fun StatusSection(p: ProjectEntity, segmentCount: Int, transcriptionReady: Boolean, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Video: ${p.video.label}", p.video.tone())
            StatusChip("Transkrip: ${p.transcript.label}", p.transcript.tone())
        }
        if (p.video == VideoStatus.Downloading) {
            LinearProgressIndicator(progress = { (p.videoProgress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Mengunduh video ${p.videoProgress ?: 0}%", style = MaterialTheme.typography.bodySmall)
        }
        if (p.video == VideoStatus.Processing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        val source = TranscriptSource.from(p.transcriptSource)
        if (p.transcript == TranscriptStatus.Ready) {
            Text("$segmentCount segmen" + (source?.let { " · ${it.label}" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        if (p.transcript == TranscriptStatus.NeedsTranscript) {
            Text(
                "Video ini tidak punya subtitle Indonesia. Impor SRT/VTT, atau jalankan Transkripsi API" +
                    (if (!transcriptionReady) " (atur dulu di Pengaturan)." else "."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        p.transcriptionMessage?.let { message ->
            val color = when {
                message.startsWith(TranscribeWorker.DONE_PREFIX) -> Color(0xFF15803D)
                message.startsWith(TranscribeWorker.CANCELLED_PREFIX) -> MaterialTheme.colorScheme.onSurfaceVariant
                p.transcript == TranscriptStatus.Fetching -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            }
            Text(message, color = color, style = MaterialTheme.typography.bodySmall)
        }
        p.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (p.isYouTube && (p.video == VideoStatus.Failed || p.transcript == TranscriptStatus.Failed)) {
                TextButton(onClick = onRetry) { Text("Coba lagi") }
            }
        }
    }
}

@Composable
private fun SegmentRow(segment: TranscriptSegmentEntity, query: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            Timecode.format(segment.startTime),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Text(highlight(segment.text, query), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun highlight(text: String, query: String) = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    while (true) {
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) break
        append(text.substring(start, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFD400))) { append(text.substring(index, index + query.length)) }
        start = index + query.length
    }
    append(text.substring(start))
}
