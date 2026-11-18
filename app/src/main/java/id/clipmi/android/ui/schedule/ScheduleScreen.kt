package id.clipmi.android.ui.schedule

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import id.clipmi.android.ui.components.bleed
import id.clipmi.android.ui.components.DashedCard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.shadow
import id.clipmi.android.ui.components.ActionChip
import androidx.compose.ui.draw.rotate
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
    var monthOpen by remember { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // Posisi bawah navigasi minggu dan atas daftar (koordinat layar) untuk menaruh kalender bulan yang melayang.
    var navBottom by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var hidden by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var banner by rememberSaveable { mutableStateOf<String?>(null) }
    var pickFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    val notificationsOn = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp)) {
        ScreenHeader("Jadwal", Modifier.padding(horizontal = ScreenGutter)) {
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
        val byDay = visible.groupBy { it.at.toLocalDate() }
        val listState = rememberLazyListState()
        // Pesan hasil (Isi otomatis, jadwal ulang) ada di paling atas: gulir ke sana supaya terbaca.
        LaunchedEffect(banner) { if (banner != null) listState.animateScrollToItem(0) }

        Box(Modifier.fillMaxSize().onGloballyPositioned { listTop = it.positionInRoot().y }) {
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
            item(key = "calendar") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (start, end) = Schedules.weekRange(selected)
                    val label = if (start.month == end.month) "${start.dayOfMonth} – ${IdDate.dayMonth(end)}" else "${IdDate.dayMonth(start)} – ${IdDate.dayMonth(end)}"
                    // Kalender bulan melayang di bawah label (lapisan di atas daftar, lihat bawah), tidak menggeser agenda.
                    Box(Modifier.onGloballyPositioned { navBottom = it.boundsInRoot().bottom }) {
                        PeriodNav(label, monthOpen, { monthOpen = !monthOpen }, onShift = { selectedDay = selected.plusWeeks(it.toLong()).toEpochDay() }, onToday = { selectedDay = LocalDate.now().toEpochDay() })
                    }
                    WeekStrip(start, selected, byDay) { selectedDay = it.toEpochDay() }
                }
            }
            if (d.accounts.size > 1) {
                item(key = "filter") {
                    ChipRow(Modifier.bleed()) {
                        d.accounts.forEach { account ->
                            val off = account.id in hidden
                            AccountChip(account.name, account.color, off) { hidden = if (off) hidden - account.id else hidden + account.id }
                        }
                    }
                }
            }
            item(key = "agenda-$selected") { DaySection(selected, byDay[selected].orEmpty(), now, onOpenSchedule) { pickFor = it.toEpochDay() } }
            queueView(container, d, open = queueOpen, onToggle = { queueOpen = !queueOpen }, onSchedule = { onNewSchedule(it, null) }, onAutoFill = {
                scope.launch {
                    val outcome = container.scheduler.autoFill()
                    banner = outcome.banner
                    outcome.firstDate?.let { selectedDay = it.toEpochDay() }
                }
            }, onOpenAccounts = onOpenAccounts, onRender = { clip ->
                scope.launch { RenderQueue.render(context, clip); notifier.show("Klip masuk antrean render.") }
            })
        }
        // Lapisan biasa di pohon yang sama (bukan Popup yang membuat jendela baru), jadi terbuka seketika di HP lama.
        // Ketuk di luar kartu untuk menutup.
        if (monthOpen) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { monthOpen = false })
            MonthCard(
                selected, byDay,
                Modifier.offset { IntOffset(0, (navBottom - listTop).toInt() + 4.dp.roundToPx()) }.padding(horizontal = ScreenGutter),
            ) { selectedDay = it.toEpochDay(); monthOpen = false }
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

/** Navigasi minggu: label minggu (ketuk untuk kalender bulan melayang), ‹ ›, dan Hari ini. */
@Composable
private fun PeriodNav(label: String, monthOpen: Boolean, onToggle: () -> Unit, onShift: (Int) -> Unit, onToday: () -> Unit) {
    val c = ClipMi.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (monthOpen) "Tutup kalender" else "Buka kalender bulan", onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.text)
            Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(16.dp).rotate(if (monthOpen) -90f else 90f))
        }
        IconCircle(ClipMiIcons.Back, "Sebelumnya", { onShift(-1) }, size = 36.dp, background = Color.Transparent, border = false)
        IconCircle(ClipMiIcons.Chevron, "Berikutnya", { onShift(1) }, size = 36.dp, background = Color.Transparent, border = false)
        GhostButton("Hari ini", onToday)
    }
}

