@file:OptIn(ExperimentalTime::class)

package id.clipmi.ios

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.PostStatEntity
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.StatsException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.data.ScheduleEntity
import id.clipmi.android.data.SocialAccountEntity
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.ClipRecord
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.QueueFiller
import id.clipmi.core.schedule.ScheduleRecord
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.Slot
import id.clipmi.core.schedule.SlotAccount
import id.clipmi.core.schedule.Slots
import id.clipmi.core.schedule.TargetStatus
import id.clipmi.core.schedule.minute
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Jam dinding HP: waktu jadwal disimpan sebagai epoch milidetik dan ditampilkan menurut zona waktu HP (sama dengan Android). */
object Wall {
    fun now(): LocalDateTime = of(nowMillis()).let { LocalDateTime(it.date, LocalTime(it.hour, it.minute, it.second)) }

    fun of(millis: Long): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

    fun millis(at: LocalDateTime): Long = at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    fun today(): LocalDate = now().date
}

@Serializable
private data class SlotJson(val time: String, val days: List<Int>)

/** Slot rutin akun dalam JSON yang sama dengan desktop dan Android: [{"time": "19:00", "days": [1, 2, ...]}]. */
object SlotCodec {
    private val serializer = ListSerializer(SlotJson.serializer())

    fun decode(json: String?): List<Slot> =
        json?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }?.map { Slot(it.time, it.days) }.orEmpty()

    fun encode(slots: List<Slot>?): String? =
        slots?.takeIf { it.isNotEmpty() }?.let { list -> Json.encodeToString(serializer, list.map { SlotJson(it.time, it.days) }) }
}

/**
 * Jadwal posting di iPhone, padanan `Scheduler` Android (logika yang sama, aturan jadwal dari core bersama): akun sosmed,
 * jadwal per klip ke banyak akun, Isi otomatis, jadwal ulang, dan status terbit per akun. Setiap perubahan memasang
 * ulang pengingat (notifikasi lokal iOS). Statistik dan pengambilan angka dari link menyusul.
 */
class IosScheduler(private val repo: Repository, private val engine: MediaEngine, private val youtubeKey: () -> String? = { null }) {
    private val db get() = repo.db
    private val dao get() = repo.db.schedules()
    private val strings = ListSerializer(String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeList(value: String?): List<String> = value?.let { runCatching { json.decodeFromString(strings, it) }.getOrNull() }.orEmpty()

    private fun encodeList(list: List<String>): String = json.encodeToString(strings, list)

    // --- Akun ---------------------------------------------------------------------------------------------------

    suspend fun saveAccount(id: Long, name: String, platform: String, handle: String?, color: String, slots: List<Slot>) {
        val now = nowMillis()
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
        armReminders()
    }

    // --- Jadwal -------------------------------------------------------------------------------------------------

    /**
     * Membuat atau menyimpan jadwal. Caption dan hashtag yang sama dengan milik klip disimpan kosong (tetap mengikuti
     * klip). Akun yang tetap dipilih mempertahankan status terbitnya; waktu yang berubah membuat pengingat dikirim lagi.
     */
    suspend fun saveSchedule(id: Long, clipId: Long, at: LocalDateTime, accountIds: List<Long>, caption: String, hashtags: List<String>, note: String): Long {
        val clip = db.clips().find(clipId) ?: error("Klip tidak ditemukan.")
        val now = nowMillis()
        val atMillis = Wall.millis(at.minute())
        val ownCaption = Schedules.ownCaption(caption, clip.caption)
        val ownHashtags = Schedules.ownHashtags(hashtags, decodeList(clip.hashtags))?.let(::encodeList)
        val scheduleId = db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
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
        }
        armReminders()
        return scheduleId
    }

    private suspend fun syncTargets(scheduleId: Long, accountIds: List<Long>) {
        dao.deleteTargetsExcept(scheduleId, accountIds)
        dao.insertTargets(accountIds.map { ScheduleAccountEntity(scheduleId = scheduleId, socialAccountId = it) })
    }

    suspend fun deleteSchedule(id: Long) {
        dao.deleteSchedule(id)
        armReminders()
    }

