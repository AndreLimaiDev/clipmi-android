package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.data.ScheduleEntity
import id.clipmi.android.data.SocialAccountEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.DashedCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
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
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.components.bleed
import id.clipmi.android.ui.components.colors
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatPoint
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.QueueFiller
import id.clipmi.core.schedule.ScheduleState
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.Slot
import id.clipmi.core.schedule.Slots
import id.clipmi.core.schedule.TargetStatus
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Tab Jadwal iOS, padanan ui/schedule Android (ScheduleScreen, ScheduleEditScreen, ReadyScreen, AccountsScreen): tampilan
// dan alur yang sama, logika jadwal dari core bersama. Statistik postingan menyusul.

// ---------------------------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------------------------

/** Akun tujuan satu jadwal beserta akunnya. */
data class TargetView(val row: ScheduleAccountEntity, val account: SocialAccountEntity) {
    val status: String get() = row.status
}

/** Jadwal lengkap untuk layar: klip, project, akun tujuan, dan waktunya sebagai jam dinding HP. */
data class ScheduleView(val entity: ScheduleEntity, val clip: ClipEntity, val project: ProjectEntity?, val targets: List<TargetView>) {
    val id: Long get() = entity.id
    val at: LocalDateTime = Wall.of(entity.scheduledAt)
    val title: String get() = ClipText.displayTitle(clip.title, clip.partNumber)
    val state: ScheduleState get() = Schedules.state(targets.map { it.status })
    val pending: List<TargetView> get() = targets.filter { it.status == TargetStatus.PENDING }

    fun isOverdue(now: LocalDateTime) = Schedules.isOverdue(at, targets.map { it.status }, now)
}

data class ScheduleData(
    val accounts: List<SocialAccountEntity>,
    val schedules: List<ScheduleView>,
    val clips: List<ClipEntity>,
    val projects: Map<Long, ProjectEntity>,
    /** Catatan statistik per akun tujuan (id baris), urut waktu. */
    val stats: Map<Long, List<PostStatEntity>> = emptyMap(),
) {
    val accountSlots: Map<Long, List<Slot>> = accounts.associate { it.id to SlotCodec.decode(it.slots) }
    val hasSlots: Boolean get() = accountSlots.values.any { it.isNotEmpty() }
    val scheduledClipIds: Set<Long> = schedules.map { it.clip.id }.toSet()

    fun schedule(id: Long) = schedules.firstOrNull { it.id == id }
}

private data class ScheduleBase(
    val accounts: List<SocialAccountEntity>,
    val schedules: List<ScheduleEntity>,
    val targets: List<ScheduleAccountEntity>,
    val clips: List<ClipEntity>,
    val projects: List<ProjectEntity>,
)

fun scheduleFlow(repo: Repository): Flow<ScheduleData> {
    val dao = repo.db.schedules()
    val base = combine(
        dao.observeAccounts(), dao.observeSchedules(), dao.observeTargets(), repo.db.clips().observeSchedulable(), repo.db.projects().observeAll(),
    ) { accounts, schedules, targets, clips, projects -> ScheduleBase(accounts, schedules, targets, clips, projects) }
    return combine(base, dao.observeStats()) { b, stats ->
        val accounts = b.accounts
        val schedules = b.schedules
        val targets = b.targets
        val clips = b.clips
        val projects = b.projects
        val accountById = accounts.associateBy { it.id }
        val clipById = clips.associateBy { it.id }
        val projectById = projects.associateBy { it.id }
        val targetsBySchedule = targets.groupBy { it.scheduleId }
        ScheduleData(
            accounts = accounts,
            schedules = schedules.mapNotNull { s ->
                val clip = clipById[s.clipId] ?: return@mapNotNull null
                ScheduleView(s, clip, projectById[clip.projectId], targetsBySchedule[s.id].orEmpty().mapNotNull { row -> accountById[row.socialAccountId]?.let { TargetView(row, it) } })
            },
            clips = clips,
            projects = projectById,
            stats = stats.sortedBy { it.recordedAt }.groupBy { it.scheduleAccountId },
        )
    }
}

fun PostStatEntity.numbers() = StatNumbers(views, likes, comments, shares)

fun List<PostStatEntity>.points() = map { StatPoint(Wall.of(it.recordedAt), it.numbers()) }

@Composable
fun rememberScheduleData(repo: Repository): State<ScheduleData?> {
    val flow = remember(repo) { scheduleFlow(repo) }
    return flow.collectAsState(initial = null)
}

private fun hexColor(value: String?): Color =
    value?.trimStart('#')?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

@Composable
fun AccountDot(color: String, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(hexColor(color)))
}

/** Ikon platform akun sosmed. */
internal fun platformIcon(platform: String) = when (platform) {
    "tiktok" -> ClipMiIcons.TikTok
    "instagram" -> ClipMiIcons.Instagram
    "youtube" -> ClipMiIcons.YouTube
    "facebook" -> ClipMiIcons.Facebook
    else -> ClipMiIcons.Globe
}