/** Tujuh hari mendatar (Sen–Min): tanggal, titik warna akun yang punya jadwal, hari terpilih disorot. */
@Composable
private fun WeekStrip(start: LocalDate, selected: LocalDate, byDay: Map<LocalDate, List<ScheduleView>>, onSelect: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0..6) {
            val day = start.plusDays(i.toLong())
            val isSelected = day == selected
            val dots = byDay[day].orEmpty().flatMap { s -> s.targets.map { it.account.color } }.distinct().take(3)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) c.accent else c.card)
                    .border(1.dp, if (isSelected) c.accent else c.line, RoundedCornerShape(16.dp))
                    .clickable { onSelect(day) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(IdDate.WEEKDAYS_SHORT[i], style = MaterialTheme.typography.labelSmall, color = if (isSelected) c.onAccent else c.textMuted)
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = when {
                        isSelected -> c.onAccent
                        day == today -> c.accent
                        day.isBefore(today) -> c.textMuted
                        else -> c.text
                    },
                )
                Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    dots.forEach { AccountDot(if (isSelected) "#FFFFFF" else it, 5.dp) }
                }
            }
        }
    }
}

@Composable
private fun DaySection(day: LocalDate, schedules: List<ScheduleView>, now: java.time.LocalDateTime, onOpen: (Long) -> Unit, onAdd: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val today = day == LocalDate.now()
    val canAdd = !day.isBefore(LocalDate.now())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Tinggi baris tetap supaya agenda tidak melompat saat tombol Jadwalkan muncul/hilang.
        Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (today) "Hari ini · " else "") + "${IdDate.WEEKDAYS[day.dayOfWeek.value - 1]}, ${IdDate.dayMonth(day)}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (today) c.accent else c.textBody,
                modifier = Modifier.weight(1f),
            )
            // Hari kosong punya tombolnya sendiri di dalam kartu, jadi di judul hanya bila sudah ada jadwal.
            if (canAdd && schedules.isNotEmpty()) ActionChip("Jadwalkan", { onAdd(day) }, icon = ClipMiIcons.Plus, height = 34.dp)
        }
        if (schedules.isEmpty()) {
            // Cukup aksinya: kartu kosong sudah menunjukkan belum ada jadwal. Seluruh kartu bisa diketuk.
            DashedCard(onClick = if (canAdd) ({ onAdd(day) }) else null) {
                if (canAdd) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(ClipMiIcons.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        Text("Jadwalkan klip", style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                } else {
                    Text("Tidak ada jadwal", style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
                }
            }
        }
        schedules.sortedBy { it.at }.forEach { ScheduleRow(it, now) { onOpen(it.id) } }
    }
}

