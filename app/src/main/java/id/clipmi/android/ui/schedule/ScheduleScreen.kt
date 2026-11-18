package id.clipmi.android.ui.schedule

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.domain.Wall
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.ScreenHeader
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.styles.hexColor
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.work.RenderQueue
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.QueueFiller
import id.clipmi.core.schedule.ScheduleState
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Tab Jadwal (halaman Jadwal desktop dalam bentuk HP): jadwal terlewat, tampilan minggu dan bulan, antrean klip siap
 * unggah dengan Isi otomatis, dan akun sosmed. Detail jadwal dibuka di layar Siap Unggah.
 */
@Composable
fun ScheduleScreen(
    container: AppContainer,
    onOpenAccounts: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onNewSchedule: (clipId: Long, date: String?) -> Unit,
) {
    val data by rememberScheduleData(container)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var anchorDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var hidden by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var banner by rememberSaveable { mutableStateOf<String?>(null) }
    var pickFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val anchor = LocalDate.ofEpochDay(anchorDay)
    val notificationsOn = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 20.dp)) {
        ScreenHeader("Jadwal", Modifier.padding(horizontal = ScreenGutter), eyebrow = "ClipMI") {
            IconCircle(ClipMiIcons.Users, "Akun sosmed", onOpenAccounts)
        }
        val d = data ?: return@Column
        if (d.accounts.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = FloatingNavSpace)) {
                EmptyState(
                    "Tambahkan akun sosmed",
                    "Akun hanya label untuk merencanakan posting (tanpa login). Klip tetap diunggah dari aplikasi TikTok, Instagram, atau YouTube.",
                    icon = ClipMiIcons.Calendar,
                    action = { PrimaryButton("Tambah akun", onOpenAccounts, icon = ClipMiIcons.Plus) },
                )
            }
            return@Column
        }
        val now = Wall.now()
        val overdue = d.schedules.filter { it.isOverdue(now) }
        val visible = d.schedules.filter { s -> hidden.isEmpty() || s.targets.any { it.account.id !in hidden } }
        val listState = rememberLazyListState()
        // Pesan hasil (Isi otomatis, jadwal ulang) ada di paling atas: gulir ke sana supaya terbaca.
        LaunchedEffect(banner) { if (banner != null) listState.animateScrollToItem(0) }

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 16.dp, bottom = FloatingNavSpace),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            banner?.let { text ->
                item(key = "banner") {
                    ClipMiCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(ClipMiIcons.Check, contentDescription = null, tint = ClipMi.colors.accent, modifier = Modifier.size(20.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.text, modifier = Modifier.weight(1f))
                            IconCircle(ClipMiIcons.Close, "Tutup pesan", { banner = null }, size = 32.dp, background = Color.Transparent, border = false)
                        }
                    }
                }
            }
            if (!notificationsOn) {
                item(key = "notif") {
                    ClipMiCard {
                        Text("Notifikasi mati", style = MaterialTheme.typography.titleSmall, color = ClipMi.colors.warn)
                        Text("Pengingat \"Waktunya posting\" tidak akan muncul. Izinkan notifikasi ClipMI di pengaturan aplikasi.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                        SecondaryButton("Buka pengaturan notifikasi", onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }, height = 40.dp)
                    }
                }
            }
            if (overdue.isNotEmpty()) {
                item(key = "overdue") {
                    OverdueCard(overdue, onOpen = onOpenSchedule, onReschedule = { id ->
                        scope.launch {
                            container.scheduler.reschedule(id)?.let { (_, message) -> banner = message }
                        }
                    })
                }
            }
            item(key = "tabs") { SegmentedTabs(listOf("Minggu", "Bulan", "Antrean"), tab, { tab = it }) }
            if (tab != 2 && d.accounts.size > 1) {
                item(key = "filter") {
                    ChipRow(gutter = 0.dp) {
                        d.accounts.forEach { account ->
                            val off = account.id in hidden
                            AccountChip(account.name, account.color, off) { hidden = if (off) hidden - account.id else hidden + account.id }
                        }
                    }
                }
            }
            when (tab) {
                0 -> weekView(anchor, visible, now, onShift = { anchorDay = anchor.plusWeeks(it.toLong()).toEpochDay() }, onToday = { anchorDay = LocalDate.now().toEpochDay() }, onOpen = onOpenSchedule, onAdd = { pickFor = it.toEpochDay() })
                1 -> monthView(anchor, LocalDate.ofEpochDay(selectedDay), visible, now, onShift = { anchorDay = anchor.plusMonths(it.toLong()).toEpochDay() }, onSelect = { selectedDay = it.toEpochDay() }, onOpen = onOpenSchedule, onAdd = { pickFor = it.toEpochDay() })
                else -> queueView(container, d, onSchedule = { onNewSchedule(it, null) }, onAutoFill = {
                    scope.launch {
                        val outcome = container.scheduler.autoFill()
                        banner = outcome.banner
                        outcome.firstDate?.let { anchorDay = it.toEpochDay(); tab = 0 }
                    }
                }, onOpenAccounts = onOpenAccounts, onRender = { clip ->
                    scope.launch { RenderQueue.render(context, clip); notifier.show("Klip masuk antrean render.") }
                })
            }
        }
    }

    pickFor?.let { day ->
        val d = data ?: return@let
        ClipPicker(container, d, onDismiss = { pickFor = null }) { clip ->
            pickFor = null
            onNewSchedule(clip.id, LocalDate.ofEpochDay(day).toString())
        }
    }
}

