package id.clipmi.core.schedule

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** Status satu akun tujuan jadwal (`ScheduleAccount`). */
object TargetStatus {
    const val PENDING = "pending"
    const val PUBLISHED = "published"
    const val SKIPPED = "skipped"

    val LABELS: Map<String, String> = linkedMapOf(PENDING to "Menunggu", PUBLISHED to "Sudah terbit", SKIPPED to "Dilewati")
}

/** Status keseluruhan jadwal dari status tiap akun (`Schedule::status`). */
enum class ScheduleState { Scheduled, Partial, Published }

object Schedules {
    fun state(statuses: List<String>): ScheduleState = when {
        statuses.isEmpty() -> ScheduleState.Scheduled
        statuses.all { it != TargetStatus.PENDING } -> ScheduleState.Published
        TargetStatus.PUBLISHED in statuses -> ScheduleState.Partial
        else -> ScheduleState.Scheduled
    }

    /** Waktunya sudah lewat tetapi masih ada akun yang belum terbit. */
    fun isOverdue(at: LocalDateTime, statuses: List<String>, now: LocalDateTime): Boolean =
        at < now && state(statuses) != ScheduleState.Published

    /** Caption dan hashtag milik jadwal, atau milik klip bila jadwal tidak mengubahnya. */
    fun effectiveCaption(scheduleCaption: String?, clipCaption: String?): String = scheduleCaption ?: clipCaption.orEmpty()

    fun effectiveHashtags(scheduleHashtags: List<String>?, clipHashtags: List<String>?): List<String> = scheduleHashtags ?: clipHashtags.orEmpty()

    /**
     * Caption dan hashtag disimpan kosong bila sama dengan milik klip, supaya tetap mengikuti klip bila klipnya diubah
     * (`saveSchedule`).
     */
    fun ownCaption(caption: String, clipCaption: String?): String? = caption.trim().takeIf { it != clipCaption.orEmpty().trim() }

    fun ownHashtags(hashtags: List<String>, clipHashtags: List<String>?): List<String>? = hashtags.takeIf { it != clipHashtags.orEmpty() }

    /**
     * Penghitung batas teks tiap platform (melewati batas hanya ditandai, tetap boleh disimpan). Panjang caption sama
     * dengan desktop: caption + spasi + hashtag, dalam satuan karakter JavaScript (UTF-16).
     */
    fun counters(platforms: Collection<String>, caption: String, hashtags: String, title: String): List<LimitCounter> =
        platforms.distinct().mapNotNull { platform ->
            val limit = Platforms.LIMITS[platform] ?: return@mapNotNull null
            val parts = buildList {
                limit.caption?.let { add(Count("caption", caption.length + hashtags.length + 1, it)) }
                limit.hashtags?.let { add(Count("hashtag", HASHTAG.findAll(hashtags).count(), it)) }
                limit.title?.let { add(Count("judul", title.length, it)) }
            }
            LimitCounter(Platforms.label(platform), parts)
        }

    private val HASHTAG = Regex("#[^\\s#]+")

    /**
     * Jadwal ulang untuk jadwal terlewat (`reschedule`): slot kosong berikutnya dari akun yang masih menunggu, utamanya
     * slot milik semua akun itu; tanpa slot, besok di jam yang sama.
     */
    fun rescheduleAt(openSlots: Map<LocalDateTime, List<Long>>, pendingCount: Int, scheduledAt: LocalDateTime, now: LocalDateTime): LocalDateTime =
        openSlots.entries.firstOrNull { it.value.size == pendingCount }?.key
            ?: openSlots.keys.firstOrNull()
            ?: LocalDateTime(now.date.plus(1, DateTimeUnit.DAY), scheduledAt.time)