private fun hhmm(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun LocalDate.plusDays(days: Int) = plus(days, DateTimeUnit.DAY)

// ---------------------------------------------------------------------------------------------------------------
// Tab Jadwal
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tab Jadwal: jadwal terlewat, minggu (kalender bulan bisa dibuka), agenda hari terpilih, antrean klip siap unggah dengan
 * Isi otomatis, dan akun sosmed. Detail jadwal dibuka di layar Siap Unggah.
 */
@Composable
fun ScheduleScreen(
    repo: Repository, engine: MediaEngine, scheduler: IosScheduler, renders: Renders,
    onOpenAccounts: () -> Unit, onOpenSchedule: (Long) -> Unit, onNewSchedule: (clipId: Long, date: LocalDate?) -> Unit,
) {
    val data by rememberScheduleData(repo)
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var monthOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(Wall.today()) }
    var hidden by remember { mutableStateOf(listOf<Long>()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<LocalDate?>(null) }
    var allowed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        engine.remindersAllowed(object : AllowedListener { override fun finished(state: String) { allowed = state } })
        // Pengingat dipasang ulang saat tab dibuka (misalnya setelah pulih dari cadangan).
        scheduler.armReminders()
    }

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
        val byDay = visible.groupBy { it.at.date }
        val listState = rememberLazyListState()
        LaunchedEffect(banner) { if (banner != null) listState.animateScrollToItem(0) }

        Box(Modifier.fillMaxSize()) {
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
                if (allowed == "denied") {
                    item(key = "notif") {
                        ClipMiCard {
                            Text("Notifikasi mati", style = MaterialTheme.typography.titleSmall, color = ClipMi.colors.warn)
                            Text("Pengingat \"Waktunya posting\" tidak akan muncul. Izinkan di Pengaturan iPhone › ClipMI › Notifikasi.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
                        }
                    }
                }
                if (overdue.isNotEmpty()) {
                    item(key = "overdue") {
                        OverdueCard(overdue, onOpen = onOpenSchedule, onReschedule = { id ->
                            scope.launch { scheduler.reschedule(id)?.let { (_, message) -> banner = message } }
                        })
                    }
                }
                item(key = "calendar") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val (start, end) = Schedules.weekRange(selected)
                        val label = if (start.month == end.month) "${start.day} – ${IdDate.dayMonth(end)}" else "${IdDate.dayMonth(start)} – ${IdDate.dayMonth(end)}"
                        PeriodNav(label, monthOpen, { monthOpen = !monthOpen }, onShift = { selected = selected.plusDays(7 * it) }, onToday = { selected = Wall.today() })
                        if (monthOpen) MonthCard(selected, byDay) { selected = it; monthOpen = false }
                        WeekStrip(start, selected, byDay) { selected = it }
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
                item(key = "agenda-$selected") { DaySection(repo, engine, selected, byDay[selected].orEmpty(), now, onOpenSchedule) { pickFor = it } }
                queueView(repo, engine, scheduler, d, open = queueOpen, onToggle = { queueOpen = !queueOpen }, onSchedule = { onNewSchedule(it, null) }, onAutoFill = {
                    scope.launch {
                        val outcome = scheduler.autoFill()
                        banner = outcome.banner
                        outcome.firstDate?.let { selected = it }
                    }
                }, onOpenAccounts = onOpenAccounts, onRender = { clip ->
                    if (renders.active == null) {
                        renders.start(clip.id)
                        notifier.show("Merender klip · biarkan aplikasi tetap terbuka.")
                    } else {
                        notifier.show("Tunggu render yang sedang berjalan selesai.")
                    }
                })
            }
        }
    }

    pickFor?.let { day ->
        val d = data ?: return@let
        ClipPicker(repo, engine, d, onDismiss = { pickFor = null }) { clip ->
            pickFor = null
            onNewSchedule(clip.id, day)
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

/** Navigasi minggu: label minggu (ketuk untuk kalender bulan), ‹ ›, dan Hari ini. */
@Composable
private fun PeriodNav(label: String, monthOpen: Boolean, onToggle: () -> Unit, onShift: (Int) -> Unit, onToday: () -> Unit) {
    val c = ClipMi.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
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
    val today = Wall.today()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0..6) {
            val day = start.plusDays(i)
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
                    day.day.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = when {
                        isSelected -> c.onAccent
                        day == today -> c.accent
                        day < today -> c.textMuted
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
private fun DaySection(repo: Repository, engine: MediaEngine, day: LocalDate, schedules: List<ScheduleView>, now: LocalDateTime, onOpen: (Long) -> Unit, onAdd: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val today = day == Wall.today()
    val canAdd = day >= Wall.today()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (today) "Hari ini · " else "") + "${IdDate.WEEKDAYS[day.dayOfWeek.isoDayNumber - 1]}, ${IdDate.dayMonth(day)}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (today) c.accent else c.textBody,
                modifier = Modifier.weight(1f),
            )
            if (canAdd && schedules.isNotEmpty()) ActionChip("Jadwalkan", { onAdd(day) }, icon = ClipMiIcons.Plus, height = 34.dp)
        }
        if (schedules.isEmpty()) {
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
        schedules.sortedBy { it.at }.forEach { ScheduleRow(repo, engine, it, now) { onOpen(it.id) } }
    }
}

/** Satu jadwal di agenda: gambar kecil, jam, judul, titik akun, dan status. */
@Composable
private fun ScheduleRow(repo: Repository, engine: MediaEngine, s: ScheduleView, now: LocalDateTime, onClick: () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumbnail(repo, engine, s.clip, Modifier.width(36.dp).height(64.dp))
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

/** Kalender bulan (di bawah navigasi minggu): bulan bisa digeser tanpa mengubah hari terpilih; ketuk tanggal untuk memilih. */
@Composable
private fun MonthCard(selected: LocalDate, byDay: Map<LocalDate, List<ScheduleView>>, onSelect: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    var month by remember { mutableStateOf(LocalDate(selected.year, selected.month, 1)) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().shadow(6.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(IdDate.monthYear(month), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = c.text, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconCircle(ClipMiIcons.Back, "Bulan sebelumnya", { month = month.plus(-1, DateTimeUnit.MONTH) }, size = 36.dp, background = Color.Transparent, border = false)
            IconCircle(ClipMiIcons.Chevron, "Bulan berikutnya", { month = month.plus(1, DateTimeUnit.MONTH) }, size = 36.dp, background = Color.Transparent, border = false)
        }
        val (start, end) = Schedules.monthRange(month)
        val today = Wall.today()
        Row(Modifier.fillMaxWidth()) {
            IdDate.WEEKDAYS_SHORT.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        var week = start
        while (week <= end) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0..6) {
                    val day = week.plusDays(i)
                    val inMonth = day.month == month.month
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
                            day.day.toString(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (day == today || isSelected) FontWeight.ExtraBold else FontWeight.Medium),
                            color = when {
                                isSelected || day == today -> c.accent
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
            week = week.plusDays(7)
        }
    }
}

private const val WAITING_SHOWN = 4

private fun LazyListScope.queueView(
    repo: Repository, engine: MediaEngine, scheduler: IosScheduler, d: ScheduleData, open: Boolean, onToggle: () -> Unit,
    onSchedule: (Long) -> Unit, onAutoFill: () -> Unit, onOpenAccounts: () -> Unit, onRender: (ClipEntity) -> Unit,
) {
    val queue = d.clips.filter { it.render == RenderStatus.Done && scheduler.outputExists(it) && it.id !in d.scheduledClipIds }
        .sortedByDescending { it.renderedAt ?: 0 }
    val waiting = d.clips.filter { it.review == ReviewStatus.Selected && it.render != RenderStatus.Done }.sortedByDescending { it.updatedAt }
    if (queue.isEmpty() && waiting.isEmpty()) return
    item(key = "queue-label") {
        val c = ClipMi.colors
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Antrean", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    listOfNotNull(queue.size.takeIf { it > 0 }?.let { "$it siap dijadwalkan" }, waiting.size.takeIf { it > 0 }?.let { "$it belum dirender" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
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
                        style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, modifier = Modifier.weight(1f),
                    )
                    if (d.hasSlots) PrimaryButton("Isi otomatis", onAutoFill, icon = ClipMiIcons.Sparkle, height = 40.dp)
                    else SecondaryButton("Atur slot", onOpenAccounts, height = 40.dp)
                }
            }
        }
        items(queue, key = { "q-${it.id}" }) { clip ->
            QueueCard(repo, engine, clip, d.projects[clip.projectId]?.title) { PrimaryButton("Jadwalkan", { onSchedule(clip.id) }, height = 40.dp, icon = ClipMiIcons.Calendar) }
        }
    }
    if (waiting.isNotEmpty()) {
        item(key = "queue-waiting") { QueueSubheader("Terpilih, belum dirender", waiting.size) }
        items(waiting.take(WAITING_SHOWN), key = { "w-${it.id}" }) { clip ->
            val c = ClipMi.colors
            ClipMiCard(padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClipThumbnail(repo, engine, clip, Modifier.width(36.dp).height(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(Timecode.format(clip.duration), d.projects[clip.projectId]?.title).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionChip("Render", { onRender(clip) }, icon = ClipMiIcons.Film, height = 34.dp, enabled = d.projects[clip.projectId]?.videoPath != null)
                }
            }
        }
        if (waiting.size > WAITING_SHOWN) {
            item(key = "queue-more") {
                Text("dan ${waiting.size - WAITING_SHOWN} lainnya; render dari halaman project.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun QueueSubheader(label: String, count: Int) {
    Text("$label · $count", style = MaterialTheme.typography.labelLarge, color = ClipMi.colors.textMuted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun QueueCard(repo: Repository, engine: MediaEngine, clip: ClipEntity, project: String?, action: @Composable () -> Unit) {
    val c = ClipMi.colors
    ClipMiCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumbnail(repo, engine, clip, Modifier.width(44.dp).height(78.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Timecode.format(clip.duration), project).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    }
}

/** Pilih klip antrean untuk dijadwalkan di hari tertentu. */
@Composable
private fun ClipPicker(repo: Repository, engine: MediaEngine, d: ScheduleData, onDismiss: () -> Unit, onPick: (ClipEntity) -> Unit) {
    val queue = d.clips.filter { it.render == RenderStatus.Done && it.outputPath != null && it.id !in d.scheduledClipIds }.sortedByDescending { it.renderedAt ?: 0 }
    ClipMiSheet(onDismiss, title = "Pilih klip", subtitle = "Klip yang sudah dirender dan belum dijadwalkan.") {
        if (queue.isEmpty()) Text("Belum ada klip siap unggah. Render klip terpilih di project dulu.", style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.textMuted)
        queue.forEach { clip -> QueueCard(repo, engine, clip, d.projects[clip.projectId]?.title) { SecondaryButton("Pilih", { onPick(clip) }, height = 40.dp) } }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Jadwalkan / ubah jadwal
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String, value: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ClipMi.colors.textBody, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted) }
    }
}

/**
 * Jadwal baru untuk satu klip (besok 19.00 ke semua akun, atau tanggal pilihan dari kalender), atau ubah jadwal:
 * tanggal, jam, akun tujuan, caption, hashtag dengan penghitung batas tiap platform, dan catatan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditScreen(repo: Repository, engine: MediaEngine, scheduler: IosScheduler, scheduleId: Long, clipId: Long, date: LocalDate?, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val data by rememberScheduleData(repo)
    val d = data
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val schedule = d?.schedule(scheduleId)
    val clip = schedule?.clip ?: d?.clips?.firstOrNull { it.id == clipId }

    var loaded by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf(Wall.today().plusDays(1)) }
    var time by remember { mutableStateOf(hhmm(Slots.DEFAULT_HOUR, 0)) }
    var accounts by remember { mutableStateOf(listOf<Long>()) }
    var caption by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    LaunchedEffect(d != null, clip?.id) {
        if (loaded || d == null || clip == null) return@LaunchedEffect
        if (schedule != null) {
            day = schedule.at.date
            time = hhmm(schedule.at.hour, schedule.at.minute)
            accounts = schedule.targets.map { it.account.id }
            caption = Schedules.effectiveCaption(schedule.entity.caption, clip.caption)
            hashtags = Schedules.effectiveHashtags(schedule.entity.hashtags?.let(scheduler::decodeList), scheduler.decodeList(clip.hashtags)).joinToString(" ")
            note = schedule.entity.note.orEmpty()
        } else {
            date?.let { day = it }
            accounts = d.accounts.map { it.id }
            caption = clip.caption.orEmpty()
            hashtags = scheduler.decodeList(clip.hashtags).joinToString(" ")
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader(if (scheduleId == 0L) "Jadwalkan klip" else "Ubah jadwal", onBack)
        if (d == null || clip == null) return@Column
        val chosen = d.accounts.filter { it.id in accounts }
        val title = ClipText.displayTitle(clip.title, clip.partNumber)
        val counters = Schedules.counters(chosen.map { it.platform }, caption, hashtags, title)

        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClipThumbnail(repo, engine, clip, Modifier.width(48.dp).height(84.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(Timecode.format(clip.duration), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField("Tanggal", "${IdDate.WEEKDAYS_SHORT[day.dayOfWeek.isoDayNumber - 1]}, ${IdDate.dayMonth(day)} ${day.year}", { picking = "date" }, Modifier.weight(1.4f))
                PickerField("Jam", time.replace(':', '.'), { picking = "time" }, Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Akun tujuan")
                if (d.accounts.isEmpty()) Text("Belum ada akun. Tambahkan dari tombol akun di tab Jadwal.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                d.accounts.forEach { account ->
                    val on = account.id in accounts
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else c.card).border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Checkbox) { accounts = if (on) accounts - account.id else accounts + account.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(hexColor(account.color)), contentAlignment = Alignment.Center) {
                            Icon(platformIcon(account.platform), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            Text(listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        Icon(if (on) ClipMiIcons.CircleCheck else ClipMiIcons.Plus, contentDescription = null, tint = if (on) c.accent else c.textMuted, modifier = Modifier.size(22.dp))
                    }
                }
                if (tried && accounts.isEmpty()) Text("Pilih minimal satu akun tujuan.", style = MaterialTheme.typography.bodySmall, color = c.bad)
            }
            Field(caption, { caption = it.take(10_000) }, label = "Caption", minLines = 4, maxLines = 10)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(hashtags, { hashtags = it }, label = "Hashtag", placeholder = "#kajian #sabar")
                val over = counters.flatMap { counter -> counter.counts.filter { it.over }.map { counter.platform to it } }
                if (over.isNotEmpty()) {
                    over.forEach { (platform, count) ->
                        Text("$platform: ${count.label} ${count.value}/${count.limit}, melewati batas", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.bad)
                    }
                } else if (counters.isNotEmpty()) {
                    Text(
                        counters.joinToString(" · ") { counter -> counter.platform + " " + counter.counts.joinToString(", ") { "${it.value}/${it.limit}" } },
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Field(note, { note = it.take(2000) }, label = "Catatan", placeholder = "Misalnya: pakai musik latar, tag akun pembicara", minLines = 2, maxLines = 5)
        }

        Row(
            Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = ScreenGutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (scheduleId != 0L) IconCircle(ClipMiIcons.Trash, "Hapus jadwal", { confirmDelete = true }, size = 48.dp, tint = c.bad)
            PrimaryButton(if (scheduleId == 0L) "Jadwalkan" else "Simpan", modifier = Modifier.weight(1f), icon = ClipMiIcons.Calendar, onClick = {
                tried = true
                if (accounts.isEmpty()) return@PrimaryButton
                val (h, m) = Slots.parseTime(time) ?: return@PrimaryButton
                scope.launch {
                    val at = LocalDateTime(day, LocalTime(h, m))
                    val id = scheduler.saveSchedule(scheduleId, clip.id, at, d.accounts.map { it.id }.filter { it in accounts }, caption, ClipImporter.hashtags(JsonPrimitive(hashtags)), note)
                    notifier.show(if (at < Wall.now()) "Jadwal tersimpan (waktunya sudah lewat)." else "Jadwal tersimpan: ${IdDate.weekdayDayMonthTime(at)}.")
                    onSaved(id)
                }
            })
        }
    }

    when (picking) {
        "date" -> DatePickerSheet(day, onDismiss = { picking = null }) { day = it; picking = null }
        "time" -> {
            val (h, m) = Slots.parseTime(time) ?: (Slots.DEFAULT_HOUR to 0)
            TimePickerDialog(h, m, onDismiss = { picking = null }) { hh, mm -> time = hhmm(hh, mm); picking = null }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Hapus jadwal ini?", "Klipnya kembali ke antrean. Status terbit dan link jadwal ini ikut terhapus.", "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    scheduler.deleteSchedule(scheduleId)
                    notifier.show("Jadwal dihapus.")
                    onSaved(0)
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Isian pilihan yang membuka dialog (tanggal, jam): label kecil + nilai dalam kotak. */
@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ClipMi.colors.textBody)
        SecondaryButton(value, onClick, modifier = Modifier.fillMaxWidth(), height = 52.dp)
    }
}

/** Pemilih jam 24 jam (slot rutin, jam jadwal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val c = ClipMi.colors
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Pilih jam", style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.panel,
                        selectorColor = c.accent,
                        timeSelectorSelectedContainerColor = c.accentSoft,
                        timeSelectorSelectedContentColor = c.accent,
                        timeSelectorUnselectedContainerColor = c.panel,
                        timeSelectorUnselectedContentColor = c.text,
                        clockDialSelectedContentColor = c.onAccent,
                        clockDialUnselectedContentColor = c.text,
                    ),
                )
            }
        },
        confirmButton = { GhostButton("Pakai", { onPick(state.hour, state.minute) }) },
        dismissButton = { GhostButton("Batal", onDismiss) },
    )
}

/** Pemilih tanggal (DatePicker memakai milidetik tengah malam UTC). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
private fun DatePickerSheet(date: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GhostButton("Pakai", {
                state.selectedDateMillis?.let { onPick(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) } ?: onDismiss()
            })
        },
        dismissButton = { GhostButton("Batal", onDismiss) },
        colors = DatePickerDefaults.colors(containerColor = c.card),
    ) {
        DatePicker(
            state = state,
            showModeToggle = false,
            title = { Text("Pilih tanggal", style = MaterialTheme.typography.titleMedium, color = c.textMuted, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            colors = DatePickerDefaults.colors(
                containerColor = c.card,
                selectedDayContainerColor = c.accent,
                selectedDayContentColor = c.onAccent,
                todayDateBorderColor = c.accent,
                todayContentColor = c.accent,
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Siap Unggah
// ---------------------------------------------------------------------------------------------------------------

/**
 * Siap Unggah (dari kalender atau jadwal terlewat): video dan teks untuk ditempel, lalu tiap akun tujuan dibagikan
 * (lembar bagikan iOS), ditandai terbit (dengan link opsional) atau dilewati.
 */
@Composable
fun ReadyScreen(repo: Repository, engine: MediaEngine, scheduler: IosScheduler, renders: Renders, scheduleId: Long, onBack: () -> Unit, onEdit: () -> Unit, onOpened: (Long) -> Unit) {
    val data by rememberScheduleData(repo)
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val clipboard = LocalClipboardManager.current

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
        val hashtags = Schedules.effectiveHashtags(s.entity.hashtags?.let(scheduler::decodeList), scheduler.decodeList(clip.hashtags))
        val caption = Schedules.effectiveCaption(s.entity.caption, clip.caption)
        val uploadText = ClipText.shareText(clip.title, clip.partNumber, caption, hashtags)
        val fileOk = scheduler.outputExists(clip)
        val copy: (String, String) -> Unit = { label, text ->
            clipboard.setText(AnnotatedString(text))
            notifier.show("$label tersalin.")
        }

        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ClipThumbnail(repo, engine, clip, Modifier.width(72.dp).height(128.dp))
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
                        scope.launch { scheduler.reschedule(s.id)?.let { (id, message) -> notifier.show(message); if (id != s.id) onOpened(id) } }
                    }, height = 40.dp)
                }
            }
            if (s.state != ScheduleState.Published) {
                val busy = renders.active == clip.id
                val warnings = listOfNotNull(
                    if (!fileOk && !busy) "File hasil render tidak ditemukan." else null,
                    if (clip.changedAfterRender && !busy) "Klip diubah setelah dirender." else null,
                )
                warnings.forEach { warning ->
                    Notice(Tone.Warn, warning) {
                        if (s.project?.videoPath != null && renders.active == null) SecondaryButton("Render ulang", { renders.start(clip.id) }, height = 40.dp)
                    }
                }
                if (busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sedang dirender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        ProgressBar(renders.fraction.toFloat(), color = c.busy)
                    }
                }
            }

            ClipMiCard(spacing = 12.dp) {
                CopyBlock("Judul", s.title, copy) { Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text) }
                if (caption.isNotBlank()) CopyBlock("Caption", caption, copy) { Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (hashtags.isNotEmpty()) {
                    val tags = hashtags.joinToString(" ")
                    CopyBlock("Hashtag", tags, copy) { Text(tags, style = MaterialTheme.typography.bodySmall, color = c.accent) }
                }
                s.entity.note?.let { Text("Catatan: $it", style = MaterialTheme.typography.bodySmall, color = c.warn) }
                SecondaryButton("Salin semua", { copy("Semua teks", uploadText) }, icon = ClipMiIcons.Copy, height = 44.dp, modifier = Modifier.fillMaxWidth())
            }

            s.targets.forEach { target ->
                TargetCard(repo, engine, scheduler, s, target, uploadText, fileOk, d.stats[target.row.id].orEmpty())
            }
        }
    }
}

