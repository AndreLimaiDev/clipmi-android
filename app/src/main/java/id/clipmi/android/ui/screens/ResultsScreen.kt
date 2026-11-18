package id.clipmi.android.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.components.formatBytes
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.work.RenderQueue
import id.clipmi.android.work.RenderSafety
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aplikasi tujuan yang sering dipakai; tombolnya hanya muncul bila aplikasinya terpasang. */
private val TARGETS = listOf(
    "TikTok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
    "Instagram" to listOf("com.instagram.android"),
    "YouTube" to listOf("com.google.android.youtube"),
)

/** Info file hasil di galeri: ada atau hilang, ukuran, resolusi, durasi, gambar kecil. */
private data class OutputInfo(val exists: Boolean, val bytes: Long?, val width: Int?, val height: Int?, val durationMs: Long?, val thumbnail: Bitmap?)

/**
 * Layar Hasil (mockup "Hasil render"): render yang sedang berjalan, klip yang sudah jadi dengan tombol bagikan ke
 * aplikasi sosmed, dan peringatan bila klip diubah setelah dirender atau file hasilnya hilang dari galeri.
 */
@Composable
fun ResultsScreen(container: AppContainer, onBack: () -> Unit, onOpenProject: (Long) -> Unit, onOpenEditor: (Long) -> Unit, onSchedule: (Long) -> Unit = {}) {
    val clips by container.database.clips().observeRendered().collectAsState(initial = null)
    val context = LocalContext.current
    val installed = remember {
        val pm = context.packageManager
        TARGETS.mapNotNull { (label, packages) ->
            packages.firstOrNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }?.let { label to it }
        }
    }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg).statusBarsPadding()) {
        SubScreenHeader("Hasil render", onBack)
        val list = clips
        if (list != null && list.isEmpty()) {
            EmptyState("Belum ada hasil", "Pilih klip di project, lalu ketuk Render klip. Hasilnya muncul di sini dan di galeri HP.", icon = ClipMiIcons.Film)
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list.orEmpty(), key = { it.id }) { clip ->
                ResultCard(container, clip, installed, onOpenProject, onOpenEditor, onSchedule)
            }
        }
    }
}

@Composable
private fun ResultCard(
    container: AppContainer,
    clip: ClipEntity,
    installed: List<Pair<String, String>>,
    onOpenProject: (Long) -> Unit,
    onOpenEditor: (Long) -> Unit,
    onSchedule: (Long) -> Unit,
) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val title = ClipText.displayTitle(clip.title, clip.partNumber)
    val info by produceState<OutputInfo?>(null, clip.outputPath, clip.renderedAt) {
        value = clip.outputPath?.let { path -> withContext(Dispatchers.IO) { outputInfo(context, Uri.parse(path)) } }
    }

    ClipMiCard {
        when (clip.render) {
            RenderStatus.Queued, RenderStatus.Rendering -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (clip.render == RenderStatus.Queued) clip.renderError ?: RenderSafety.batteryReason(context) ?: "Antre render…" else "Merender ${clip.renderProgress ?: 0}% · HP boleh dipakai",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                    }
                    GhostButton("Batalkan", onClick = { scope.launch { RenderQueue.cancel(context, clip) } })
                }
                ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = c.busy)
            }
            RenderStatus.Failed -> {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Render gagal: ${clip.renderError ?: "sebab tidak diketahui"}", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Coba lagi", onClick = { scope.launch { RenderQueue.render(context, clip) } }, icon = ClipMiIcons.Refresh, height = 44.dp)
                    GhostButton("Buka editor", onClick = { onOpenEditor(clip.id) })
                }
            }
            else -> {
                val missing = info?.exists == false
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(72.dp).height(128.dp).clip(RoundedCornerShape(14.dp)).background(c.panel), contentAlignment = Alignment.Center) {
                        info?.thumbnail?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: Icon(ClipMiIcons.Film, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        info?.takeIf { it.exists }?.let { i ->
                            Text(
                                listOfNotNull(
                                    i.durationMs?.let { Timecode.format(it / 1000.0) },
                                    if (i.width != null && i.height != null) "${i.width}×${i.height}" else null,
                                    i.bytes?.let(::formatBytes),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                            )
                        }
                        Text("Tersimpan di Galeri › Movies/ClipMI", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        when {
                            missing -> StatusBadge("File hilang", Tone.Bad)
                            clip.changedAfterRender -> StatusBadge("Hasil lama", Tone.Warn)
                            else -> StatusBadge("Siap unggah", Tone.Good)
                        }
                    }
                }
                if (missing || clip.changedAfterRender) {
                    Text(
                        if (missing) "File hasil sudah tidak ada di galeri. Render ulang untuk membuatnya lagi."
                        else "Klip diubah setelah dirender; render ulang agar videonya ikut berubah.",
                        style = MaterialTheme.typography.bodySmall, color = if (missing) c.bad else c.warn,
                    )
                    PrimaryButton("Render ulang", onClick = { scope.launch { RenderQueue.render(context, clip) } }, icon = ClipMiIcons.Refresh, height = 44.dp, modifier = Modifier.fillMaxWidth())
                }
                if (!missing) {
                    Text("Bagikan ke", style = MaterialTheme.typography.labelMedium, color = c.textBody)
                    ChipRow(gutter = 0.dp) {
                        installed.forEach { (label, pkg) ->
                            FilterPill(label, selected = false, onClick = { share(context, container, clip, pkg) { notifier.show(it) } })
                        }
                        FilterPill("Lainnya…", selected = false, onClick = { share(context, container, clip, null) { notifier.show(it) } })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!missing) IconCircle(ClipMiIcons.Play, "Putar hasil", { openVideo(context, clip.outputPath) }, size = 40.dp, background = c.panel)
                    IconCircle(ClipMiIcons.Edit, "Buka editor", { onOpenEditor(clip.id) }, size = 40.dp, background = c.panel)
                    GhostButton("Buka project", onClick = { onOpenProject(clip.projectId) })
                    if (!missing) GhostButton("Jadwalkan", onClick = { onSchedule(clip.id) })
                }
            }
        }
    }
}

/** Video terlampir ke aplikasi [pkg] (atau pemilih aplikasi), caption dan hashtag disalin dulu. */
private fun share(context: Context, container: AppContainer, clip: ClipEntity, pkg: String?, notify: (String) -> Unit) {
    val text = ClipText.shareText(clip.title, clip.partNumber, clip.caption, container.analysis.decodeList(clip.hashtags))
    copy(context, text)
    val uri = clip.outputPath?.let(Uri::parse)
    if (uri == null || !exists(context, uri)) {
        notify("File hasil tidak ditemukan. Render ulang klip ini.")
        return
    }
    val send = videoSend(uri, clip.title)
    val ok = runCatching { context.startActivity(if (pkg != null) send.setPackage(pkg) else videoChooser(context, send)) }.isSuccess
    notify(if (ok) "Caption & hashtag disalin. Tempel di aplikasi tujuan." else "Aplikasi tujuan tidak bisa dibuka.")
}

private fun outputInfo(context: Context, uri: Uri): OutputInfo {
    val columns = arrayOf(MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION)
    val row = runCatching {
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else listOf(0, 1, 2, 3).map { if (c.isNull(it)) null else c.getLong(it) }
        }
    }.getOrNull() ?: return OutputInfo(false, null, null, null, null, null)
    if (!exists(context, uri)) return OutputInfo(false, null, null, null, null, null)
    val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(216, 384), null) }.getOrNull()
    return OutputInfo(true, row[0], row[1]?.toInt(), row[2]?.toInt(), row[3], thumbnail)
}
