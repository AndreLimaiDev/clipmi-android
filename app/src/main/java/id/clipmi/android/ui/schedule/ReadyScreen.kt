package id.clipmi.android.ui.schedule

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.domain.Wall
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.components.colors
import id.clipmi.android.ui.screens.copy
import id.clipmi.android.ui.screens.exists
import id.clipmi.android.ui.screens.openVideo
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.work.RenderQueue
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.ScheduleState
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.TargetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Siap Unggah (dibuka dari notifikasi pengingat, kalender, atau Statistik): file dan teks untuk ditempel, lalu tiap
 * akun tujuan dibagikan ke aplikasinya, ditandai terbit atau dilewati, diberi link postingan, dan statistiknya diambil.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadyScreen(container: AppContainer, scheduleId: Long, onBack: () -> Unit, onEdit: () -> Unit, onOpened: (Long) -> Unit) {
    val data by rememberScheduleData(container)
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    // Pengambilan statistik yang sedang dipantau: baris → waktu mulai (selesai begitu stats_checked_at sesudahnya).
    val fetching = remember { mutableStateMapOf<Long, Long>() }
    var manualFor by remember { mutableStateOf<TargetView?>(null) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        val d = data
        val s = d?.schedule(scheduleId)
        SubScreenHeader("Siap unggah", onBack) {
            if (s != null) IconCircle(ClipMiIcons.Edit, "Ubah jadwal", onEdit)
        }
        if (d == null) return@Column
        if (s == null) {
            EmptyState("Jadwal tidak ditemukan", "Jadwal ini sudah dihapus atau dipindah.", icon = ClipMiIcons.Calendar)
            return@Column
        }
        val now = Wall.now()
        val clip = s.clip
        val hashtags = Schedules.effectiveHashtags(s.entity.hashtags?.let(container.analysis::decodeList), container.analysis.decodeList(clip.hashtags))
        val caption = Schedules.effectiveCaption(s.entity.caption, clip.caption)
        val uploadText = ClipText.shareText(clip.title, clip.partNumber, caption, hashtags)
        val fileOk by produceState<Boolean?>(null, clip.outputPath, clip.renderedAt) {
            value = clip.outputPath?.let { withContext(Dispatchers.IO) { exists(context, Uri.parse(it)) } } ?: false
        }
        // Selesai begitu baris dicek sejak waktu mulai (data terbaru dari database), atau berhenti memantau setelah 3 menit.
        val targets = s.targets
        LaunchedEffect(targets, fetching.size) {
            val rows = targets.associateBy { it.row.id }
            fetching.keys.toList().forEach { id ->
                val since = fetching[id] ?: return@forEach
                if ((rows[id]?.row?.statsCheckedAt ?: 0) >= since) fetching.remove(id)
            }
            val oldest = fetching.values.minOrNull() ?: return@LaunchedEffect
            delay((oldest + 180_000 - System.currentTimeMillis()).coerceAtLeast(0))
            fetching.keys.filter { (fetching[it] ?: 0) + 180_000 <= System.currentTimeMillis() }.forEach { fetching.remove(it) }
        }

        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ClipThumb(clip, Modifier.width(72.dp).height(128.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    s.project?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(ClipMiIcons.Clock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                        Text(IdDate.weekdayDayMonthTime(s.at), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textBody)
                    }
                    when {
                        s.state == ScheduleState.Published -> StatusBadge("Sudah terbit semua", Tone.Good)
                        s.isOverdue(now) -> StatusBadge("Terlewat", Tone.Bad)
                        s.state == ScheduleState.Partial -> StatusBadge("Sebagian terbit", Tone.Busy)
                        else -> StatusBadge("Terjadwal", Tone.Idle)
                    }
                }
            }

            if (s.isOverdue(now)) {
                Notice(Tone.Bad, "Terlewat sejak ${IdDate.weekdayDayMonthTime(s.at)}. Unggah sekarang, atau pindahkan ke slot berikutnya.") {
                    SecondaryButton("Jadwalkan ulang", {
                        scope.launch { container.scheduler.reschedule(s.id)?.let { (id, message) -> notifier.show(message); if (id != s.id) onOpened(id) } }
                    }, height = 40.dp)
                }
            }
            if (s.state != ScheduleState.Published) {
                val busy = clip.render == RenderStatus.Queued || clip.render == RenderStatus.Rendering
                val warnings = listOfNotNull(
                    if (fileOk == false && !busy) "File hasil render tidak ditemukan. Render ulang klipnya." else null,
                    if (clip.changedAfterRender && !busy) "Klip diubah setelah dirender. Render ulang supaya videonya mengikuti perubahan." else null,
                )
                warnings.forEach { warning ->
                    Notice(Tone.Warn, warning) {
                        val canRender = s.project?.videoPath != null
                        if (canRender) SecondaryButton("Render ulang", { scope.launch { RenderQueue.render(context, clip) } }, height = 40.dp)
                    }
                }
                if (busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (clip.render == RenderStatus.Queued) "Antre render…" else "Sedang dirender ${clip.renderProgress ?: 0}%", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = c.busy)
                    }
                }
            }

            ClipMiCard {
                Text("Teks unggahan", style = MaterialTheme.typography.titleSmall, color = c.text)
                if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (hashtags.isNotEmpty()) Text(hashtags.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = c.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Salin judul" to s.title,
                        "Salin caption" to caption,
                        "Salin hashtag" to hashtags.joinToString(" "),
                        "Salin semua" to uploadText,
                    ).forEach { (label, text) ->
                        FilterPill(label, selected = false, enabled = text.isNotBlank(), onClick = { if (copy(context, text)) notifier.show("Tersalin.") })
                    }
                    if (fileOk == true) FilterPill("Putar video", selected = false, onClick = { openVideo(context, clip.outputPath) })
                }
                s.entity.note?.let { Text("Catatan: $it", style = MaterialTheme.typography.bodySmall, color = c.warn) }
            }

            s.targets.forEach { target ->
                TargetCard(
                    container, s, target, uploadText, d.stats[target.row.id].orEmpty(),
                    fetching = target.row.id in fetching,
                    onFetchStarted = { fetching[target.row.id] = System.currentTimeMillis() / 1000 * 1000 },
                    onManual = { manualFor = target },
                )
            }
        }
    }

    manualFor?.let { target ->
        ManualStatsSheet(container, target, onDone = { manualFor = null })
    }
}