    /** Hari pertama dan terakhir tampilan minggu (Senin–Minggu) atau bulan (minggu-minggu yang mencakup bulan itu). */
    fun weekRange(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val start = anchor.minus(anchor.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        return start to start.plus(6, DateTimeUnit.DAY)
    }

    fun monthRange(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val first = LocalDate(anchor.year, anchor.month, 1)
        val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        return weekRange(first).first to weekRange(last).second
    }
}

data class Count(val label: String, val value: Int, val limit: Int) {
    val over: Boolean get() = value > limit
}

data class LimitCounter(val platform: String, val counts: List<Count>)

/** Satu notifikasi pengingat: judul, isi, dan jadwal yang dibuka saat diketuk (null: buka daftar Jadwal). */
data class ReminderNote(val title: String, val body: String, val scheduleId: Long?)

/** Jadwal yang waktunya tiba, untuk pengingat. */
data class DueSchedule(val id: Long, val at: LocalDateTime, val clipTitle: String, val pendingAccounts: List<String>)

/**
 * Pengingat "waktunya posting", padanan `App\Scheduling\ScheduleReminder`: jadwal yang waktunya tiba dan masih punya
 * akun menunggu diberi satu notifikasi; jadwal yang sudah lewat lebih dari [LATE_MINUTES] saat pertama terlihat (HP
 * mati, aplikasi dihentikan sistem) dirangkum sebagai terlewat.
 */
object Reminders {
    const val LATE_MINUTES = 10L

    /** @param due jadwal yang belum diingatkan, waktunya ≤ sekarang, masih ada akun menunggu; urut waktu. */
    fun notes(due: List<DueSchedule>, now: LocalDateTime): List<ReminderNote> {
        val late = due.filter { it.at < now.plusMinutes(-LATE_MINUTES) }
        val onTime = due - late.toSet()
        return buildList {
            if (onTime.size == 1) {
                val schedule = onTime.first()
                add(ReminderNote("Waktunya posting", "${schedule.clipTitle} ke ${schedule.pendingAccounts.joinToString(", ")}", schedule.id))
            } else if (onTime.size > 1) {
                add(ReminderNote("Waktunya posting", "${onTime.size} klip dijadwalkan sekarang. Buka Jadwal untuk mengunggahnya.", null))
            }
            if (late.isNotEmpty()) {
                add(
                    ReminderNote(
                        "${late.size} jadwal terlewat",
                        "Belum diunggah sejak ${IdDate.dayMonthTime(late.first().at)}. Unggah sekarang atau jadwalkan ulang.",
                        if (late.size == 1) late.first().id else null,
                    ),
                )
            }
        }
    }
}

/** Format tanggal Indonesia yang sama dengan `translatedFormat` Carbon (locale id) di desktop. */
object IdDate {
    val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agt", "Sep", "Okt", "Nov", "Des")
    val MONTHS = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")

    /** Nama hari menurut nomor ISO (Senin = 1). */
    val WEEKDAYS = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    val WEEKDAYS_SHORT = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

    /** "H.i", misalnya "19.00". */
    fun time(at: LocalDateTime): String = "${pad2(at.hour)}.${pad2(at.minute)}"

    /** "j M", misalnya "4 Nov". */
    fun dayMonth(at: LocalDate): String = "${at.day} ${MONTHS_SHORT[at.month.number - 1]}"

    fun dayMonth(at: LocalDateTime): String = dayMonth(at.date)

    /** "j M, H.i". */
    fun dayMonthTime(at: LocalDateTime): String = "${dayMonth(at)}, ${time(at)}"

    /** "j M Y, H.i". */
    fun full(at: LocalDateTime): String = "${dayMonth(at)} ${at.year}, ${time(at)}"

    /** "l, j M, H.i", misalnya "Rabu, 4 Nov, 19.00". */
    fun weekdayDayMonthTime(at: LocalDateTime): String = "${WEEKDAYS[at.dayOfWeek.isoDayNumber - 1]}, ${dayMonthTime(at)}"

    /** "F Y", misalnya "November 2026". */
    fun monthYear(at: LocalDate): String = "${MONTHS[at.month.number - 1]} ${at.year}"
}