    /** Hasil Isi otomatis: pesan untuk pengguna dan tanggal jadwal pertama (untuk membuka minggunya). */
    data class FillOutcome(val banner: String, val firstDate: LocalDate?)

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
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                for (plan in result.scheduled) {
                    val id = dao.insertSchedule(ScheduleEntity(clipId = plan.clipId, scheduledAt = Wall.millis(plan.at)))
                    syncTargets(id, plan.accountIds)
                }
            }
        }
        if (result.scheduled.isNotEmpty()) armReminders()
        return FillOutcome(QueueFiller.banner(result), result.scheduled.firstOrNull()?.at?.date)
    }

    /**
     * Jadwal terlewat dijadwalkan ulang ke slot kosong berikutnya dari akun yang masih menunggu (tanpa slot: besok di jam
     * yang sama). Akun yang sudah terbit tetap di jadwal lama; yang menunggu dipindah ke jadwal baru.
     * @return id jadwal (yang baru bila dipisah) dan pesannya.
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
            .filter { (_, at) -> at >= now && at <= until }
            .flatMap { (s, at) -> allTargets[s.id].orEmpty().map { it.socialAccountId to at.minute() } }
            .toSet()
        val at = Schedules.rescheduleAt(QueueFiller.openSlots(pendingAccounts, now, taken), pendingAccounts.size, Wall.of(schedule.scheduledAt), now)
        val millis = Wall.millis(at)
        val result = db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (pending.size == targets.size) {
                    dao.updateSchedule(schedule.copy(scheduledAt = millis, notifiedAt = null, updatedAt = nowMillis()))
                    id
                } else {
                    val moved = dao.insertSchedule(schedule.copy(id = 0, scheduledAt = millis, notifiedAt = null, createdAt = nowMillis(), updatedAt = nowMillis()))
                    dao.moveTargets(id, moved, pending.map { it.socialAccountId })
                    moved
                }
            }
        }
        armReminders()
        val clip = db.clips().find(schedule.clipId)
        val title = clip?.let { ClipText.displayTitle(it.title, it.partNumber) } ?: "Klip"
        return result to "$title dijadwalkan ulang ke ${IdDate.weekdayDayMonthTime(at)}."
    }

    /** Status satu akun tujuan (terbit, dilewati, atau kembali menunggu). */
    suspend fun markTarget(rowId: Long, status: String) {
        val row = dao.target(rowId) ?: return
        dao.updateTarget(row.copy(status = status, publishedAt = if (status == TargetStatus.PUBLISHED) Wall.millis(Wall.now()) else null, updatedAt = nowMillis()))
        armReminders()
    }

    /** Link postingan satu akun tujuan; link di akun yang masih menunggu menandainya terbit. @return pesan kesalahan. */
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
                updatedAt = nowMillis(),
            ),
        )
        if (publish) armReminders()
        return null
    }

    // --- Statistik ----------------------------------------------------------------------------------------------

    private val statsLock = Mutex()

    /**
     * Mengambil statistik postingan [rowIds] berurutan (padanan StatsWorker Android, lewat yt-dlp tertanam). Hasil
     * disimpan sebagai catatan bertanggal; kegagalan dicatat di baris akun tujuan supaya tampil di layar.
     */
    suspend fun fetchStats(rowIds: List<Long>) = statsLock.withLock {
        for (id in rowIds.distinct()) {
            val row = dao.target(id) ?: continue
            val url = row.url ?: continue
            val now = Wall.millis(Wall.now())
            // YouTube lewat YouTube Data API bila key diisi (resmi, tanpa login), selain itu yt-dlp (sama dengan Android).
            val videoId = PostStats.youtubeApiVideoId(url)
            val key = youtubeKey()
            if (videoId != null && key != null) {
                try {
                    val numbers = fromYouTubeApi(videoId, key)
                    dao.insertStat(PostStatEntity(scheduleAccountId = row.id, views = numbers.views, likes = numbers.likes, comments = numbers.comments, shares = numbers.shares, source = StatSource.YOUTUBE_API, recordedAt = now))
                    dao.setStatsChecked(row.id, now, null)
                } catch (e: StatsException) {
                    dao.setStatsChecked(row.id, now, e.message)
                }
                continue
            }
            val result = runCatching { json.parseToJsonElement(readStats(url)).jsonObject }.getOrNull()
            if (result == null || result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val error = result?.get("error")?.jsonPrimitive?.contentOrNull ?: "yt-dlp tidak memberi jawaban."
                dao.setStatsChecked(row.id, now, PostStats.explain(url, error))
                continue
            }
            val numbers = StatNumbers(
                result["views"]?.jsonPrimitive?.longOrNull, result["likes"]?.jsonPrimitive?.longOrNull,
                result["comments"]?.jsonPrimitive?.longOrNull, result["shares"]?.jsonPrimitive?.longOrNull,
            )
            if (numbers.isEmpty) {
                dao.setStatsChecked(row.id, now, PostStats.NO_NUMBERS)
                continue
            }
            dao.insertStat(PostStatEntity(scheduleAccountId = row.id, views = numbers.views, likes = numbers.likes, comments = numbers.comments, shares = numbers.shares, source = StatSource.YTDLP, recordedAt = now))
            dao.setStatsChecked(row.id, now, null)
        }
    }

    private suspend fun fromYouTubeApi(videoId: String, key: String): StatNumbers {
        // Key lewat header, bukan query, supaya tidak ikut tercatat di log URL.
        val response = engine.http("GET", "https://www.googleapis.com/youtube/v3/videos?part=statistics&id=$videoId", mapOf("X-Goog-Api-Key" to key), null, 20)
        if (response.error != null) throw StatsException("Tidak bisa terhubung ke YouTube. Periksa koneksi internet.")
        val body = runCatching { json.parseToJsonElement(response.body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        return PostStats.parseYouTubeApi(response.status, body)
    }

    private suspend fun readStats(url: String): String = suspendCancellableCoroutine { cont ->
        engine.fetchStats(url, repo.documents, object : ResultListener {
            override fun finished(resultJson: String) {
                if (cont.isActive) cont.resume(resultJson)
            }
        })
    }

    /**
     * Pembaruan berkala (padanan StatsRefresher): postingan terbit dengan link, berumur ≤ 31 hari, yang sudah waktunya
     * diambil lagi (3, 12, atau 48 jam menurut umurnya). iOS tidak menjalankannya di latar, jadi dipanggil saat tab
     * Statistik dibuka. @return jumlah postingan yang diambil.
     */
    suspend fun refreshDueStats(): Int {
        val now = Wall.now()
        val due = dao.refreshCandidates(Wall.millis(PostStats.refreshFrom(now)))
            .filter { row -> row.publishedAt?.let { PostStats.isDue(Wall.of(it), row.statsCheckedAt?.let(Wall::of), now) } == true }
            .map { it.id }
        if (due.isNotEmpty()) fetchStats(due)
        return due.size
    }

    /** Semua postingan terbit yang punya link diambil sekarang. @return jumlahnya. */
    suspend fun refreshAllStats(): Int {
        val rows = dao.publishedWithLink()
        if (rows.isNotEmpty()) fetchStats(rows)
        return rows.size
    }

    /** Statistik diisi manual (misalnya Instagram); akun yang masih menunggu dianggap sudah terbit. */
    suspend fun saveManualStats(rowId: Long, numbers: StatNumbers) {
        val row = dao.target(rowId) ?: return
        val now = Wall.millis(Wall.now())
        dao.insertStat(PostStatEntity(scheduleAccountId = row.id, views = numbers.views, likes = numbers.likes, comments = numbers.comments, shares = numbers.shares, source = StatSource.MANUAL, recordedAt = now))
        if (row.status == TargetStatus.PENDING) {
            dao.updateTarget(row.copy(status = TargetStatus.PUBLISHED, publishedAt = now, updatedAt = nowMillis()))
            armReminders()
        }
    }

    /** File hasil render masih ada (Documents/renders). */
    fun outputExists(clip: ClipEntity): Boolean = clip.outputPath?.takeIf { !it.contains("://") }?.let { fileSize(repo.absolute(it)) > 0 } ?: false

    /**
     * Pengingat "Waktunya posting" sebagai notifikasi lokal iOS: satu per jadwal yang akan datang dan masih punya akun
     * menunggu (paling banyak [REMINDER_LIMIT], batas iOS 64). Notifikasi tetap muncul walau aplikasi ditutup.
     */
    suspend fun armReminders() {
        val now = nowMillis()
        val accounts = dao.accounts().associateBy { it.id }
        val targets = dao.allTargets().groupBy { it.scheduleId }
        val upcoming = dao.schedules().filter { it.scheduledAt > now }.sortedBy { it.scheduledAt }.mapNotNull { s ->
            val pending = targets[s.id].orEmpty().filter { it.status == TargetStatus.PENDING }.mapNotNull { accounts[it.socialAccountId]?.name }
            if (pending.isEmpty()) null else s to pending
        }.take(REMINDER_LIMIT)
        val items = buildJsonArray {
            for ((s, pending) in upcoming) {
                val clip = db.clips().find(s.clipId)
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("at", s.scheduledAt)
                        put("title", "Waktunya posting")
                        put("body", "${clip?.let { ClipText.displayTitle(it.title, it.partNumber) } ?: "Klip"} ke ${pending.joinToString(", ")}")
                    },
                )
            }
        }
        engine.scheduleReminders(items.toString())
    }

    companion object {
        const val REMINDER_LIMIT = 60

        /** Mirip FILTER_VALIDATE_URL PHP: skema http/https dan host wajib ada. */
        fun validUrl(url: String): Boolean {
            if (' ' in url) return false
            val match = Regex("^(https?)://([^/?#:]+)(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE).find(url) ?: return false
            return match.groupValues[2].isNotEmpty()
        }
    }
}
