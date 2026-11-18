package id.clipmi.android.ui.screens

import id.clipmi.android.ui.components.bleed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
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
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
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

/** Filter hasil render. */
private enum class ResultFilter(val label: String) { All("Semua"), Ready("Siap unggah"), Stale("Hasil lama"), Scheduled("Sudah dijadwalkan") }

/**
 * Layar Hasil render: klip yang sedang/selesai dirender. Kartu ringkas (gambar untuk memutar, judul, durasi dan ukuran,
 * status) dengan Jadwalkan, Bagikan, dan menu ⋯; filter per status bila ada isinya.
 */
@Composable
fun ResultsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenEditor: (Long) -> Unit,
    onSchedule: (Long) -> Unit = {},
    onOpenSchedule: (Long) -> Unit = {},
) {
    val clips by container.database.clips().observeRendered().collectAsState(initial = null)
    val schedule by id.clipmi.android.ui.schedule.rememberScheduleData(container)
    val scheduled = schedule?.scheduledClipIds.orEmpty()
    // Klip yang sudah dijadwalkan: "Lihat jadwal" membuka jadwal terdekatnya (Siap unggah), bukan form baru.
    val scheduleOf = schedule?.schedules.orEmpty().sortedBy { it.at }.groupBy { it.clip.id }.mapValues { it.value.first().id }
    val context = LocalContext.current
    val c = ClipMi.colors
    val installed = remember {
        val pm = context.packageManager
        TARGETS.mapNotNull { (label, packages) ->
            packages.firstOrNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }?.let { label to it }
        }
    }
    var filter by rememberSaveable { mutableStateOf(ResultFilter.All) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        SubScreenHeader("Hasil render", onBack)
        val list = clips
        if (list != null && list.isEmpty()) {
            EmptyState("Belum ada hasil", "Render klip terpilih dari halaman project.", icon = ClipMiIcons.Film)
            return
        }
        fun matches(f: ResultFilter, clip: ClipEntity) = when (f) {
            ResultFilter.All -> true
            ResultFilter.Ready -> clip.render == RenderStatus.Done && !clip.changedAfterRender && clip.id !in scheduled
            ResultFilter.Stale -> clip.changedAfterRender
            ResultFilter.Scheduled -> clip.id in scheduled
        }
        val all = list.orEmpty()
        val shown = all.filter { matches(filter, it) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 0.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "where") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ClipMiIcons.Image, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                    Text("Tersimpan di Galeri › Movies/ClipMI", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            val filters = ResultFilter.entries.filter { f -> f == ResultFilter.All || f == filter || all.any { matches(f, it) } }
            if (filters.size > 2 || filter != ResultFilter.All) {
                item(key = "filter") {
                    ChipRow(Modifier.bleed()) {
                        filters.forEach { f -> FilterPill("${f.label} · ${all.count { matches(f, it) }}", selected = filter == f, height = 34.dp, onClick = { filter = f }) }
                    }
                }
            }
            items(shown, key = { it.id }) { clip ->
                ResultCard(container, clip, installed, clip.id in scheduled, onOpenProject, onOpenEditor) { id -> scheduleOf[id]?.let(onOpenSchedule) ?: onSchedule(id) }
            }
        }
    }
}

@Composable
private fun ResultCard(
    container: AppContainer,
    clip: ClipEntity,
    installed: List<Pair<String, String>>,
    isScheduled: Boolean,
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

    ClipMiCard(padding = PaddingValues(12.dp), spacing = 12.dp) {
        when (clip.render) {
            RenderStatus.Queued, RenderStatus.Rendering -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (clip.render == RenderStatus.Queued) clip.renderError ?: RenderSafety.batteryReason(context) ?: "Antre render…" else "Merender ${clip.renderProgress ?: 0}%",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                    }
                    GhostButton("Batalkan", onClick = { scope.launch { RenderQueue.cancel(context, clip) } })
                }
                ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = c.busy)
            }
            RenderStatus.Failed -> {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Render gagal: ${clip.renderError ?: "sebab tidak diketahui"}", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Coba lagi", { scope.launch { RenderQueue.render(context, clip) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Refresh, primary = true)
                    ActionChip("Buka editor", { onOpenEditor(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Scissors)
                }
            }
            else -> {
                val missing = info?.exists == false
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Gambar kecil: ketuk untuk memutar.
                    Box(
                        Modifier.width(64.dp).height(112.dp).clip(RoundedCornerShape(12.dp)).background(c.panel)
                            .clickable(enabled = !missing, onClickLabel = "Putar hasil") { openVideo(context, clip.outputPath) },
                        contentAlignment = Alignment.Center,
                    ) {
                        info?.thumbnail?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: Icon(ClipMiIcons.Film, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(22.dp))
                        if (!missing && info != null) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                Icon(ClipMiIcons.Play, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        info?.takeIf { it.exists }?.let { i ->
                            Text(
                                listOfNotNull(i.durationMs?.let { Timecode.format(it / 1000.0) }, i.bytes?.let(::formatBytes)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                            )
                        }
                        when {
                            missing -> StatusBadge("File hilang", Tone.Bad)
                            clip.changedAfterRender -> StatusBadge("Hasil lama", Tone.Warn)
                            isScheduled -> StatusBadge("Dijadwalkan", Tone.Busy)
                            else -> StatusBadge("Siap unggah", Tone.Good)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (missing || clip.changedAfterRender) {
                        ActionChip("Render ulang", { scope.launch { RenderQueue.render(context, clip) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Refresh, primary = true)
                    } else {
                        ActionChip(if (isScheduled) "Lihat jadwal" else "Jadwalkan", { onSchedule(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Calendar, primary = !isScheduled)
                    }
                    if (!missing) ActionChip("Bagikan", { share(context, container, clip, null) { notifier.show(it) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Share)
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        ActionChip("", { menu = true }, height = 40.dp, icon = ClipMiIcons.More)
                        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
                            if (!missing) installed.forEach { (label, pkg) ->
                                DropdownMenuItem(text = { Text("Bagikan ke $label") }, leadingIcon = { Icon(ClipMiIcons.Share, null, Modifier.size(18.dp)) }, onClick = {
                                    menu = false
                                    share(context, container, clip, pkg) { notifier.show(it) }
                                })
                            }
                            DropdownMenuItem(text = { Text("Buka editor") }, leadingIcon = { Icon(ClipMiIcons.Scissors, null, Modifier.size(18.dp)) }, onClick = { menu = false; onOpenEditor(clip.id) })
                            DropdownMenuItem(text = { Text("Buka project") }, leadingIcon = { Icon(ClipMiIcons.Home, null, Modifier.size(18.dp)) }, onClick = { menu = false; onOpenProject(clip.projectId) })
                            if (!missing && !clip.changedAfterRender) {
                                DropdownMenuItem(text = { Text("Render ulang") }, leadingIcon = { Icon(ClipMiIcons.Refresh, null, Modifier.size(18.dp)) }, onClick = {
                                    menu = false
                                    scope.launch { RenderQueue.render(context, clip) }
                                })
                            }
                        }
                    }
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