@Composable
private fun Notice(tone: Tone, text: String, action: @Composable () -> Unit = {}) {
    val (fg, bg) = tone.colors()
    Column(
        Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ClipMiIcons.Alert, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.text, modifier = Modifier.weight(1f))
        }
        action()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(
    container: AppContainer,
    s: ScheduleView,
    target: TargetView,
    uploadText: String,
    stats: List<id.clipmi.android.data.PostStatEntity>,
    fetching: Boolean,
    onFetchStarted: () -> Unit,
    onManual: () -> Unit,
) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val row = target.row
    val account = target.account
    var link by remember(row.id, row.url) { mutableStateOf(row.url.orEmpty()) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val latest = stats.lastOrNull()
    val app = remember(account.platform) { installedApp(context, account.platform) }

    ClipMiCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountDot(account.color, 12.dp)
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            StatusBadge(
                TargetStatus.LABELS[row.status] ?: row.status,
                when (row.status) { TargetStatus.PUBLISHED -> Tone.Good; TargetStatus.SKIPPED -> Tone.Idle; else -> Tone.Warn },
            )
        }
        if (row.status == TargetStatus.PENDING) {
            PrimaryButton(
                if (app != null) "Bagikan ke ${Platforms.label(account.platform)}" else "Bagikan…",
                { notifier.show(shareTo(context, s.clip, uploadText, account.platform)) },
                icon = ClipMiIcons.Share, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Tandai terbit", { scope.launch { container.scheduler.markTarget(row.id, TargetStatus.PUBLISHED) } }, icon = ClipMiIcons.Check, height = 40.dp, modifier = Modifier.weight(1f))
                SecondaryButton("Lewati", { scope.launch { container.scheduler.markTarget(row.id, TargetStatus.SKIPPED) } }, height = 40.dp, modifier = Modifier.weight(1f))
            }
            // Statistik manual (misalnya angka dari aplikasi Instagram) sekaligus menandai akun ini terbit, sama dengan desktop.
            GhostButton("Isi statistik manual", onManual)
        } else {
            row.publishedAt?.let { Text("Terbit ${IdDate.full(Wall.of(it))}", style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        Field(
            link, { link = it; linkError = null },
            label = "Link postingan",
            placeholder = "Tempel link setelah terbit",
            singleLine = true,
            error = linkError,
            supporting = if (row.status == TargetStatus.PENDING) "Menempel link menandai akun ini terbit dan mengambil statistiknya." else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailing = if (link.trim() != row.url.orEmpty()) ({
                GhostButton("Simpan", {
                    scope.launch {
                        val error = container.scheduler.saveLink(row.id, link)
                        linkError = error
                        if (error == null && link.isNotBlank()) onFetchStarted()
                    }
                })
            }) else null,
        )
        if (row.status == TargetStatus.PUBLISHED) {
            val failed = row.statsError != null && row.statsCheckedAt != null && (latest == null || row.statsCheckedAt > latest.recordedAt)
            when {
                fetching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    Text("Mengambil statistik…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                failed -> Text(row.statsError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.warn)
            }
            latest?.let {
                Text(
                    "${PostStats.number(it.views)} tayangan · ${PostStats.number(it.likes)} suka · ${PostStats.number(it.comments)} komentar · ${PostStats.number(it.shares)} share",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textBody,
                )
                Text("Diperbarui ${IdDate.dayMonthTime(Wall.of(it.recordedAt))} · ${StatSource.LABELS[it.source] ?: it.source}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.url != null) SecondaryButton("Ambil statistik", {
                    scope.launch { if (container.scheduler.refreshStats(row.id)) onFetchStarted() }
                }, icon = ClipMiIcons.Refresh, height = 40.dp, enabled = !fetching)
                SecondaryButton("Isi manual", onManual, height = 40.dp)
            }
        }
        if (row.status != TargetStatus.PENDING) {
            GhostButton("Kembalikan ke Menunggu", { scope.launch { container.scheduler.markTarget(row.id, TargetStatus.PENDING) } })
        }
    }
}

/** Statistik diisi manual (misalnya Instagram, atau angka dari aplikasi platformnya). */
@Composable
private fun ManualStatsSheet(container: AppContainer, target: TargetView, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val fields = listOf("Tayangan", "Suka", "Komentar", "Share")
    val values = remember { mutableStateMapOf<Int, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.row.id) {
        container.database.schedules().latestStat(target.row.id)?.let { latest ->
            listOf(latest.views, latest.likes, latest.comments, latest.shares).forEachIndexed { i, v -> v?.let { values[i] = it.toString() } }
        }
    }
    ClipMiSheet(onDone, title = "Isi statistik manual", subtitle = "${target.account.name} · angka dari aplikasi ${Platforms.label(target.account.platform)}") {
        fields.forEachIndexed { i, label ->
            Field(
                values[i].orEmpty(), { values[i] = it.filter(Char::isDigit).take(11); error = null },
                label = label, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.bad) }
        PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
            val numbers = (0..3).map { values[it]?.takeIf { v -> v.isNotEmpty() }?.toLongOrNull() }
            if (numbers.all { it == null }) {
                error = "Isi minimal satu angka."
                return@PrimaryButton
            }
            scope.launch {
                container.scheduler.saveManualStats(target.row.id, StatNumbers(numbers[0], numbers[1], numbers[2], numbers[3]))
                notifier.show("Statistik tersimpan.")
                onDone()
            }
        })
    }
}