/** Satu jadwal di agenda: gambar kecil, jam, judul, titik akun, dan status. */
@Composable
fun ScheduleRow(s: ScheduleView, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumb(s.clip, Modifier.width(36.dp).height(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(IdDate.time(s.at), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = c.text)
                    when {
                        s.state == ScheduleState.Published -> StatusBadge("Terbit", Tone.Good)
                        s.isOverdue(now) -> StatusBadge("Terlewat", Tone.Bad)
                        s.state == ScheduleState.Partial -> StatusBadge("Sebagian", Tone.Busy)
                        else -> Unit
                    }
                }
                Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.targets.forEach { AccountDot(it.account.color, 8.dp) }
                    Text(s.targets.joinToString(", ") { it.account.name }, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Kalender bulan yang melayang di bawah navigasi minggu (lebar layar dikurangi gutter). Bulan bisa digeser tanpa
 * mengubah hari terpilih; ketuk tanggal untuk memilih, ketuk di luar untuk menutup.
 */
@Composable
private fun MonthCard(selected: LocalDate, byDay: Map<LocalDate, List<ScheduleView>>, modifier: Modifier, onSelect: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    var month by remember { mutableStateOf(selected.withDayOfMonth(1)) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(6.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape)
            // Ketukan di dalam kartu (di luar tanggal) tidak menutup kalender.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(IdDate.monthYear(month), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = c.text, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconCircle(ClipMiIcons.Back, "Bulan sebelumnya", { month = month.minusMonths(1) }, size = 36.dp, background = Color.Transparent, border = false)
            IconCircle(ClipMiIcons.Chevron, "Bulan berikutnya", { month = month.plusMonths(1) }, size = 36.dp, background = Color.Transparent, border = false)
        }
        MonthGrid(month, selected, byDay, onSelect)
    }
}

@Composable
private fun MonthGrid(anchor: LocalDate, selected: LocalDate, byDay: Map<LocalDate, List<ScheduleView>>, onSelect: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val (start, end) = Schedules.monthRange(anchor)
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Modifier.weight(1f).aspectRatio(1.05f).padding(2.dp).clip(RoundedCornerShape(12.dp))
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
    open: Boolean,
    onToggle: () -> Unit,
    onSchedule: (Long) -> Unit,
    onAutoFill: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRender: (ClipEntity) -> Unit,
) {
    val queue = d.clips.filter { it.render == RenderStatus.Done && it.outputPath != null && it.id !in d.scheduledClipIds }
        .sortedByDescending { it.renderedAt ?: 0 }
    val waiting = d.clips.filter { it.review == ReviewStatus.Selected && it.render != RenderStatus.Done }.sortedByDescending { it.updatedAt }
    // Antrean di bawah agenda (bukan tab terpisah), tertutup secara bawaan: judulnya sudah memuat jumlahnya.
    if (queue.isEmpty() && waiting.isEmpty()) return
    item(key = "queue-label") {
        val c = ClipMi.colors
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = if (open) "Tutup antrean" else "Buka antrean", onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Antrean", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    listOfNotNull(
                        queue.size.takeIf { it > 0 }?.let { "$it siap dijadwalkan" },
                        waiting.size.takeIf { it > 0 }?.let { "$it belum dirender" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.size(18.dp).rotate(if (open) -90f else 90f))
        }
    }
    if (!open) return
    if (queue.isNotEmpty()) {
        item(key = "queue-ready") { QueueSubheader("Siap dijadwalkan", queue.size) }
        item(key = "autofill") {
            ClipMiCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (d.hasSlots) "Bagikan antrean ke slot rutin yang kosong, ${QueueFiller.DAYS} hari ke depan. Seri tetap berurutan."
                        else "Atur jam posting rutin tiap akun supaya antrean bisa dijadwalkan sekaligus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClipMi.colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    if (d.hasSlots) PrimaryButton("Isi otomatis", onAutoFill, icon = ClipMiIcons.Sparkle, height = 40.dp)
                    else SecondaryButton("Atur slot", onOpenAccounts, height = 40.dp)
                }
            }
        }
        items(queue, key = { "q-${it.id}" }) { clip ->
            QueueCard(clip, d.projects[clip.projectId]?.title) { PrimaryButton("Jadwalkan", { onSchedule(clip.id) }, height = 40.dp, icon = ClipMiIcons.Calendar) }
        }
    }
    if (waiting.isNotEmpty()) {
        item(key = "queue-waiting") { QueueSubheader("Terpilih, belum dirender", waiting.size) }
        // Satu kartu ringkas per klip (seperti baris agenda); sisanya dirender dari halaman project.
        items(waiting.take(WAITING_SHOWN), key = { "w-${it.id}" }) { clip ->
            val c = ClipMi.colors
            ClipMiCard(padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClipThumb(clip, Modifier.width(36.dp).height(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(Timecode.format(clip.duration), d.projects[clip.projectId]?.title).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when (clip.render) {
                        RenderStatus.Queued, RenderStatus.Rendering -> Text(
                            if (clip.render == RenderStatus.Queued) "Antre" else "${clip.renderProgress ?: 0}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.busy,
                        )
                        else -> ActionChip("Render", { onRender(clip) }, icon = ClipMiIcons.Film, height = 34.dp, enabled = d.projects[clip.projectId]?.videoPath != null)
                    }
                }
            }
        }
        if (waiting.size > WAITING_SHOWN) {
            item(key = "queue-more") {
                Text(
                    "dan ${waiting.size - WAITING_SHOWN} lainnya; render dari halaman project.",
                    style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Subjudul kelompok di antrean: lebih kecil dan redup daripada judul klip di bawahnya. */
@Composable
private fun QueueSubheader(label: String, count: Int) {
    Text(
        "$label · $count",
        style = MaterialTheme.typography.labelLarge,
        color = ClipMi.colors.textMuted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/** Jumlah klip "belum dirender" yang ditampilkan di antrean. */
private const val WAITING_SHOWN = 4

@Composable
private fun QueueCard(clip: ClipEntity, project: String?, action: @Composable () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumb(clip, Modifier.width(44.dp).height(78.dp))
            // Tombol di bawah judul: judul klip biasanya panjang dan tidak boleh terpotong oleh tombol.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Timecode.format(clip.duration), project).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
