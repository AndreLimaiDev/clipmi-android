package id.clipmi.core.schedule

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Klip untuk Isi otomatis: hanya yang menentukan urutan (seri dan nomor bagian). */
data class QueueClip(val id: Long, val seriesId: Long? = null, val partNumber: Int? = null)

/** Akun dengan slot rutinnya. */
data class SlotAccount(val id: Long, val slots: List<Slot>)

/** Hasil Isi otomatis: jadwal baru (klip → waktu dan akun), klip yang belum kebagian slot, dan bagian seri yang tertahan. */
data class FillResult(val scheduled: List<PlannedSchedule>, val left: Int, val blocked: Int)

data class PlannedSchedule(val clipId: Long, val at: LocalDateTime, val accountIds: List<Long>)

/** Klip dari database untuk [QueueFiller.plan]. */
data class ClipRecord(
    val id: Long,
    val seriesId: Long?,
    val partNumber: Int?,
    val selected: Boolean,
    val renderDone: Boolean,
    val fileExists: Boolean,
    val renderedAt: LocalDateTime?,
    val scheduled: Boolean,
)

/** Jadwal yang sudah ada: klip, waktu, dan akun tujuannya. */
data class ScheduleRecord(val clipId: Long, val at: LocalDateTime, val accountIds: List<Long>)

/**
 * Isi otomatis, padanan `App\Scheduling\QueueFiller` desktop: klip di antrean dibagikan ke slot rutin akun yang masih
 * kosong. Akun yang slotnya di jam yang sama mendapat satu jadwal bersama. Klip seri tetap berurutan: bagian 2 baru
 * dijadwalkan setelah bagian 1. Bagian yang membaca database (antrean, slot terpakai) disiapkan pemanggil.
 */
object QueueFiller {
    /** Rentang slot yang diisi, dihitung dari sekarang. */
    const val DAYS = 14L

    /** Akhir rentang Isi otomatis: akhir hari ke-14 dari sekarang. */
    fun until(now: LocalDateTime): LocalDateTime = LocalDateTime(now.date.plus(DAYS, DateTimeUnit.DAY), LocalTime(23, 59, 59, 999_999_999))

    /**
     * Slot kosong mulai [now]: waktu → id akun yang slotnya di waktu itu dan belum punya jadwal di sana.
     * @param taken pasangan (akun, waktu jadwal) yang sudah terisi di rentang ini.
     */
    fun openSlots(accounts: List<SlotAccount>, now: LocalDateTime, taken: Set<Pair<Long, LocalDateTime>>): Map<LocalDateTime, List<Long>> {
        val until = until(now)
        val slots = mutableMapOf<LocalDateTime, MutableList<Long>>()
        for (account in accounts) {
            for (at in Slots.times(account.slots, now, until)) {
                if ((account.id to at.minute()) !in taken) slots.getOrPut(at) { mutableListOf() }.add(account.id)
            }
        }
        // Urut waktu (peta berurutan, pengganti SortedMap JVM).
        return slots.keys.sorted().associateWith { slots.getValue(it) }
    }

    /**
     * Mengisi slot kosong dengan klip antrean.
     * @param queue klip yang sudah dirender, filenya ada, dan belum dijadwalkan, urut waktu render lalu id.
     * @param seriesAfter waktu jadwal terakhir tiap seri yang sudah dijadwalkan: bagian berikutnya harus sesudahnya.
     * @param pendingParts klip terpilih yang belum dijadwalkan (termasuk yang belum dirender), untuk menahan bagian seri
     *   yang bagian sebelumnya belum siap.
     */
    fun fill(
        queue: List<QueueClip>,
        openSlots: Map<LocalDateTime, List<Long>>,
        seriesAfter: Map<Long, LocalDateTime>,
        pendingParts: List<QueueClip>,
    ): FillResult {
        val clips = ordered(queue)
        val keys = openSlots.keys.sorted().toMutableList()
        val after = seriesAfter.toMutableMap()
        val queued = clips.map { it.id }.toSet()
        val scheduled = mutableListOf<PlannedSchedule>()
        var blocked = 0
        // Seri yang satu bagiannya tertahan atau tidak kebagian slot: bagian berikutnya ikut menunggu.
        val stalled = mutableSetOf<Long>()

        for (clip in clips) {
            var notBefore: LocalDateTime? = null
            if (clip.seriesId != null && clip.partNumber != null) {
                val waits = pendingParts.any { it.seriesId == clip.seriesId && it.partNumber != null && it.partNumber < clip.partNumber && it.id !in queued }
                if (clip.seriesId in stalled || waits) {
                    stalled += clip.seriesId
                    blocked++
                    continue
                }
                notBefore = after[clip.seriesId]
            }
            val key = keys.firstOrNull { notBefore == null || it > notBefore }
            if (key == null) {
                clip.seriesId?.let { stalled += it }
                continue
            }
            scheduled += PlannedSchedule(clip.id, key, openSlots.getValue(key))
            keys.remove(key)
            clip.seriesId?.let { series -> after[series] = after[series]?.let { maxOf(it, key) } ?: key }
        }
        return FillResult(scheduled, clips.size - scheduled.size - blocked, blocked)
    }

