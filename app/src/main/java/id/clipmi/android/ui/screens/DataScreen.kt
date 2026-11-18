package id.clipmi.android.ui.screens

import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.ActionChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.domain.Backup
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SectionLabel
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.SwitchRow
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.components.formatBytes
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.settings.PackPreview
import id.clipmi.core.settings.ParsedPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data & penyimpanan: sisa ruang HP dan ukuran tiap project (hapus atau siapkan ulang video sumber), Cadangkan &
 * Pulihkan, dan Paket Standar Tim.
 */
@Composable
fun DataScreen(container: AppContainer, onBack: () -> Unit) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val projects by container.database.projects().observeAll().collectAsState(initial = null)
    var refresh by remember { mutableStateOf(0) }
    val sizes by produceState<Map<Long, Long>?>(null, projects, refresh) {
        value = withContext(Dispatchers.IO) { projects.orEmpty().associate { it.id to container.workspace.projectSize(it.id) } }
    }
    val space by produceState<Triple<Long, Long, Long>?>(null, projects, refresh) {
        value = withContext(Dispatchers.IO) {
            val stat = StatFs(context.filesDir.path)
            val renders = container.database.clips().schedulable().mapNotNull { it.outputPath }.sumOf { path ->
                runCatching {
                    context.contentResolver.query(Uri.parse(path), arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
                }.getOrDefault(0L)
            }
            Triple(stat.availableBytes, container.workspace.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, renders)
        }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var includeRenders by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreSummary by remember { mutableStateOf<Backup.Summary?>(null) }
    var pack by remember { mutableStateOf<Pair<ParsedPack, PackPreview>?>(null) }
    var deleteSource by remember { mutableStateOf<ProjectEntity?>(null) }
    var recoverLocal by remember { mutableStateOf<Long?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = recoverLocal ?: return@rememberLauncherForActivityResult
        recoverLocal = null
        if (uri != null) scope.launch { container.actions.recoverVideo(id, uri); notifier.show("Video disalin ulang ke folder kerja.") }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Menyiapkan cadangan…"
            runCatching { container.backup.export(uri, includeRenders) { busy = it } }
                .onSuccess { s -> notifier.show("Cadangan tersimpan: ${s.counts["projects"] ?: 0} project, ${s.counts["clips"] ?: 0} klip" + if (s.renders > 0) ", ${s.renders} video hasil." else ".") }
                .onFailure { notifier.show("Cadangan gagal: ${it.message}") }
            busy = null
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.backup.inspect(uri) }
                .onSuccess { restoreUri = uri; restoreSummary = it }
                .onFailure { notifier.show(it.message ?: "File cadangan tidak bisa dibaca.") }
        }
    }
    val exportPack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.teamPacks.export(uri) }
                .onSuccess { notifier.show("Paket Standar Tim tersimpan.") }
                .onFailure { notifier.show("Paket gagal disimpan: ${it.message}") }
        }
    }
    val openPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { container.teamPacks.read(uri).let { it to container.teamPacks.preview(it) } }
                .onSuccess { pack = it }
                .onFailure { notifier.show(it.message ?: "Paket tidak bisa dibaca.") }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Data & penyimpanan", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            busy?.let { text ->
                ClipMiCard {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                    ProgressBar(null)
                }
            }

            SectionLabel("Penyimpanan")
            // Ringkasan: sisa ruang besar, batang pemakaian (folder kerja vs video hasil), satu baris keterangan.
            ClipMiCard(spacing = 14.dp, padding = PaddingValues(20.dp)) {
                val s = space
                if (s == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                } else {
                    Column {
                        Text("Sisa ruang HP", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Text(formatBytes(s.first), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 36.sp), color = c.text)
                    }
                    val total = (s.second + s.third).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.panel)) {
                        Box(Modifier.weight(s.second.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(c.accent))
                        Box(Modifier.weight(s.third.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(c.busy))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(c.accent, "Folder kerja", formatBytes(s.second))
                        Legend(c.busy, "Video hasil", formatBytes(s.third))
                    }
                    if (total > 0) Text("Video sumber paling besar; transkrip, klip, dan hasil tetap ada bila dihapus.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            // Video sumber per project dalam satu kartu: judul, ukuran, dan ikon hapus/unduh ulang.
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
                                        if (p.isYouTube) scope.launch { container.actions.recoverVideo(p.id); notifier.show("Video diunduh ulang di latar.") }
                                        else { recoverLocal = p.id; pickVideo.launch(arrayOf("video/*")) }
                                    },
                                    size = 40.dp, background = Color.Transparent, border = false,
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }

            SectionLabel("Cadangkan & pulihkan")
            ClipMiCard {
                Text("Cadangkan", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("Project, klip, gaya, jadwal, dan pengaturan · tanpa API key dan video sumber", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                SwitchRow("Sertakan video hasil", space?.third?.let(::formatBytes), includeRenders, onCheckedChange = { includeRenders = it })
                ActionChip("Cadangkan sekarang", { createBackup.launch(container.backup.fileName()) }, Modifier.fillMaxWidth(), height = 44.dp, enabled = busy == null, icon = ClipMiIcons.Download, primary = true)
            }
            ClipMiCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Pulihkan", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text("Isinya ditampilkan dulu sebelum diganti", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    ActionChip("Pilih file", { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) }, height = 40.dp, enabled = busy == null, icon = ClipMiIcons.Upload)
                }
            }

            SectionLabel("Paket Standar Tim")
            ClipMiCard {
                Text("Prompt, gaya, intro/outro, dan kamus · sama dengan ClipMI di PC", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Ekspor paket", { exportPack.launch(container.teamPacks.fileName()) }, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Download)
                    ActionChip("Impor paket", { openPack.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Upload)
                }
            }
        }
    }

    deleteSource?.let { p ->
        ConfirmDialog(
            "Hapus video sumber?", "Video sumber \"${p.title}\" dihapus dari HP (${sizes?.get(p.id)?.let(::formatBytes) ?: ""}). Transkrip, klip, dan hasil render tetap ada; untuk render ulang, " +
                (if (p.isYouTube) "videonya diunduh ulang." else "videonya dipilih ulang dari galeri."),
            "Hapus", danger = true,
            onConfirm = {
                deleteSource = null
                scope.launch {
                    val freed = container.actions.deleteSource(p.id)
                    notifier.show(if (freed > 0) "Video sumber dihapus, ${formatBytes(freed)} dibebaskan." else "Video sumber tidak bisa dihapus saat klipnya sedang dirender.")
                    refresh++
                }
            },
            onDismiss = { deleteSource = null },
        )
    }

    restoreSummary?.let { s ->
        ClipMiSheet({ restoreSummary = null; restoreUri = null }, title = "Pulihkan cadangan?", subtitle = "Dibuat ${s.createdAt.replace('T', ' ').take(16)} · ClipMI ${s.appVersion}") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.counts.forEach { (table, count) -> Text("${container.backup.tableLabel(table)}: $count", style = MaterialTheme.typography.bodyMedium, color = c.text) }
                Text("Berkas pendukung: ${s.files}" + if (s.renders > 0) " · video hasil: ${s.renders}" else "", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            Text(
                "Semua data ClipMI di HP ini diganti isi cadangan, lalu aplikasi dibuka ulang. API key perlu diisi lagi; video sumber perlu diunduh atau dipilih ulang.",
                style = MaterialTheme.typography.bodySmall, color = c.warn,
            )
            PrimaryButton("Ganti data dengan cadangan", modifier = Modifier.fillMaxWidth(), onClick = {
                val uri = restoreUri ?: return@PrimaryButton
                restoreSummary = null
                scope.launch {
                    busy = "Memulihkan…"
                    runCatching { container.backup.restore(uri) { busy = it } }.onFailure { notifier.show(it.message ?: "Gagal memulihkan."); busy = null }
                }
            })
            GhostButton("Batal", { restoreSummary = null; restoreUri = null })
        }
    }

    pack?.let { (parsed, preview) ->
        ClipMiSheet({ pack = null }, title = "Terapkan Paket Standar Tim?", subtitle = "Isi paket yang akan diterapkan") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gaya subtitle: ${preview.stylesNew} baru, ${preview.stylesUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Gaya bingkai: ${preview.framesNew} baru, ${preview.framesUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Kamus koreksi: ${preview.correctionsNew} baru, ${preview.correctionsUpdated} diperbarui", style = MaterialTheme.typography.bodyMedium, color = c.text)
                if (preview.bumpers > 0) Text("Galeri intro & outro: ${preview.bumpers} video", style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("Template prompt: " + if (preview.prompt) "diganti dengan versi paket" else "tidak berubah", style = MaterialTheme.typography.bodyMedium, color = c.text)
                if (preview.skipped > 0) Text("${preview.skipped} entri tidak valid akan dilewati", style = MaterialTheme.typography.bodyMedium, color = c.warn)
            }
            PrimaryButton("Terapkan", modifier = Modifier.fillMaxWidth(), onClick = {
                pack = null
                scope.launch {
                    busy = "Menerapkan paket…"
                    runCatching { container.teamPacks.apply(parsed) }
                        .onSuccess { notifier.show("Paket diterapkan." + if (it.bumpersFailed > 0) " ${it.bumpersFailed} intro/outro tidak bisa dipakai di HP (pakai MP4 H.264)." else "") }
                        .onFailure { notifier.show("Paket gagal diterapkan: ${it.message}") }
                    busy = null
                }
            })
            GhostButton("Batal", { pack = null })
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text("$label $value", style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textBody)
    }
}
