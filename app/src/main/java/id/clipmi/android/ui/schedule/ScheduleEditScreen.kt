package id.clipmi.android.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.domain.Wall
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.styles.FieldLabel
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.Slots
import id.clipmi.core.transcript.Timecode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jadwal baru untuk satu klip (besok 19.00 ke semua akun, atau tanggal pilihan dari kalender), atau ubah jadwal:
 * tanggal, jam, akun tujuan, caption, hashtag dengan penghitung batas tiap platform, dan catatan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditScreen(container: AppContainer, scheduleId: Long, clipId: Long, date: String?, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val data by rememberScheduleData(container)
    val d = data
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val schedule = d?.schedule(scheduleId)
    val clip = schedule?.clip ?: d?.clips?.firstOrNull { it.id == clipId }

    var loaded by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toEpochDay()) }
    var time by rememberSaveable { mutableStateOf("%02d:00".format(Slots.DEFAULT_HOUR)) }
    var accounts by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var caption by rememberSaveable { mutableStateOf("") }
    var hashtags by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    LaunchedEffect(d != null, clip?.id) {
        if (loaded || d == null || clip == null) return@LaunchedEffect
        if (schedule != null) {
            day = schedule.at.toLocalDate().toEpochDay()
            time = "%02d:%02d".format(schedule.at.hour, schedule.at.minute)
            accounts = schedule.targets.map { it.account.id }
            caption = Schedules.effectiveCaption(schedule.entity.caption, clip.caption)
            hashtags = Schedules.effectiveHashtags(schedule.entity.hashtags?.let(container.analysis::decodeList), container.analysis.decodeList(clip.hashtags)).joinToString(" ")
            note = schedule.entity.note.orEmpty()
        } else {
            date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { day = it.toEpochDay() }
            accounts = d.accounts.map { it.id }
            caption = clip.caption.orEmpty()
            hashtags = container.analysis.decodeList(clip.hashtags).joinToString(" ")
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader(if (scheduleId == 0L) "Jadwalkan klip" else "Ubah jadwal", onBack)
        if (d == null || clip == null) return@Column
        val chosen = d.accounts.filter { it.id in accounts }
        val title = ClipText.displayTitle(clip.title, clip.partNumber)
        val counters = Schedules.counters(chosen.map { it.platform }, caption, hashtags, title)
        val selectedDay = LocalDate.ofEpochDay(day)

        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClipThumb(clip, Modifier.width(48.dp).height(84.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(d.projects[clip.projectId]?.title, Timecode.format(clip.duration)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField("Tanggal", "${IdDate.WEEKDAYS_SHORT[selectedDay.dayOfWeek.value - 1]}, ${IdDate.dayMonth(selectedDay)} ${selectedDay.year}", { picking = "date" }, Modifier.weight(1.4f))
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
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AccountDot(account.color, 12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            Text(listOfNotNull(Platforms.label(account.platform), account.handle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        if (on) Icon(ClipMiIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
                if (tried && accounts.isEmpty()) Text("Pilih minimal satu akun tujuan.", style = MaterialTheme.typography.bodySmall, color = c.bad)
            }
            Field(caption, { caption = it.take(10_000) }, label = "Caption", minLines = 4, maxLines = 10)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(hashtags, { hashtags = it }, label = "Hashtag", placeholder = "#kajian #sabar")
                // Melewati batas hanya ditandai; tetap boleh disimpan (sama dengan desktop).
                counters.forEach { counter ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(counter.platform, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = c.textBody)
                        counter.counts.forEach { count ->
                            Text(
                                "${count.label} ${count.value}/${count.limit}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (count.over) FontWeight.Bold else FontWeight.Normal),
                                color = if (count.over) c.bad else c.textMuted,
                            )
                        }
                    }
                }
            }
            Field(note, { note = it.take(2000) }, label = "Catatan", placeholder = "Misalnya: pakai musik latar, tag akun pembicara", minLines = 2, maxLines = 5)
        }

        Row(
            Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = ScreenGutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (scheduleId != 0L) GhostButton("Hapus jadwal", { confirmDelete = true }, danger = true)
            Box(Modifier.weight(1f))
            PrimaryButton(if (scheduleId == 0L) "Jadwalkan" else "Simpan", icon = ClipMiIcons.Calendar, onClick = {
                tried = true
                if (accounts.isEmpty()) return@PrimaryButton
                val (h, m) = Slots.parseTime(time) ?: return@PrimaryButton
                scope.launch {
                    val at = LocalDateTime.of(selectedDay, java.time.LocalTime.of(h, m))
                    val id = container.scheduler.saveSchedule(
                        scheduleId, clip.id, at, d.accounts.map { it.id }.filter { it in accounts }, caption,
                        ClipImporter.hashtags(JsonPrimitive(hashtags)), note,
                    )
                    notifier.show(if (at.isBefore(Wall.now())) "Jadwal tersimpan (waktunya sudah lewat)." else "Jadwal tersimpan: ${IdDate.weekdayDayMonthTime(at)}.")
                    onSaved(id)
                }
            })
        }
    }

    when (picking) {
        "date" -> DatePickerSheet(LocalDate.ofEpochDay(day), notBefore = null, onDismiss = { picking = null }) { day = it.toEpochDay(); picking = null }
        "time" -> {
            val (h, m) = Slots.parseTime(time) ?: (Slots.DEFAULT_HOUR to 0)
            TimePickerDialog(h, m, onDismiss = { picking = null }) { hh, mm -> time = "%02d:%02d".format(hh, mm); picking = null }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Hapus jadwal ini?", "Klipnya kembali ke antrean. Status terbit, link, dan statistik jadwal ini ikut terhapus.", "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.scheduler.deleteSchedule(scheduleId)
                    notifier.show("Jadwal dihapus.")
                    onSaved(0)
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
