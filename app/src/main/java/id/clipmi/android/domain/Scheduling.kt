package id.clipmi.android.domain

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.data.ScheduleEntity
import id.clipmi.android.data.SocialAccountEntity
import id.clipmi.android.reminders.ReminderAlarm
import id.clipmi.android.work.StatsQueue
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.ClipRecord
import id.clipmi.core.schedule.QueueFiller
import id.clipmi.core.schedule.ScheduleRecord
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.Slot
import id.clipmi.core.schedule.SlotAccount
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.TargetStatus
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.Slots
import id.clipmi.core.schedule.minute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Jam dinding HP: waktu jadwal disimpan sebagai epoch milidetik dan ditampilkan menurut zona waktu HP saat ini. */
object Wall {
    fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

    fun of(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    fun millis(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

@Serializable
private data class SlotJson(val time: String, val days: List<Int>)

/** Slot rutin akun dalam JSON yang sama dengan desktop: [{"time": "19:00", "days": [1, 2, ...]}]. */
object SlotCodec {
    private val serializer = ListSerializer(SlotJson.serializer())

    fun decode(json: String?): List<Slot> =
        json?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }?.map { Slot(it.time, it.days) }.orEmpty()

    fun encode(slots: List<Slot>?): String? = slots?.takeIf { it.isNotEmpty() }?.let { list -> Json.encodeToString(serializer, list.map { SlotJson(it.time, it.days) }) }
}

/**
 * Jadwal posting (halaman Jadwal desktop): akun sosmed, jadwal per klip ke banyak akun, Isi otomatis, jadwal ulang,
 * status terbit per akun, link postingan, dan statistik manual. Setiap perubahan memasang ulang alarm pengingat.
 */
class Scheduler(private val app: Context, private val db: AppDatabase, private val analysis: Analysis) {
    private val dao get() = db.schedules()

    // --- Akun ---------------------------------------------------------------------------------------------------

    /** Menyimpan akun (baru bila [id] 0). Slot dirapikan seperti desktop; tanpa slot menjadi null. */
    suspend fun saveAccount(id: Long, name: String, platform: String, handle: String?, color: String, slots: List<Slot>) {
        val now = System.currentTimeMillis()
        val slotJson = SlotCodec.encode(Slots.normalize(slots))
        if (id == 0L) {
            dao.insertAccount(SocialAccountEntity(name = name.trim(), platform = platform, handle = handle?.trim()?.ifEmpty { null }, color = color.uppercase(), slots = slotJson))
        } else {
            val old = dao.account(id) ?: return
            dao.updateAccount(old.copy(name = name.trim(), platform = platform, handle = handle?.trim()?.ifEmpty { null }, color = color.uppercase(), slots = slotJson, updatedAt = now))
        }
    }

    suspend fun deleteAccount(id: Long) {
        dao.deleteAccount(id)
        ReminderAlarm.arm(app)
    }

    // --- Jadwal -------------------------------------------------------------------------------------------------

    /**
     * Membuat atau menyimpan jadwal. Caption dan hashtag yang sama dengan milik klip disimpan kosong (tetap mengikuti
     * klip). Akun yang tetap dipilih mempertahankan status terbitnya; waktu yang berubah membuat pengingat dikirim lagi.
     */
    suspend fun saveSchedule(id: Long, clipId: Long, at: LocalDateTime, accountIds: List<Long>, caption: String, hashtags: List<String>, note: String): Long {
        val clip = db.clips().find(clipId) ?: error("Klip tidak ditemukan.")
        val now = System.currentTimeMillis()
        val atMillis = Wall.millis(at.minute())
        val ownCaption = Schedules.ownCaption(caption, clip.caption)
        val ownHashtags = Schedules.ownHashtags(hashtags, analysis.decodeList(clip.hashtags))?.let(analysis::encodeList)
        val scheduleId = db.withTransaction {
            val saved = if (id == 0L) {
                dao.insertSchedule(ScheduleEntity(clipId = clipId, scheduledAt = atMillis, caption = ownCaption, hashtags = ownHashtags, note = note.trim().ifEmpty { null }))
            } else {
                val old = dao.schedule(id) ?: error("Jadwal tidak ditemukan.")
                dao.updateSchedule(
                    old.copy(
                        scheduledAt = atMillis, caption = ownCaption, hashtags = ownHashtags, note = note.trim().ifEmpty { null },
                        notifiedAt = if (old.scheduledAt != atMillis) null else old.notifiedAt, updatedAt = now,
                    ),
                )
                id
            }
            syncTargets(saved, accountIds)
            saved
        }
        ReminderAlarm.arm(app)
        return scheduleId
    }

    private suspend fun syncTargets(scheduleId: Long, accountIds: List<Long>) {
        dao.deleteTargetsExcept(scheduleId, accountIds)
        dao.insertTargets(accountIds.map { ScheduleAccountEntity(scheduleId = scheduleId, socialAccountId = it) })
    }

    suspend fun deleteSchedule(id: Long) {
        dao.deleteSchedule(id)
        ReminderAlarm.arm(app)
    }

    /** Jadwal dipindah ke waktu lain (pengingat dikirim lagi pada waktu yang baru). */
    suspend fun moveSchedule(id: Long, at: LocalDateTime) {
        val old = dao.schedule(id) ?: return
        val millis = Wall.millis(at.minute())
        if (millis == old.scheduledAt) return
        dao.updateSchedule(old.copy(scheduledAt = millis, notifiedAt = null, updatedAt = System.currentTimeMillis()))
        ReminderAlarm.arm(app)
    }

    /** Hasil Isi otomatis: pesan untuk pengguna dan tanggal jadwal pertama (untuk membuka minggunya). */
    data class FillOutcome(val banner: String, val firstDate: java.time.LocalDate?)

    /** Isi otomatis: klip antrean dibagikan ke slot rutin akun yang masih kosong, 14 hari ke depan. */
    suspend fun autoFill(): FillOutcome {
        val now = Wall.now()
        val clips = db.clips().schedulable()
        val schedules = dao.schedules()
        val targets = dao.allTargets().groupBy { it.scheduleId }
        val scheduled = schedules.map { it.clipId }.toSet()
        val records = clips.map { clip ->
            ClipRecord(
                id = clip.id, seriesId = clip.clipSeriesId, partNumber = clip.partNumber,
                selected = clip.review == ReviewStatus.Selected,
                renderDone = clip.render == RenderStatus.Done && clip.outputPath != null,
                fileExists = clip.render == RenderStatus.Done && outputExists(clip),
                renderedAt = clip.renderedAt?.let(Wall::of),
                scheduled = clip.id in scheduled,
            )
        }
        val accounts = dao.accounts().map { SlotAccount(it.id, SlotCodec.decode(it.slots)) }.filter { it.slots.isNotEmpty() }
        val result = QueueFiller.plan(
            records,
            schedules.map { s -> ScheduleRecord(s.clipId, Wall.of(s.scheduledAt), targets[s.id].orEmpty().map { it.socialAccountId }) },
            accounts,
            now,
        )
        db.withTransaction {
            for (plan in result.scheduled) {
                val id = dao.insertSchedule(ScheduleEntity(clipId = plan.clipId, scheduledAt = Wall.millis(plan.at)))
                syncTargets(id, plan.accountIds)
            }
        }
        if (result.scheduled.isNotEmpty()) ReminderAlarm.arm(app)
        return FillOutcome(QueueFiller.banner(result), result.scheduled.firstOrNull()?.at?.toLocalDate())
    }

    /**
     * Jadwal terlewat dijadwalkan ulang ke slot kosong berikutnya dari akun yang masih menunggu (utamanya slot milik
     * semua akun itu; tanpa slot: besok di jam yang sama). Akun yang sudah terbit tetap di jadwal lama; yang menunggu
     * dipindah ke jadwal baru. Mengembalikan id jadwal (yang baru bila dipisah) dan pesannya.
     */
    suspend fun reschedule(id: Long): Pair<Long, String>? {
        val schedule = dao.schedule(id) ?: return null
        val targets = dao.targets(id)
        val pending = targets.filter { it.status == TargetStatus.PENDING }
        if (pending.isEmpty()) return null
        val now = Wall.now()
        val accounts = dao.accounts().associateBy { it.id }
        val pendingAccounts = pending.mapNotNull { accounts[it.socialAccountId] }.map { SlotAccount(it.id, SlotCodec.decode(it.slots)) }
        val until = QueueFiller.until(now)
        val allTargets = dao.allTargets().groupBy { it.scheduleId }
        val taken = dao.schedules().map { it to Wall.of(it.scheduledAt) }
            .filter { (_, at) -> !at.isBefore(now) && !at.isAfter(until) }
            .flatMap { (s, at) -> allTargets[s.id].orEmpty().map { it.socialAccountId to at.minute() } }
            .toSet()
        val at = Schedules.rescheduleAt(QueueFiller.openSlots(pendingAccounts, now, taken), pendingAccounts.size, Wall.of(schedule.scheduledAt), now)
        val millis = Wall.millis(at)
        val result = db.withTransaction {
            if (pending.size == targets.size) {
                dao.updateSchedule(schedule.copy(scheduledAt = millis, notifiedAt = null, updatedAt = System.currentTimeMillis()))
                id
            } else {
                val moved = dao.insertSchedule(schedule.copy(id = 0, scheduledAt = millis, notifiedAt = null, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
                dao.moveTargets(id, moved, pending.map { it.socialAccountId })
                moved
            }
        }
        ReminderAlarm.arm(app)
        val clip = db.clips().find(schedule.clipId)
        val title = clip?.let { ClipText.displayTitle(it.title, it.partNumber) } ?: "Klip"
        return result to "$title dijadwalkan ulang ke ${IdDate.weekdayDayMonthTime(at)}."
    }

    // --- Status terbit & statistik -------------------------------------------------------------------------------

    /** Status satu akun tujuan: terbit (dengan waktu terbit sekarang), dilewati, atau kembali menunggu. */
    suspend fun markTarget(rowId: Long, status: String) {
        val row = dao.target(rowId) ?: return
        val now = System.currentTimeMillis()
        dao.updateTarget(row.copy(status = status, publishedAt = if (status == TargetStatus.PUBLISHED) Wall.millis(Wall.now()) else null, updatedAt = now))
        ReminderAlarm.arm(app)
        if (status == TargetStatus.PUBLISHED) refreshStats(rowId)
    }

    /**
     * Link postingan satu akun tujuan. Link di akun yang masih menunggu menandainya terbit, lalu statistiknya diambil.
     * Mengembalikan pesan kesalahan bila link tidak sah.
     */
    suspend fun saveLink(rowId: Long, url: String): String? {
        val link = url.trim()
        if (link.isNotEmpty() && !validUrl(link)) return "Link tidak valid."
        val row = dao.target(rowId) ?: return null
        if (row.url == link.ifEmpty { null }) return null
        val publish = link.isNotEmpty() && row.status == TargetStatus.PENDING
        dao.updateTarget(
            row.copy(
                url = link.ifEmpty { null }, statsError = null,
                status = if (publish) TargetStatus.PUBLISHED else row.status,
                publishedAt = if (publish) Wall.millis(Wall.now()) else row.publishedAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (publish) ReminderAlarm.arm(app)
        refreshStats(rowId)
        return null
    }

    /** Ambil statistik satu postingan sekarang (di latar). Mengembalikan false bila belum terbit atau belum punya link. */
    suspend fun refreshStats(rowId: Long): Boolean {
        val row = dao.target(rowId) ?: return false
        if (row.url == null || row.status != TargetStatus.PUBLISHED) return false
        StatsQueue.fetch(app, listOf(row.id))
        return true
    }

    /** Ambil statistik semua postingan terbit yang punya link; mengembalikan jumlahnya. */
    suspend fun refreshAllStats(): List<Long> {
        val rows = dao.publishedWithLink()
        if (rows.isNotEmpty()) StatsQueue.fetch(app, rows)
        return rows
    }

    /**
     * Statistik diisi manual (misalnya Instagram): disimpan sebagai catatan bertanggal sekarang; akun yang masih
     * menunggu dianggap sudah terbit.
     */
    suspend fun saveManualStats(rowId: Long, numbers: StatNumbers) {
        val row = dao.target(rowId) ?: return
        val now = Wall.millis(Wall.now())
        dao.insertStat(PostStatEntity(scheduleAccountId = row.id, views = numbers.views, likes = numbers.likes, comments = numbers.comments, shares = numbers.shares, source = StatSource.MANUAL, recordedAt = now))
        if (row.status == TargetStatus.PENDING) {
            dao.updateTarget(row.copy(status = TargetStatus.PUBLISHED, publishedAt = now, updatedAt = System.currentTimeMillis()))
            ReminderAlarm.arm(app)
        }
    }

    /** File hasil render masih ada di galeri. */
    suspend fun outputExists(clip: ClipEntity): Boolean = withContext(Dispatchers.IO) {
        val uri = clip.outputPath?.let(Uri::parse) ?: return@withContext false
        runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    companion object {
        /** Mirip FILTER_VALIDATE_URL PHP: skema dan host wajib ada. */
        fun validUrl(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme != null && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && ' ' !in url
        }.getOrDefault(false)
    }
}