@Composable
private fun AccountChip(name: String, color: String, off: Boolean, onClick: () -> Unit) {
    val c = ClipMi.colors
    Row(
        Modifier.height(36.dp).clip(CircleShape).border(1.dp, if (off) c.line else c.lineStrong, CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (off) c.line else hexColor(color)))
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (off) c.textMuted else c.textBody, maxLines = 1)
    }
}

@Composable
private fun OverdueCard(overdue: List<ScheduleView>, onOpen: (Long) -> Unit, onReschedule: (Long) -> Unit) {
    val c = ClipMi.colors
    ClipMiCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ClipMiIcons.Alert, contentDescription = null, tint = c.bad, modifier = Modifier.size(20.dp))
            Text("${overdue.size} jadwal terlewat", style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        overdue.take(5).forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Sejak ${IdDate.weekdayDayMonthTime(s.at)} · ${s.pending.joinToString(", ") { it.account.name }}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Unggah sekarang", { onOpen(s.id) }, height = 40.dp, icon = ClipMiIcons.Upload)
                    SecondaryButton("Jadwalkan ulang", { onReschedule(s.id) }, height = 40.dp)
                }
            }
        }
        if (overdue.size > 5) Text("dan ${overdue.size - 5} lainnya di kalender.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

/** Navigasi minggu/bulan: ‹ periode › dan Hari ini. */
@Composable
private fun PeriodNav(label: String, onShift: (Int) -> Unit, onToday: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(ClipMiIcons.Back, "Sebelumnya", { onShift(-1) }, size = 40.dp)
        Text(label, style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconCircle(ClipMiIcons.Chevron, "Berikutnya", { onShift(1) }, size = 40.dp)
        onToday?.let { GhostButton("Hari ini", it) }
    }
}

private fun LazyListScope.weekView(
    anchor: LocalDate,
    schedules: List<ScheduleView>,
    now: java.time.LocalDateTime,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    val (start, end) = Schedules.weekRange(anchor)
    val label = if (start.month == end.month) "${start.dayOfMonth} – ${IdDate.dayMonth(end)} ${end.year}" else "${IdDate.dayMonth(start)} – ${IdDate.dayMonth(end)} ${end.year}"
    item(key = "week-nav") { PeriodNav(label, onShift, onToday) }
    val byDay = schedules.groupBy { it.at.toLocalDate() }
    for (offset in 0..6) {
        val day = start.plusDays(offset.toLong())
        item(key = "day-$day") { DaySection(day, byDay[day].orEmpty(), now, onOpen, onAdd) }
    }
}

@Composable
private fun DaySection(day: LocalDate, schedules: List<ScheduleView>, now: java.time.LocalDateTime, onOpen: (Long) -> Unit, onAdd: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val today = day == LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${IdDate.WEEKDAYS[day.dayOfWeek.value - 1]}, ${IdDate.dayMonth(day)}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (today) c.accent else c.textBody,
            )
            if (today) StatusBadge("Hari ini", Tone.Good)
            Spacer(Modifier.weight(1f))
            // Hari yang sudah lewat tanpa tombol tambah, tetapi tinggi barisnya sama.
            if (!day.isBefore(LocalDate.now())) IconCircle(ClipMiIcons.Plus, "Jadwalkan klip di hari ini", { onAdd(day) }, size = 32.dp, background = Color.Transparent)
            else Spacer(Modifier.size(32.dp))
        }
        if (schedules.isEmpty()) {
            Text("Belum ada jadwal", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        }
        schedules.forEach { ScheduleRow(it, now) { onOpen(it.id) } }
    }
}

