package id.clipmi.core.schedule

import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor

// Padanan java.time untuk aplikasi Android dan tes JVM: logika jadwal ada di commonMain (kotlinx-datetime, dipakai
// juga iOS); fungsi di sini hanya mengubah tipe lalu memanggilnya, jadi hasilnya sama persis.

fun LocalDateTime.minute(): LocalDateTime = truncatedTo(ChronoUnit.MINUTES)

fun IdDate.time(at: LocalDateTime): String = time(at.toKotlinLocalDateTime())

/** "j M" dari tanggal atau tanggal-jam java.time. */
fun IdDate.dayMonth(at: TemporalAccessor): String = dayMonth(LocalDate.from(at).toKotlinLocalDate())

fun IdDate.dayMonthTime(at: LocalDateTime): String = dayMonthTime(at.toKotlinLocalDateTime())

fun IdDate.full(at: LocalDateTime): String = full(at.toKotlinLocalDateTime())

fun IdDate.weekdayDayMonthTime(at: LocalDateTime): String = weekdayDayMonthTime(at.toKotlinLocalDateTime())

fun IdDate.monthYear(at: LocalDate): String = monthYear(at.toKotlinLocalDate())

fun Schedules.isOverdue(at: LocalDateTime, statuses: List<String>, now: LocalDateTime): Boolean =
    isOverdue(at.toKotlinLocalDateTime(), statuses, now.toKotlinLocalDateTime())

fun Schedules.rescheduleAt(openSlots: Map<LocalDateTime, List<Long>>, pendingCount: Int, scheduledAt: LocalDateTime, now: LocalDateTime): LocalDateTime =
    rescheduleAt(
        openSlots.entries.associate { (at, ids) -> at.toKotlinLocalDateTime() to ids }, pendingCount, scheduledAt.toKotlinLocalDateTime(), now.toKotlinLocalDateTime(),
    ).toJavaLocalDateTime()

fun Schedules.weekRange(anchor: LocalDate): Pair<LocalDate, LocalDate> =
    weekRange(anchor.toKotlinLocalDate()).let { (a, b) -> a.toJavaLocalDate() to b.toJavaLocalDate() }

fun Schedules.monthRange(anchor: LocalDate): Pair<LocalDate, LocalDate> =
    monthRange(anchor.toKotlinLocalDate()).let { (a, b) -> a.toJavaLocalDate() to b.toJavaLocalDate() }

fun Reminders.notes(due: List<DueSchedule>, now: LocalDateTime): List<ReminderNote> = notes(due, now.toKotlinLocalDateTime())

fun QueueFiller.until(now: LocalDateTime): LocalDateTime = until(now.toKotlinLocalDateTime()).toJavaLocalDateTime()

/** Slot kosong (lihat [QueueFiller.openSlots]) dengan kunci java.time, urut waktu. */
fun QueueFiller.openSlots(accounts: List<SlotAccount>, now: LocalDateTime, taken: Set<Pair<Long, LocalDateTime>>): java.util.SortedMap<LocalDateTime, List<Long>> =
    openSlots(accounts, now.toKotlinLocalDateTime(), taken.map { (id, at) -> id to at.toKotlinLocalDateTime() }.toSet())
        .entries.associateTo(java.util.TreeMap()) { (at, ids) -> at.toJavaLocalDateTime() to ids }

fun QueueFiller.fill(
    queue: List<QueueClip>,
    openSlots: Map<LocalDateTime, List<Long>>,
    seriesAfter: Map<Long, LocalDateTime>,
    pendingParts: List<QueueClip>,
): FillResult = fill(
    queue,
    openSlots.entries.associate { (at, ids) -> at.toKotlinLocalDateTime() to ids },
    seriesAfter.mapValues { it.value.toKotlinLocalDateTime() },
    pendingParts,
)

fun QueueFiller.plan(clips: List<ClipRecord>, schedules: List<ScheduleRecord>, accounts: List<SlotAccount>, now: LocalDateTime): FillResult =
    plan(clips, schedules, accounts, now.toKotlinLocalDateTime())

fun Slots.times(slots: List<Slot>, after: LocalDateTime, until: LocalDateTime): List<LocalDateTime> =
    times(slots, after.toKotlinLocalDateTime(), until.toKotlinLocalDateTime()).map { it.toJavaLocalDateTime() }

/** Waktu jadwal sebagai java.time (aplikasi Android). */
val PlannedSchedule.javaAt: LocalDateTime get() = at.toJavaLocalDateTime()

fun PostStats.statAt(publishedAt: LocalDateTime?, stats: List<StatPoint>, days: Int): StatPoint? = statAt(publishedAt?.toKotlinLocalDateTime(), stats, days)

fun PostStats.isDue(publishedAt: LocalDateTime, checkedAt: LocalDateTime?, now: LocalDateTime): Boolean =
    isDue(publishedAt.toKotlinLocalDateTime(), checkedAt?.toKotlinLocalDateTime(), now.toKotlinLocalDateTime())

fun PostStats.refreshFrom(now: LocalDateTime): LocalDateTime = refreshFrom(now.toKotlinLocalDateTime()).toJavaLocalDateTime()

/** Catatan statistik dengan waktu java.time (aplikasi Android dan tes). */
fun StatPoint(recordedAt: LocalDateTime, numbers: StatNumbers): StatPoint = StatPoint(recordedAt.toKotlinLocalDateTime(), numbers)
