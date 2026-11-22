package id.clipmi.core.schedule

import java.time.LocalDateTime

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
        at.isBefore(now) && state(statuses) != ScheduleState.Published

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
            ?: now.plusDays(1).withHour(scheduledAt.hour).withMinute(scheduledAt.minute).withSecond(scheduledAt.second).withNano(scheduledAt.nano)

    /** Hari pertama dan terakhir tampilan minggu (Senin–Minggu) atau bulan (minggu-minggu yang mencakup bulan itu). */
    fun weekRange(anchor: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        return start to start.plusDays(6)
    }

    fun monthRange(anchor: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
        val first = anchor.withDayOfMonth(1)
        val last = anchor.withDayOfMonth(anchor.lengthOfMonth())
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
        val late = due.filter { it.at.isBefore(now.minusMinutes(LATE_MINUTES)) }
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

    private fun month(at: java.time.temporal.TemporalAccessor) = MONTHS_SHORT[at.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) - 1]

    /** "H.i", misalnya "19.00". */
    fun time(at: LocalDateTime): String = "%02d.%02d".format(at.hour, at.minute)

    /** "j M", misalnya "4 Nov". */
    fun dayMonth(at: java.time.temporal.TemporalAccessor): String = "${at.get(java.time.temporal.ChronoField.DAY_OF_MONTH)} ${month(at)}"

    /** "j M, H.i". */
    fun dayMonthTime(at: LocalDateTime): String = "${dayMonth(at)}, ${time(at)}"

    /** "j M Y, H.i". */
    fun full(at: LocalDateTime): String = "${dayMonth(at)} ${at.year}, ${time(at)}"

    /** "l, j M, H.i", misalnya "Rabu, 4 Nov, 19.00". */
    fun weekdayDayMonthTime(at: LocalDateTime): String = "${WEEKDAYS[at.dayOfWeek.value - 1]}, ${dayMonthTime(at)}"

    /** "F Y", misalnya "November 2026". */
    fun monthYear(at: java.time.LocalDate): String = "${MONTHS[at.monthValue - 1]} ${at.year}"
}