@Composable
private fun Notice(tone: Tone, text: String, action: @Composable () -> Unit = {}) {
    val (fg, bg) = tone.colors()
    Column(Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.medium).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ClipMiIcons.Alert, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.text, modifier = Modifier.weight(1f))
        }
        action()
    }
}

@Composable
private fun CopyBlock(label: String, text: String, copy: (String, String) -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted)
            content()
        }
        IconCircle(ClipMiIcons.Copy, "Salin ${label.lowercase()}", { copy(label, text) }, size = 36.dp, background = Color.Transparent, border = false)
    }
}

@Composable
private fun TargetCard(
    repo: Repository, engine: MediaEngine, scheduler: IosScheduler, s: ScheduleView, target: TargetView, uploadText: String, fileOk: Boolean,
    stats: List<PostStatEntity>,
) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val clipboard = LocalClipboardManager.current
    val row = target.row
    val account = target.account
    var sheet by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    val pending = row.status == TargetStatus.PENDING
    val published = row.status == TargetStatus.PUBLISHED
    val latest = stats.lastOrNull()
    var expanded by remember(row.id, row.status) { mutableStateOf(false) }

    ClipMiCard(onClick = if (published) ({ expanded = !expanded }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountDot(account.color, 12.dp)
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    if (published) listOfNotNull(
                        row.publishedAt?.let { "Terbit ${IdDate.dayMonth(Wall.of(it))}" },
                        latest?.views?.let { "${PostStats.number(it)} tayangan" } ?: if (row.url == null) "belum ada link" else null,
                    ).joinToString(" · ")
                    else listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                    if (pending) DropdownMenuItem(text = { Text("Lewati akun ini") }, onClick = { menu = false; scope.launch { scheduler.markTarget(row.id, TargetStatus.SKIPPED) } })
                    if (published) DropdownMenuItem(text = { Text(if (row.url == null) "Tambah link" else "Ubah link") }, onClick = { menu = false; sheet = true })
                    DropdownMenuItem(text = { Text("Isi statistik manual") }, onClick = { menu = false; manual = true })
                    if (!pending) DropdownMenuItem(text = { Text("Kembalikan ke Menunggu") }, onClick = { menu = false; scope.launch { scheduler.markTarget(row.id, TargetStatus.PENDING) } })
                }
            }
        }
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Bagikan ke ${Platforms.label(account.platform)}",
                    {
                        clipboard.setText(AnnotatedString(uploadText))
                        val output = s.clip.outputPath?.let { repo.absolute(it) }
                        if (fileOk && output != null) {
                            notifier.show("Caption & hashtag disalin. Pilih ${Platforms.label(account.platform)} di lembar bagikan.")
                            engine.shareFile(output)
                        } else {
                            notifier.show("File hasil tidak ditemukan. Render ulang klip ini.")
                        }
                    },
                    icon = ClipMiIcons.Share, height = 44.dp, modifier = Modifier.weight(1f),
                )
                SecondaryButton("Terbit", { sheet = true }, icon = ClipMiIcons.Check, height = 44.dp)
            }
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.panel).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(ClipMiIcons.Link, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                Text(url.removePrefix("https://").removePrefix("http://"), style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            ActionChip("Tambah link", { sheet = true }, icon = ClipMiIcons.Link)
        }
        val checked = row.statsCheckedAt
        val failed = row.statsError != null && checked != null && (latest == null || checked > latest.recordedAt)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    fetching -> "Mengambil statistik…"
                    failed -> "Gagal diambil: ${row.statsError}"
                    latest != null -> "Diperbarui ${IdDate.dayMonthTime(Wall.of(latest.recordedAt))} · ${StatSource.LABELS[latest.source] ?: latest.source}"
                    else -> "Belum ada statistik"
                },
                style = MaterialTheme.typography.bodySmall, color = if (failed) c.warn else c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (url != null && !fetching) {
                IconCircle(ClipMiIcons.Refresh, "Ambil statistik", {
                    fetching = true
                    scope.launch {
                        scheduler.fetchStats(listOf(row.id))
                        fetching = false
                    }
                }, size = 32.dp, background = Color.Transparent, border = false)
            }
        }
    }
    if (sheet) PublishSheet(scheduler, target, editing = !pending, onDone = { sheet = false })
    if (manual) ManualStatsSheet(repo, scheduler, target, onDone = { manual = false })
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