/** Satu jadwal di kalender: jam, judul, titik akun, dan status. */
@Composable
fun ScheduleRow(s: ScheduleView, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(IdDate.time(s.at), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.text, modifier = Modifier.width(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.targets.forEach { AccountDot(it.account.color, 8.dp) }
                    Text(s.targets.joinToString(", ") { it.account.name }, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                s.state == ScheduleState.Published -> StatusBadge("Terbit", Tone.Good)
                s.isOverdue(now) -> StatusBadge("Terlewat", Tone.Bad)
                s.state == ScheduleState.Partial -> StatusBadge("Sebagian", Tone.Busy)
                else -> StatusBadge("Terjadwal", Tone.Idle)
            }
        }
    }
}

private fun LazyListScope.monthView(
    anchor: LocalDate,
    selected: LocalDate,
    schedules: List<ScheduleView>,
    now: java.time.LocalDateTime,
    onShift: (Int) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    item(key = "month-nav") { PeriodNav(IdDate.monthYear(anchor), onShift, null) }
    val byDay = schedules.groupBy { it.at.toLocalDate() }
    item(key = "month-grid") { MonthGrid(anchor, selected, byDay, onSelect) }
    item(key = "month-day") { DaySection(selected, byDay[selected].orEmpty(), now, onOpen, onAdd) }
}

@Composable
private fun MonthGrid(anchor: LocalDate, selected: LocalDate, byDay: Map<LocalDate, List<ScheduleView>>, onSelect: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val (start, end) = Schedules.monthRange(anchor)
    val today = LocalDate.now()
    ClipMiCard(padding = PaddingValues(10.dp), spacing = 4.dp) {
        Row(Modifier.fillMaxWidth()) {
            IdDate.WEEKDAYS_SHORT.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        var week = start
        while (!week.isAfter(end)) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0..6) {
                    val day = week.plusDays(i.toLong())
                    val inMonth = day.month == anchor.month
                    val isSelected = day == selected
                    val dots = byDay[day].orEmpty().flatMap { s -> s.targets.map { it.account.color } }.distinct().take(3)
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) c.accentSoft else Color.Transparent)
                            .clickable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (day == today || isSelected) FontWeight.ExtraBold else FontWeight.Medium),
                            color = when {
                                isSelected -> c.accent
                                day == today -> c.accent
                                inMonth -> c.text
                                else -> c.textMuted.copy(alpha = 0.5f)
                            },
                        )
                        Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dots.forEach { AccountDot(it, 5.dp) }
                        }
                    }
                }
            }
            week = week.plusWeeks(1)
        }
    }
}

