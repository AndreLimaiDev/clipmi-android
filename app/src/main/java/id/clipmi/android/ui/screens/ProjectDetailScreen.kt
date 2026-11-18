package id.clipmi.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.work.RenderQueue
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.FileImage
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconBadge
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SearchField
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.SnackbarInset
import id.clipmi.android.ui.components.LightStatusIcons
import id.clipmi.android.ui.components.StatusBarScrim
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.work.TranscribeWorker
import id.clipmi.core.transcript.Timecode
import id.clipmi.core.youtube.TranscriptSource
import kotlinx.coroutines.launch

/** Konfirmasi yang sedang ditampilkan: teks dan aksinya. */
private data class Confirm(val title: String, val body: String, val label: String, val danger: Boolean = false, val action: suspend () -> Unit)

@Composable
fun ProjectDetailScreen(container: AppContainer, projectId: Long, onBack: () -> Unit, onOpenEditor: (Long) -> Unit = {}, onSchedule: (Long) -> Unit = {}) {
    val project by container.database.projects().observe(projectId).collectAsState(initial = null)
    val segments by container.database.segments().observe(projectId).collectAsState(initial = emptyList())
    val clips by container.database.clips().observe(projectId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) }
    // Pilihan segmen untuk klip manual (seperti selectSegment desktop): tekan lama segmen awal, lalu ketuk segmen akhir.
    var selectStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectEnd by rememberSaveable { mutableStateOf<Int?>(null) }
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
    // Putar rentang klip: berhenti sendiri di akhir rentang.
    var stopAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(stopAtMs) {
        val stop = stopAtMs ?: return@LaunchedEffect
        while (player.currentPosition < stop) {
            if (!player.playWhenReady) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
        player.pause()
        stopAtMs = null
    }
    val playRange: (Double, Double) -> Unit = { start, end ->
        if (videoFile != null) {
            player.seekTo((start * 1000).toLong())
            player.play()
            stopAtMs = (end * 1000).toLong()
        } else {
            notifier.show("Video belum siap diputar.")
        }
    }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { container.actions.recoverVideo(projectId, uri); notifier.show("Video disalin ulang ke folder kerja.") }
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            notifier.show(
                runCatching { container.actions.importSubtitle(projectId, uri) }.fold(
                    { "Transkrip diimpor: ${it.segmentCount} segmen" + if (it.correctionCount > 0) ", ${it.correctionCount} kata dikoreksi kamus." else "." },
                    { it.message ?: "File subtitle tidak bisa dibaca." },
                ),
            )
        }
    }

    val p = project ?: return
    val c = ClipMi.colors
    val selecting = selectStart != null
    if (selecting) SnackbarInset(80.dp)
    LightStatusIcons()

    // Segmen yang sedang diputar, untuk ditandai di transkrip (seperti transkrip YouTube).
    var playingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) {
        while (true) {
            playingMs = if (player.isPlaying) player.currentPosition else playingMs.takeIf { player.playWhenReady }
            kotlinx.coroutines.delay(300)
        }
    }

    Box(Modifier.fillMaxSize()) {
      // Video tetap di atas; hanya isi di bawahnya yang bergulir.
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
            if (videoFile != null) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                FileImage(container.workspace.file(p.thumbnailPath), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            IconCircle(
                ClipMiIcons.Back, "Kembali", onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                background = Color(0x990B0F0E), tint = Color.White, border = false,
            )
            IconCircle(
                ClipMiIcons.Trash, "Hapus project",
                onClick = {
                    confirm = Confirm("Hapus project?", "Project, transkrip, klip, dan video sumbernya di HP ini dihapus permanen.", "Hapus", danger = true) {
                        container.actions.delete(projectId)
                        onBack()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                background = Color(0x990B0F0E), tint = Color.White, border = false,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = if (selecting || tab == 1) 120.dp else 40.dp)) {
            item {
                Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val source = TranscriptSource.from(p.transcriptSource)
                        Text(
                            listOfNotNull(
                                if (p.isYouTube) "YouTube" else "Galeri",
                                p.durationSeconds?.let { Timecode.format(it) },
                                segments.size.takeIf { it > 0 }?.let { "$it segmen" },
                                source?.label?.takeIf { p.transcript == TranscriptStatus.Ready },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    }
                    StatusCard(p, transcriptionReady, onRetry = { scope.launch { container.actions.retry(projectId) } }, onRecover = {
                        if (p.isYouTube) scope.launch { container.actions.recoverVideo(projectId); notifier.show("Video diunduh ulang di latar.") }
                        else pickSource.launch(arrayOf("video/*"))
                    })
                    val active = clips.count { it.review != ReviewStatus.Rejected }
                    SegmentedTabs(listOf("Transkrip", if (active > 0) "Klip · $active" else "Klip"), tab, onSelect = { tab = it })
                }
            }

            if (tab == 1) {
                item { ClipsTab(container, p, segments.isNotEmpty(), playRange, onOpenEditor, onSchedule) }
                return@LazyColumn
            }

            item {
                ChipRow(Modifier.padding(top = 12.dp)) {
                    ActionChip("Impor SRT/VTT", height = 32.dp, onClick = {
                        val open = { pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")) }
                        if (segments.isEmpty()) open()
                        else confirm = Confirm("Ganti transkrip?", "Transkrip sekarang (${segments.size} segmen) diganti isi file subtitle.", "Pilih file") { open() }
                    })
                    // Transkripsi API sedang berjalan/menunggu (pengambilan subtitle YouTube tidak memakai pesan ini).
                    if (p.transcript == TranscriptStatus.Fetching && p.transcriptionMessage != null && p.video == VideoStatus.Ready) {
                        ActionChip("Batalkan transkripsi", height = 32.dp, onClick = {
                            scope.launch {
                                val restore = if (segments.isNotEmpty()) TranscriptStatus.Ready else TranscriptStatus.NeedsTranscript
                                container.actions.cancelTranscription(projectId, restore)
                            }
                        })
                    } else {
                        ActionChip(
                            "Transkripsi API", height = 32.dp,
                            enabled = p.video == VideoStatus.Ready && transcriptionReady,
                            onClick = {
                                val start: suspend () -> Unit = { container.actions.startTranscription(projectId) }
                                if (segments.isEmpty()) scope.launch { start() }
                                else confirm = Confirm(
                                    "Transkripsi ulang lewat API?",
                                    "Transkrip sekarang diganti hasil transkripsi API. Biaya API ditagih penyedia sesuai panjang audio.",
                                    "Transkripsi",
                                    action = start,
                                )
                            },
                        )
                    }
                    ActionChip("Terapkan kamus", height = 32.dp, enabled = segments.isNotEmpty(), onClick = {
                        scope.launch {
                            val count = container.transcripts.applyCorrections(projectId)
                            notifier.show(if (count > 0) "$count kata dikoreksi kamus." else "Tidak ada kata yang perlu dikoreksi.")
                        }
                    })
                }
            }
            if (segments.isNotEmpty()) {
                val shown = if (query.isBlank()) segments else segments.filter { it.text.contains(query.trim(), ignoreCase = true) }
                item {
                    Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(
                            query, { query = it }, "Cari di transkrip",
                            trailingText = if (query.isNotBlank()) "${shown.size} cocok" else null,
                            onClear = { query = "" },
                        )
                        if (!selecting) {
                            Text(
                                "Ketuk segmen untuk memutar. Tekan lama untuk membuat klip sendiri.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                items(shown, key = { it.id }) { segment ->
                    val start = selectStart
                    val selected = start != null && segment.sequence in start..(selectEnd ?: start)
                    val now = playingMs
                    val playing = now != null && now >= segment.startTime * 1000 && now < segment.endTime * 1000
                    SegmentRow(
                        segment, query.trim(), selected, playing,
                        onClick = {
                            if (start == null) {
                                playRange(segment.startTime, segments.last().endTime)
                            } else if (selectEnd != null || segment.sequence < start) {
                                selectStart = segment.sequence
                                selectEnd = null
                            } else {
                                selectEnd = segment.sequence
                            }
                        },
                        onLongClick = { selectStart = segment.sequence; selectEnd = null },
                    )
                }
            }
        }
      }

        // Tab Klip: klip terpilih bisa dirender sekaligus (berurutan di antrean render).
        val selected = clips.filter { it.review == ReviewStatus.Selected }
        val pending = selected.filter { it.render == RenderStatus.NotRendered || it.render == RenderStatus.Failed || it.changedAfterRender }
        val context = LocalContext.current
        AnimatedVisibility(
            visible = tab == 1 && !selecting && selected.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SnackbarInset(80.dp)
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(c.panel)
                    .border(1.dp, c.lineStrong, RoundedCornerShape(32.dp))
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val busy = selected.count { it.render.isBusy }
                Text(
                    "${selected.size} klip terpilih" + if (busy > 0) " · $busy dirender" else "",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                if (pending.isNotEmpty()) {
                    PrimaryButton(if (pending.size == selected.size) "Render semua" else "Render ${pending.size} klip", height = 48.dp, onClick = {
                        scope.launch { pending.forEach { RenderQueue.render(context, it) } }
                    })
                } else {
                    Text(if (busy > 0) "Merender…" else "Semua sudah dirender", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val start = selectStart ?: 0
            val end = selectEnd ?: start
            val first = segments.firstOrNull { it.sequence == start }
            val last = segments.firstOrNull { it.sequence == end }
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.panel)
                    .border(1.dp, c.lineStrong, RoundedCornerShape(28.dp))
                    .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (selectEnd == null) "Ketuk segmen akhir" else "${end - start + 1} segmen dipilih",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                        color = c.text,
                    )
                    Text(
                        "S%04d–S%04d".format(start, end) + (if (first != null && last != null) " · ${Timecode.format(last.endTime - first.startTime)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
                GhostButton("Batal", onClick = { selectStart = null; selectEnd = null })
                PrimaryButton("Buat klip", height = 44.dp, onClick = {
                    scope.launch {
                        container.analysis.createManualClip(projectId, start, end)
                        selectStart = null
                        selectEnd = null
                        tab = 1
                        notifier.show("Klip manual dibuat dan langsung terpilih.")
                    }
                })
            }
        }
    }

    confirm?.let { cf ->
        ConfirmDialog(
            cf.title, cf.body, cf.label, danger = cf.danger,
            onConfirm = { confirm = null; scope.launch { cf.action() } },
            onDismiss = { confirm = null },
        )
    }
}

/** Kartu status unduhan/transkrip; tidak tampil bila semuanya siap tanpa pesan. */
@Composable
private fun StatusCard(p: ProjectEntity, transcriptionReady: Boolean, onRetry: () -> Unit, onRecover: () -> Unit = {}) {
    val c = ClipMi.colors
    val message = p.transcriptionMessage
    val needsTranscript = p.transcript == TranscriptStatus.NeedsTranscript
    val busyVideo = p.video == VideoStatus.Downloading || p.video == VideoStatus.Processing || p.video == VideoStatus.Pending
    val fetching = p.transcript == TranscriptStatus.Fetching
    if (p.video == VideoStatus.Deleted) {
        ClipMiCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ClipMiIcons.Alert, Tone.Warn)
                Text("Video sumber dihapus", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            }
            Text(
                "Transkrip dan klip tetap ada. Untuk memutar atau merender ulang, " + if (p.isYouTube) "unduh ulang videonya." else "pilih ulang video sumbernya dari galeri.",
                style = MaterialTheme.typography.bodySmall, color = c.textBody,
            )
            SecondaryButton(if (p.isYouTube) "Unduh ulang video" else "Pilih ulang video", onClick = onRecover, height = 40.dp, icon = ClipMiIcons.Refresh)
        }
        return
    }
    if (!busyVideo && !fetching && !needsTranscript && message == null && p.errorMessage == null) return

    val (icon, tone, title) = when {
        p.errorMessage != null && (p.video == VideoStatus.Failed || p.transcript == TranscriptStatus.Failed) -> Triple(ClipMiIcons.Alert, Tone.Bad, "Gagal")
        p.video == VideoStatus.Downloading -> Triple(ClipMiIcons.Download, Tone.Busy, "Mengunduh video ${p.videoProgress ?: 0}%")
        p.video == VideoStatus.Processing -> Triple(ClipMiIcons.Film, Tone.Busy, "Memproses video")
        p.video == VideoStatus.Pending -> Triple(ClipMiIcons.Download, Tone.Idle, "Menunggu antrean")
        fetching -> Triple(ClipMiIcons.Mic, Tone.Busy, if (message != null) "Transkripsi API" else "Mengambil transkrip")
        needsTranscript -> Triple(ClipMiIcons.Alert, Tone.Warn, "Butuh transkrip")
        message?.startsWith(TranscribeWorker.DONE_PREFIX) == true -> Triple(ClipMiIcons.Check, Tone.Good, "Transkripsi selesai")
        else -> Triple(ClipMiIcons.Mic, Tone.Idle, "Transkripsi")
    }

    ClipMiCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tone)
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
        }
        if (p.video == VideoStatus.Downloading) ProgressBar((p.videoProgress ?: 0) / 100f)
        else if (busyVideo || fetching) ProgressBar(null, color = c.busy)
        if (needsTranscript) {
            Text(
                "Video ini tidak punya subtitle Indonesia. Impor SRT/VTT, atau jalankan Transkripsi API" +
                    (if (!transcriptionReady) " (atur dulu di Pengaturan)." else "."),
                style = MaterialTheme.typography.bodySmall,
                color = c.textBody,
            )
        }
        message?.let {
            val color = when {
                it.startsWith(TranscribeWorker.DONE_PREFIX) -> c.accent
                it.startsWith(TranscribeWorker.CANCELLED_PREFIX) -> c.textMuted
                fetching -> c.textBody
                else -> c.bad
            }
            Text(it, color = color, style = MaterialTheme.typography.bodySmall)
        }
        p.errorMessage?.let {
            Text(it, color = c.bad, style = MaterialTheme.typography.bodySmall)
            if (p.isYouTube && (p.video == VideoStatus.Failed || p.transcript == TranscriptStatus.Failed)) {
                SecondaryButton("Coba lagi", onClick = onRetry, height = 40.dp, icon = ClipMiIcons.Refresh)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(segment: TranscriptSegmentEntity, query: String, selected: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .then(
                when {
                    selected -> Modifier.background(c.accent.copy(alpha = 0.12f)).border(1.dp, c.accent.copy(alpha = 0.35f), shape)
                    playing -> Modifier.background(c.panel)
                    else -> Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            Timecode.format(segment.startTime),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = c.accent,
            modifier = Modifier.width(48.dp).padding(top = 2.dp),
        )
        Text(
            highlight(segment.text, query, c.highlight, c.onHighlight),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = if (selected) c.text else c.textBody,
        )
    }
}

private fun highlight(text: String, query: String, background: Color, color: Color) = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    while (true) {
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) break
        append(text.substring(start, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = background, color = color)) { append(text.substring(index, index + query.length)) }
        start = index + query.length
    }
    append(text.substring(start))
}