    /**
     * Isi otomatis dari isi database apa adanya: menyiapkan antrean, slot terpakai, dan batas seri seperti query
     * desktop, lalu [fill].
     * @param accounts akun yang punya slot rutin.
     */
    fun plan(clips: List<ClipRecord>, schedules: List<ScheduleRecord>, accounts: List<SlotAccount>, now: LocalDateTime): FillResult {
        val until = until(now)
        val queue = clips
            .filter { it.renderDone && it.fileExists && !it.scheduled }
            .sortedWith(compareBy<ClipRecord, LocalDateTime?>(nullsFirst()) { it.renderedAt }.thenBy { it.id })
        val taken = schedules
            .filter { it.at >= now && it.at <= until }
            .flatMap { schedule -> schedule.accountIds.map { it to schedule.at.minute() } }
            .toSet()
        val seriesOf = clips.associate { it.id to it.seriesId }
        val queuedSeries = queue.mapNotNull { it.seriesId }.toSet()
        val seriesAfter = schedules
            .mapNotNull { schedule -> seriesOf[schedule.clipId]?.takeIf { it in queuedSeries }?.let { it to schedule.at } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.max().minute() }
        return fill(
            queue.map { QueueClip(it.id, it.seriesId, it.partNumber) },
            openSlots(accounts, now, taken),
            seriesAfter,
            clips.filter { it.selected && !it.scheduled }.map { QueueClip(it.id, it.seriesId, it.partNumber) },
        )
    }

    /**
     * Urutan pengisian: klip biasa menurut waktu render; bagian seri dikumpulkan dan diurutkan menurut nomor bagiannya,
     * di posisi bagian seri yang paling lama dirender.
     */
    fun ordered(clips: List<QueueClip>): List<QueueClip> {
        val position = mutableMapOf<String, Int>()
        clips.forEachIndexed { index, clip -> if (group(clip) !in position) position[group(clip)] = index }
        return clips.sortedWith(compareBy<QueueClip> { position.getValue(group(it)) }.thenBy { it.partNumber ?: 0 })
    }

    private fun group(clip: QueueClip) = if (clip.seriesId != null) "series-${clip.seriesId}" else "clip-${clip.id}"

    /** Pesan setelah Isi otomatis (`autoFill`). */
    fun banner(result: FillResult): String {
        if (result.scheduled.isEmpty()) {
            return if (result.blocked > 0 && result.left == 0) "Belum ada yang dijadwalkan: klip seri menunggu bagian sebelumnya dirender atau dijadwalkan."
            else "Belum ada yang dijadwalkan: slot rutin $DAYS hari ke depan sudah terisi semua."
        }
        val first = result.scheduled.first().at
        val last = result.scheduled.last().at
        val range = if (first.date == last.date) IdDate.dayMonth(first) else IdDate.dayMonth(first) + " – " + IdDate.dayMonth(last)
        return "${result.scheduled.size} klip dijadwalkan, $range." +
            (if (result.left > 0) " ${result.left} klip belum kebagian slot." else "") +
            (if (result.blocked > 0) " ${result.blocked} bagian seri menunggu bagian sebelumnya." else "")
    }
}