private fun LazyListScope.queueView(
    container: AppContainer,
    d: ScheduleData,
    onSchedule: (Long) -> Unit,
    onAutoFill: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRender: (ClipEntity) -> Unit,
) {
    val queue = d.clips.filter { it.render == RenderStatus.Done && it.outputPath != null && it.id !in d.scheduledClipIds }
        .sortedByDescending { it.renderedAt ?: 0 }
    val waiting = d.clips.filter { it.review == ReviewStatus.Selected && it.render != RenderStatus.Done }.sortedByDescending { it.updatedAt }
    item(key = "autofill") {
        ClipMiCard {
            if (d.hasSlots) {
                Text("Isi otomatis", style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
                Text("Klip antrean dibagikan ke slot rutin akun yang masih kosong, ${QueueFiller.DAYS} hari ke depan. Klip seri tetap berurutan.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                PrimaryButton("Isi otomatis", onAutoFill, enabled = queue.isNotEmpty(), icon = ClipMiIcons.Sparkle, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Slot rutin belum diatur", style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
                Text("Atur jam posting rutin tiap akun (misalnya 19.00 setiap hari) supaya antrean bisa dijadwalkan sekaligus.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                SecondaryButton("Atur slot", onOpenAccounts, height = 40.dp)
            }
        }
    }
    item(key = "queue-label") { Text("Siap dijadwalkan (${queue.size})", style = MaterialTheme.typography.titleSmall, color = ClipMi.colors.textBody) }
    if (queue.isEmpty()) {
        item(key = "queue-empty") {
            Text("Semua klip yang sudah dirender sudah dijadwalkan.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
        }
    }
    items(queue, key = { "q-${it.id}" }) { clip ->
        QueueCard(clip, d.projects[clip.projectId]?.title) { PrimaryButton("Jadwalkan", { onSchedule(clip.id) }, height = 40.dp, icon = ClipMiIcons.Calendar) }
    }
    if (waiting.isNotEmpty()) {
        item(key = "waiting-label") { Text("Terpilih, belum dirender (${waiting.size})", style = MaterialTheme.typography.titleSmall, color = ClipMi.colors.textBody) }
        items(waiting, key = { "w-${it.id}" }) { clip ->
            QueueCard(clip, d.projects[clip.projectId]?.title) {
                when (clip.render) {
                    RenderStatus.Queued, RenderStatus.Rendering -> Column(Modifier.width(120.dp)) {
                        Text(if (clip.render == RenderStatus.Queued) "Antre render" else "Merender ${clip.renderProgress ?: 0}%", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                        ProgressBar(if (clip.render == RenderStatus.Rendering) (clip.renderProgress ?: 0) / 100f else null, color = ClipMi.colors.busy)
                    }
                    else -> SecondaryButton("Render", { onRender(clip) }, height = 40.dp, enabled = d.projects[clip.projectId]?.videoPath != null)
                }
            }
        }
    }
}

@Composable
private fun QueueCard(clip: ClipEntity, project: String?, action: @Composable () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumb(clip, Modifier.width(44.dp).height(78.dp))
            // Tombol di bawah judul: judul klip biasanya panjang dan tidak boleh terpotong oleh tombol.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(project, Timecode.format(clip.duration)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    }
}

/** Gambar kecil video hasil render dari galeri (kosong bila belum dirender atau filenya hilang). */
@Composable
fun ClipThumb(clip: ClipEntity, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, clip.outputPath, clip.renderedAt) {
        value = clip.outputPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(Uri.parse(path), Size(144, 256), null) }.getOrNull() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(ClipMi.colors.panel), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(ClipMiIcons.Film, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

/** Pilih klip antrean untuk dijadwalkan di hari tertentu. */
@Composable
private fun ClipPicker(container: AppContainer, d: ScheduleData, onDismiss: () -> Unit, onPick: (ClipEntity) -> Unit) {
    val queue = d.clips.filter { it.render == RenderStatus.Done && it.outputPath != null && it.id !in d.scheduledClipIds }.sortedByDescending { it.renderedAt ?: 0 }
    ClipMiSheet(onDismiss, title = "Pilih klip", subtitle = "Klip yang sudah dirender dan belum dijadwalkan.") {
        if (queue.isEmpty()) Text("Belum ada klip siap unggah. Render klip terpilih di project dulu.", style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.textMuted)
        queue.forEach { clip -> QueueCard(clip, d.projects[clip.projectId]?.title) { SecondaryButton("Pilih", { onPick(clip) }, height = 40.dp) } }
    }
}