/** Statistik diisi manual (misalnya Instagram, atau angka dari aplikasi platformnya). */
@Composable
private fun ManualStatsSheet(repo: Repository, scheduler: IosScheduler, target: TargetView, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val fields = listOf("Tayangan", "Suka", "Komentar", "Share")
    val values = remember { mutableStateMapOf<Int, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.row.id) {
        repo.db.schedules().latestStat(target.row.id)?.let { latest ->
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
                scheduler.saveManualStats(target.row.id, StatNumbers(numbers[0], numbers[1], numbers[2], numbers[3]))
                notifier.show("Statistik tersimpan.")
                onDone()
            }
        })
    }
}

/** Tandai satu akun terbit dengan link postingan opsional, atau ubah link akun yang sudah terbit ([editing]). */
@Composable
private fun PublishSheet(scheduler: IosScheduler, target: TargetView, editing: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val current = target.row.url.orEmpty()
    var link by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    ClipMiSheet(
        onDone,
        title = if (editing) "Link postingan" else "Terbit di ${target.account.name}",
        subtitle = if (editing) target.account.name else "Link opsional, untuk statistik nanti.",
    ) {
        Field(
            link, { link = it; error = null },
            label = if (editing) null else "Link postingan", placeholder = "https://…", singleLine = true, error = error,
            trailing = { GhostButton("Tempel", { clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { link = it.trim(); error = null } }) },
        )
        val save: () -> Unit = {
            scope.launch {
                val failed = scheduler.saveLink(target.row.id, link)
                if (failed != null) error = failed else onDone()
            }
        }
        if (editing) {
            PrimaryButton("Simpan", save, enabled = link.trim() != current, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Tanpa link", { scope.launch { scheduler.markTarget(target.row.id, TargetStatus.PUBLISHED); onDone() } }, modifier = Modifier.weight(1f))
                PrimaryButton("Simpan", save, enabled = link.isNotBlank(), modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Akun sosmed
// ---------------------------------------------------------------------------------------------------------------

/** Akun sosmed: daftar akun dengan slot rutinnya, tambah, ubah, dan hapus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(repo: Repository, scheduler: IosScheduler, onBack: () -> Unit) {
    val accounts by remember { repo.db.schedules().observeAccounts() }.collectAsState(initial = null)
    var editing by remember { mutableStateOf<SocialAccountEntity?>(null) }

    Column(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
        SubScreenHeader("Akun sosmed", onBack) {
            IconCircle(ClipMiIcons.Plus, "Tambah akun", { editing = SocialAccountEntity(name = "", platform = "instagram", color = Platforms.nextColor(accounts.orEmpty().map { it.color })) })
        }
        val list = accounts ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                "Belum ada akun",
                "Akun hanya label untuk jadwal; ClipMI tidak login ke akun mana pun. Atur jam posting rutin untuk Isi otomatis.",
                icon = ClipMiIcons.Users,
                action = { PrimaryButton("Tambah akun", { editing = SocialAccountEntity(name = "", platform = "instagram", color = Platforms.COLORS[0]) }, icon = ClipMiIcons.Plus) },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { account ->
                    val slots = SlotCodec.decode(account.slots)
                    val c = ClipMi.colors
                    ClipMiCard(onClick = { editing = account }, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(hexColor(account.color)), contentAlignment = Alignment.Center) {
                                Icon(platformIcon(account.platform), contentDescription = Platforms.label(account.platform), tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Column {
                                    Text(account.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                                    Text(listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                }
                                if (slots.isEmpty()) {
                                    Text("Tanpa slot rutin", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Slots.summary(slots).split(" · ").forEach { slot ->
                                            Row(
                                                Modifier.clip(RoundedCornerShape(8.dp)).background(c.panel).padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(ClipMiIcons.Clock, null, tint = c.textMuted, modifier = Modifier.size(12.dp))
                                                Text(slot, style = MaterialTheme.typography.labelMedium, color = c.textBody)
                                            }
                                        }
                                    }
                                }
                            }
                            Icon(ClipMiIcons.Chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    editing?.let { account -> AccountSheet(scheduler, account, onDone = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSheet(scheduler: IosScheduler, account: SocialAccountEntity, onDone: () -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var name by remember { mutableStateOf(account.name) }
    var platform by remember { mutableStateOf(account.platform) }
    var handle by remember { mutableStateOf(account.handle.orEmpty()) }
    var color by remember { mutableStateOf(account.color) }
    var slots by remember { mutableStateOf(SlotCodec.decode(account.slots)) }
    var timeFor by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameError = when {
        name.isBlank() -> "Nama akun wajib diisi."
        name.trim().length > 60 -> "Nama akun maksimal 60 karakter."
        else -> null
    }
    val slotError = slots.indexOfFirst { it.days.isEmpty() }.takeIf { it >= 0 }

    ClipMiSheet(onDone, title = if (account.id == 0L) "Akun baru" else "Ubah akun") {
        Field(name, { name = it.take(60) }, label = "Nama", placeholder = "Kajian Sunnah", singleLine = true, error = nameError.takeIf { name.isNotEmpty() || account.id != 0L })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Platform")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Platforms.LABELS.forEach { (value, label) -> FilterPill(label, platform == value, { platform = value }) }
            }
        }
        Field(handle, { handle = it.take(60) }, label = "Handle (opsional)", placeholder = "@kajian.sunnah", singleLine = true)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Warna di kalender")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Platforms.COLORS.forEach { swatch ->
                    val selected = swatch.equals(color, ignoreCase = true)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(hexColor(swatch))
                            .border(if (selected) 3.dp else 0.dp, if (selected) c.text else c.line, CircleShape)
                            .clickable(role = Role.RadioButton) { color = swatch },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Slot rutin", if (slots.isEmpty()) "untuk Isi otomatis" else Slots.summary(slots))
            slots.forEachIndexed { index, slot ->
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(c.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(slot.time.replace(':', '.'), { timeFor = index }, icon = ClipMiIcons.Clock, height = 40.dp)
                        Box(Modifier.weight(1f))
                        IconCircle(ClipMiIcons.Trash, "Hapus slot", { slots = slots.filterIndexed { i, _ -> i != index } }, size = 36.dp, background = c.card, tint = c.bad)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Platforms.DAYS.forEach { (dayNumber, label) ->
                            val on = dayNumber in slot.days
                            Box(
                                Modifier.weight(1f).size(36.dp).clip(CircleShape).background(if (on) c.accent else c.card)
                                    .border(1.dp, if (on) c.accent else c.line, CircleShape)
                                    .clickable(role = Role.Checkbox) { slots = slots.mapIndexed { i, s -> if (i == index) Slots.toggleDay(s, dayNumber) else s } },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (on) c.onAccent else c.textMuted) }
                        }
                    }
                    if (slotError == index) Text("Pilih minimal satu hari untuk slot ini.", style = MaterialTheme.typography.bodySmall, color = c.bad)
                }
            }
            if (slots.size < 12) SecondaryButton("Tambah slot", { slots = slots + Slots.next(slots) }, icon = ClipMiIcons.Plus, height = 40.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account.id != 0L) GhostButton("Hapus akun", { confirmDelete = true }, danger = true)
            Box(Modifier.weight(1f))
            PrimaryButton("Simpan", enabled = nameError == null && slotError == null, onClick = {
                scope.launch {
                    scheduler.saveAccount(account.id, name, platform, handle, color, slots)
                    notifier.show(if (account.id == 0L) "Akun ditambahkan." else "Akun diperbarui.")
                    onDone()
                }
            })
        }
    }

    timeFor?.let { index ->
        val (hour, minute) = Slots.parseTime(slots[index].time) ?: (Slots.DEFAULT_HOUR to 0)
        TimePickerDialog(hour, minute, onDismiss = { timeFor = null }) { h, m ->
            slots = slots.mapIndexed { i, s -> if (i == index) Slot(hhmm(h, m), s.days) else s }
            timeFor = null
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Hapus akun ini?", "\"${account.name}\" dilepas dari semua jadwal. Jadwal yang jadi tanpa akun dihapus dan klipnya kembali ke antrean.", "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    scheduler.deleteAccount(account.id)
                    notifier.show("Akun dihapus.")
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
