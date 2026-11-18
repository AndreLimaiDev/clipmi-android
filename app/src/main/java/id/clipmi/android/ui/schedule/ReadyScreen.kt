package id.clipmi.android.ui.schedule

import android.content.Intent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import id.clipmi.android.ui.components.ActionChip
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
                // Ketuk gambar untuk memutar video hasil render.
                Box(contentAlignment = Alignment.Center) {
                    ClipThumb(clip, Modifier.width(72.dp).height(128.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = fileOk == true, onClickLabel = "Putar video") { openVideo(context, clip.outputPath) })
                    if (fileOk == true) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(ClipMiIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(ClipMiIcons.Clock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                        Text(IdDate.weekdayDayMonthTime(s.at), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textBody)
                    }
                    val active = s.targets.filter { it.row.status != TargetStatus.SKIPPED }
                    val published = active.count { it.row.status == TargetStatus.PUBLISHED }
                    when {
                        s.state == ScheduleState.Published -> StatusBadge("Semua terbit", Tone.Good)
                        s.isOverdue(now) -> StatusBadge("Terlewat · $published/${active.size} terbit", Tone.Bad)
                        else -> StatusBadge("$published/${active.size} akun terbit", if (published > 0) Tone.Busy else Tone.Idle)
                    }
                }
            }

            if (s.isOverdue(now)) {
                Notice(Tone.Bad, "Terlewat sejak ${IdDate.weekdayDayMonthTime(s.at)}.") {
                    SecondaryButton("Jadwalkan ulang", {
                        scope.launch { container.scheduler.reschedule(s.id)?.let { (id, message) -> notifier.show(message); if (id != s.id) onOpened(id) } }
                    }, height = 40.dp)
                }
            }
            if (s.state != ScheduleState.Published) {
                val busy = clip.render == RenderStatus.Queued || clip.render == RenderStatus.Rendering
                val warnings = listOfNotNull(
                    if (fileOk == false && !busy) "File hasil render tidak ditemukan." else null,
                    if (clip.changedAfterRender && !busy) "Klip diubah setelah dirender." else null,
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

            // Teks yang ditempel di aplikasi tujuan: tiap bagian bisa disalin sendiri, atau sekaligus.
            ClipMiCard(spacing = 12.dp) {
                CopyBlock("Judul", s.title) { Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text) }
                if (caption.isNotBlank()) CopyBlock("Caption", caption) { Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (hashtags.isNotEmpty()) {
                    val tags = hashtags.joinToString(" ")
                    CopyBlock("Hashtag", tags) { Text(tags, style = MaterialTheme.typography.bodySmall, color = c.accent) }
                }
                s.entity.note?.let { Text("Catatan: $it", style = MaterialTheme.typography.bodySmall, color = c.warn) }
                SecondaryButton("Salin semua", { if (copy(context, uploadText)) notifier.show("Tersalin.") }, icon = ClipMiIcons.Copy, height = 44.dp, modifier = Modifier.fillMaxWidth())
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

/** Satu bagian teks unggahan (label kecil, isi, ikon salin). */
@Composable
private fun CopyBlock(label: String, text: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted)
            content()
        }
        IconCircle(ClipMiIcons.Copy, "Salin ${label.lowercase()}", { if (copy(context, text)) notifier.show("$label tersalin.") }, size = 36.dp, background = Color.Transparent, border = false)
    }
}

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
    var sheet by remember { mutableStateOf(false) }
    val latest = stats.lastOrNull()
    val pending = row.status == TargetStatus.PENDING
    val published = row.status == TargetStatus.PUBLISHED
    val publishedAt = row.publishedAt?.let { Wall.of(it) }

    // Semua kartu: titik akun, nama, lencana, menu ⋯. Akun menunggu: Bagikan + Terbit. Akun terbit: ketuk untuk angka.
    var expanded by remember(row.id, row.status) { mutableStateOf(false) }

    ClipMiCard(onClick = if (published) ({ expanded = !expanded }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountDot(account.color, 12.dp)
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    if (published) {
                        listOfNotNull(
                            publishedAt?.let { "Terbit ${IdDate.dayMonth(it)}" },
                            latest?.views?.let { "${PostStats.number(it)} tayangan" } ?: if (row.url == null) "belum ada link" else null,
                        ).joinToString(" · ")
                    } else {
                        listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusBadge(
                when (row.status) { TargetStatus.PUBLISHED -> "Terbit"; TargetStatus.SKIPPED -> "Dilewati"; else -> "Menunggu" },
                when (row.status) { TargetStatus.PUBLISHED -> Tone.Good; TargetStatus.SKIPPED -> Tone.Idle; else -> Tone.Warn },
            )
            Box {
                var menu by remember { mutableStateOf(false) }
                IconCircle(ClipMiIcons.More, "Lainnya", { menu = true }, size = 36.dp, background = Color.Transparent, border = false)
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
                    if (pending) {
                        DropdownMenuItem(text = { Text("Lewati akun ini") }, onClick = { menu = false; scope.launch { container.scheduler.markTarget(row.id, TargetStatus.SKIPPED) } })
                    }
                    if (published) {
                        DropdownMenuItem(text = { Text(if (row.url == null) "Tambah link" else "Ubah link") }, onClick = { menu = false; sheet = true })
                    }
                    // Statistik manual (misalnya angka dari aplikasi Instagram) sekaligus menandai akun ini terbit, sama dengan desktop.
                    DropdownMenuItem(text = { Text("Isi statistik manual") }, onClick = { menu = false; onManual() })
                    if (!pending) {
                        DropdownMenuItem(text = { Text("Kembalikan ke Menunggu") }, onClick = { menu = false; scope.launch { container.scheduler.markTarget(row.id, TargetStatus.PENDING) } })
                    }
                }
            }
        }
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Label selalu menyebut platformnya; bila aplikasinya tidak terpasang, yang terbuka menu bagikan biasa.
                PrimaryButton(
                    "Bagikan ke ${Platforms.label(account.platform)}",
                    { notifier.show(shareTo(context, s.clip, uploadText, account.platform)) },
                    icon = ClipMiIcons.Share, height = 44.dp, modifier = Modifier.weight(1f),
                )
                SecondaryButton("Terbit", { sheet = true }, icon = ClipMiIcons.Check, height = 44.dp)
            }
        }
        if (sheet) {
            PublishSheet(container, target, editing = !pending, onFetchStarted = onFetchStarted, onDone = { sheet = false })
        }
        if (!expanded || !published) return@ClipMiCard

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("Tayangan", PostStats.number(latest?.views), Modifier.weight(1f))
            MiniStat("Suka", PostStats.number(latest?.likes), Modifier.weight(1f))
            MiniStat("Komentar", PostStats.number(latest?.comments), Modifier.weight(1f))
            MiniStat("Share", PostStats.number(latest?.shares), Modifier.weight(1f))
        }
        val url = row.url
        if (url != null) {
            // Link sebagai satu baris (ketuk untuk membuka); ubah lewat menu ⋯.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.panel)
                    .clickable(onClickLabel = "Buka postingan") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(ClipMiIcons.Link, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                Text(url.removePrefix("https://").removePrefix("http://"), style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            ActionChip("Tambah link", { sheet = true }, icon = ClipMiIcons.Link)
        }
        val failed = row.statsError != null && row.statsCheckedAt != null && (latest == null || row.statsCheckedAt > latest.recordedAt)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fetching) CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
            Text(
                when {
                    fetching -> "Mengambil statistik…"
                    failed -> "Gagal diambil"
                    latest != null -> "Diperbarui ${IdDate.dayMonthTime(Wall.of(latest.recordedAt))} · ${StatSource.LABELS[latest.source] ?: latest.source}"
                    else -> "Belum ada statistik"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) c.warn else c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (url != null && !fetching) {
                IconCircle(ClipMiIcons.Refresh, "Ambil statistik", { scope.launch { if (container.scheduler.refreshStats(row.id)) onFetchStarted() } }, size = 32.dp, background = Color.Transparent, border = false)
            }
        }
    }
}

/** Angka kecil di kartu akun terbit. */
@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.panel).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = c.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
    }
}

/**
 * Tandai satu akun terbit dengan link postingan opsional (dipakai untuk statistik), atau ubah link akun yang sudah
 * terbit ([editing]).
 */
@Composable
private fun PublishSheet(container: AppContainer, target: TargetView, editing: Boolean, onFetchStarted: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = target.row.url.orEmpty()
    var link by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    ClipMiSheet(
        onDone,
        title = if (editing) "Link postingan" else "Terbit di ${target.account.name}",
        subtitle = if (editing) target.account.name else "Link opsional, untuk statistik.",
    ) {
        Field(
            link, { link = it; error = null },
            label = if (editing) null else "Link postingan",
            placeholder = "https://…",
            singleLine = true,
            error = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailing = {
                GhostButton("Tempel", {
                    val clip = runCatching { context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
                    if (!clip.isNullOrBlank()) { link = clip.trim(); error = null }
                })
            },
        )
        val save: () -> Unit = {
            scope.launch {
                val failed = container.scheduler.saveLink(target.row.id, link)
                if (failed != null) error = failed else { if (link.isNotBlank()) onFetchStarted(); onDone() }
            }
        }
        if (editing) {
            PrimaryButton("Simpan", save, enabled = link.trim() != current, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Tanpa link", {
                    scope.launch { container.scheduler.markTarget(target.row.id, TargetStatus.PUBLISHED); onDone() }
                }, modifier = Modifier.weight(1f))
                PrimaryButton("Simpan", save, enabled = link.isNotBlank(), modifier = Modifier.weight(1f))
            }
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
