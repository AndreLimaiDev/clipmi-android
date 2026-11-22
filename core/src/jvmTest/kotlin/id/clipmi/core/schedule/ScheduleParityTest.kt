package id.clipmi.core.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Jadwal & Statistik dikunci dengan keluaran kode desktop (tools/export-schedule-fixtures.php). */
class ScheduleParityTest {
    private fun fixture(name: String): JsonElement =
        Json.parseToJsonElement(id.clipmi.core.testing.Fixtures.text("schedule/$name.json"))

    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun time(value: String): LocalDateTime = when (value.length) {
        16 -> LocalDateTime.parse(value, minute)
        19 -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        else -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))
    }

    private fun JsonElement?.isNull() = this == null || this is JsonNull
    private fun JsonElement?.str(): String? = if (isNull()) null else this!!.jsonPrimitive.contentOrNull
    private fun JsonElement?.lng(): Long? = if (isNull()) null else this!!.jsonPrimitive.longOrNull

    private fun slots(element: JsonElement?): List<Slot> = if (element.isNull()) emptyList() else element!!.jsonArray.map { slot ->
        Slot(slot.jsonObject.getValue("time").jsonPrimitive.content, slot.jsonObject.getValue("days").jsonArray.map { it.jsonPrimitive.int })
    }

    private fun accounts(o: JsonObject) = o.getValue("accounts").jsonArray.map {
        SlotAccount(it.jsonObject.getValue("id").jsonPrimitive.long, slots(it.jsonObject["slots"]))
    }

    @Test
    fun slotsMatchDesktop() {
        val cases = fixture("slots").jsonArray
        cases.forEachIndexed { i, case ->
            val o = case.jsonObject
            val normalized = Slots.normalize(slots(o["raw"]))
            val expected = if (o["slots"].isNull()) null else slots(o["slots"])
            assertEquals("normalize #$i", expected, normalized)
            assertEquals("summary #$i", o.getValue("summary").jsonPrimitive.content, Slots.summary(normalized.orEmpty()))
            val times = Slots.times(normalized.orEmpty(), time(o.getValue("after").jsonPrimitive.content), time(o.getValue("until").jsonPrimitive.content))
            assertEquals("times #$i", o.getValue("times").jsonArray.map { it.jsonPrimitive.content }, times.map { it.format(minute) })
        }
        println("Slot: ${cases.size} kasus")
    }

    @Test
    fun autoFillMatchesDesktop() {
        val cases = fixture("fill").jsonArray
        var planned = 0
        cases.forEachIndexed { i, case ->
            val o = case.jsonObject
            val now = time(o.getValue("now").jsonPrimitive.content)
            val schedules = o.getValue("schedules").jsonArray.map {
                val s = it.jsonObject
                ScheduleRecord(s.getValue("clip").jsonPrimitive.long, time(s.getValue("at").jsonPrimitive.content), s.getValue("accounts").jsonArray.map { a -> a.jsonPrimitive.long })
            }
            val scheduledClips = schedules.map { it.clipId }.toSet()
            val clips = o.getValue("clips").jsonArray.map {
                val c = it.jsonObject
                val id = c.getValue("id").jsonPrimitive.long
                ClipRecord(
                    id = id,
                    seriesId = c["series"].lng(),
                    partNumber = c["part"].lng()?.toInt(),
                    selected = c.getValue("selected").jsonPrimitive.boolean,
                    renderDone = c.getValue("done").jsonPrimitive.boolean,
                    fileExists = c.getValue("file").jsonPrimitive.boolean,
                    renderedAt = c["rendered_at"].str()?.let(::time),
                    scheduled = id in scheduledClips,
                )
            }
            val result = QueueFiller.plan(clips, schedules, accounts(o).filter { it.slots.isNotEmpty() }, now)
            val expected = o.getValue("planned").jsonArray.map {
                val p = it.jsonObject
                Triple(p.getValue("clip").jsonPrimitive.long, p.getValue("at").jsonPrimitive.content, p.getValue("accounts").jsonArray.map { a -> a.jsonPrimitive.long })
            }
            assertEquals("plan #$i", expected, result.scheduled.map { Triple(it.clipId, it.at.format(minute), it.accountIds) })
            assertEquals("left #$i", o.getValue("left").jsonPrimitive.int, result.left)
            assertEquals("blocked #$i", o.getValue("blocked").jsonPrimitive.int, result.blocked)
            assertEquals("banner #$i", o.getValue("banner").jsonPrimitive.content, QueueFiller.banner(result))
            planned += expected.size
        }
        println("Isi otomatis: ${cases.size} kasus, $planned jadwal")
    }

    @Test
    fun rescheduleMatchesDesktop() {
        val cases = fixture("reschedule").jsonArray
        cases.forEachIndexed { i, case ->
            val o = case.jsonObject
            val now = time(o.getValue("now").jsonPrimitive.content)
            val statuses = o.getValue("statuses").jsonObject.mapKeys { it.key.toLong() }.mapValues { it.value.jsonPrimitive.content }
            val pending = accounts(o).filter { statuses[it.id] == TargetStatus.PENDING }
            val taken = o.getValue("others").jsonArray.flatMap { other ->
                val at = time(other.jsonObject.getValue("at").jsonPrimitive.content)
                other.jsonObject.getValue("accounts").jsonArray.map { it.jsonPrimitive.long to at.minute() }
            }.filter { !it.second.isBefore(now) && !it.second.isAfter(QueueFiller.until(now)) }.toSet()
            val at = Schedules.rescheduleAt(QueueFiller.openSlots(pending, now, taken), pending.size, time(o.getValue("scheduled_at").jsonPrimitive.content), now)
            assertEquals("at #$i", time(o.getValue("at").jsonPrimitive.content), at)
            assertEquals("banner #$i", o.getValue("banner").jsonPrimitive.content, "Klip dijadwalkan ulang ke ${IdDate.weekdayDayMonthTime(at)}.")
        }
        println("Jadwal ulang: ${cases.size} kasus")
    }

    @Test
    fun remindersMatchDesktop() {
        val cases = fixture("reminders").jsonArray
        var notes = 0
        cases.forEachIndexed { i, case ->
            val o = case.jsonObject
            val now = time(o.getValue("now").jsonPrimitive.content)
            val due = o.getValue("schedules").jsonArray.map { it.jsonObject }
                .filter { s ->
                    val targets = s.getValue("targets").jsonArray.map { it.jsonObject }
                    !s.getValue("notified").jsonPrimitive.boolean &&
                        !time(s.getValue("at").jsonPrimitive.content).isAfter(now) &&
                        targets.any { it.getValue("status").jsonPrimitive.content == TargetStatus.PENDING }
                }
                .map { s ->
                    DueSchedule(
                        s.getValue("id").jsonPrimitive.long,
                        time(s.getValue("at").jsonPrimitive.content),
                        s.getValue("title").jsonPrimitive.content,
                        s.getValue("targets").jsonArray.map { it.jsonObject }.filter { it.getValue("status").jsonPrimitive.content == TargetStatus.PENDING }
                            .map { it.getValue("name").jsonPrimitive.content },
                    )
                }
                .sortedBy { it.at }
            assertEquals("count #$i", o.getValue("count").jsonPrimitive.int, due.size)
            val expected = o.getValue("notes").jsonArray.map { n -> n.jsonArray.map { it.jsonPrimitive.content } }
            val actual = Reminders.notes(due, now).map { listOf(it.title, it.body, it.scheduleId?.let { id -> "schedule:$id" } ?: "schedule:due") }
            assertEquals("notes #$i", expected, actual)
            notes += expected.size
        }
        println("Pengingat: ${cases.size} kasus, $notes notifikasi")
    }

    @Test
    fun statsMatchDesktop() {
        val data = fixture("stats").jsonObject

        data.getValue("stat_at").jsonArray.forEachIndexed { i, case ->
            val o = case.jsonObject
            val published = o["published_at"].str()?.let(::time)
            val points = o.getValue("points").jsonArray.map { StatPoint(time(it.jsonPrimitive.content), StatNumbers(1, null, null, null)) }
            o.getValue("at").jsonObject.forEach { (days, expected) ->
                assertEquals("statAt #$i hari $days", expected.str()?.let(::time), PostStats.statAt(published, points, days.toInt())?.recordedAt)
            }
        }

        val due = data.getValue("due").jsonObject
        val now = time(due.getValue("now").jsonPrimitive.content)
        due.getValue("rows").jsonArray.forEachIndexed { i, row ->
            val o = row.jsonObject
            val published = time(o.getValue("published_at").jsonPrimitive.content)
            val eligible = o.getValue("status").jsonPrimitive.content == TargetStatus.PUBLISHED && o.getValue("url").jsonPrimitive.boolean &&
                !published.isBefore(PostStats.refreshFrom(now))
            val actual = eligible && PostStats.isDue(published, o["checked_at"].str()?.let(::time), now)
            assertEquals("due #$i", o.getValue("due").jsonPrimitive.boolean, actual)
        }

        data.getValue("summary").jsonArray.forEachIndexed { i, case ->
            val o = case.jsonObject
            val views = o.getValue("views").jsonArray.map { it.lng() }
            val summary = PostStats.summary(views)
            val s = o.getValue("summary").jsonObject
            assertEquals("summary #$i", AccountSummary(s.getValue("count").jsonPrimitive.int, s["total"].lng(), s["median"].lng(), s["best"].lng(), s.getValue("measured").jsonPrimitive.int), summary)
            assertEquals("ratio #$i", o.getValue("ratios").jsonArray.map { it.str() }, views.map { v -> PostStats.ratio(v, summary)?.let(PostStats::decimal) })
            assertEquals("number #$i", o.getValue("formatted").jsonArray.map { it.jsonPrimitive.content }, listOf(summary.total, summary.median, summary.best).plus(views).map(PostStats::number))
            val numbers = StatNumbers(views[0], o["likes"].lng(), o["comments"].lng(), o["shares"].lng())
            assertEquals("rate #$i", o.getValue("rate").jsonPrimitive.content, PostStats.rate(numbers.engagementRate))
        }

        data.getValue("parse").jsonArray.forEach { case ->
            val o = case.jsonObject
            val expected = (o["stats"] as? JsonObject)?.let { StatNumbers(it["views"].lng(), it["likes"].lng(), it["comments"].lng(), it["shares"].lng()) }
            assertEquals(o.getValue("output").jsonPrimitive.content, expected, PostStats.parseYtDlp(o.getValue("output").jsonPrimitive.content))
        }

        data.getValue("explain").jsonArray.forEach { case ->
            val o = case.jsonObject
            assertEquals(o.getValue("message").jsonPrimitive.content, PostStats.explain(o.getValue("url").jsonPrimitive.content, o.getValue("error").jsonPrimitive.content))
        }
        println("Statistik: ${data.getValue("stat_at").jsonArray.size} umur, ${due.getValue("rows").jsonArray.size} jadwal ambil, ${data.getValue("summary").jsonArray.size} ringkasan")
    }

    /** Kasus tes desktop ScheduleAutomationTest/PostStatsTest yang tidak tercakup fixture acak. */
    @Test
    fun desktopTestCases() {
        val slots = Slots.normalize(listOf(Slot("19:00", (1..7).toList()), Slot("07:00", listOf(6, 7)), Slot("19:00", listOf(1))))!!
        assertEquals(listOf(Slot("07:00", listOf(6, 7)), Slot("19:00", (1..7).toList())), slots)
        assertEquals("07.00 akhir pekan · 19.00 setiap hari", Slots.summary(slots))
        val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        val times = Slots.times(slots, now, now.plusDays(3).toLocalDate().atTime(23, 59, 59)).map { "${IdDate.WEEKDAYS_SHORT[it.dayOfWeek.value - 1]} ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" }
        assertEquals(listOf("Rab 19:00", "Kam 19:00", "Jum 19:00", "Sab 07:00", "Sab 19:00"), times)
        assertEquals(Slot("19:00", (1..7).toList()), Slots.next(emptyList()))
        assertEquals(Slot("20:00", (1..7).toList()), Slots.next(listOf(Slot("19:00", listOf(1)))))

        assertEquals(StatNumbers(1500, 20, null, 3), PostStats.parseYtDlp("[info] x\nclipmi-stats 1500|20|NA|3\n"))
        assertEquals(47.0 / 900, StatNumbers(900, 40, 5, 2).engagementRate!!, 0.0)
        assertEquals("10,0%", PostStats.rate(StatNumbers(3000, 270, 20, 10).engagementRate))
        assertEquals("3.000", PostStats.number(3000))

        val ok = Json.parseToJsonElement("""{"items":[{"statistics":{"viewCount":"1234","likeCount":"56","commentCount":"7"}}]}""").jsonObject
        assertEquals(StatNumbers(1234, 56, 7, null), PostStats.parseYouTubeApi(200, ok))
        val bad = Json.parseToJsonElement("""{"error":{"errors":[{"reason":"keyInvalid"}],"message":"Bad key"}}""").jsonObject
        assertEquals("API key YouTube ditolak. Periksa key di Pengaturan.", runCatching { PostStats.parseYouTubeApi(400, bad) }.exceptionOrNull()?.message)
        val quota = Json.parseToJsonElement("""{"error":{"errors":[{"reason":"quotaExceeded"}],"message":"x"}}""").jsonObject
        assertEquals("Kuota YouTube Data API hari ini habis. Coba lagi besok.", runCatching { PostStats.parseYouTubeApi(403, quota) }.exceptionOrNull()?.message)
        assertEquals("Video tidak ditemukan atau tidak publik.", runCatching { PostStats.parseYouTubeApi(200, Json.parseToJsonElement("""{"items":[]}""").jsonObject) }.exceptionOrNull()?.message)

        val counters = Schedules.counters(listOf("instagram", "facebook", "youtube"), "Sabar itu indah.", "#kajian #sabar", "Judul")
        assertEquals(
            listOf(
                LimitCounter("Instagram", listOf(Count("caption", 31, 2200), Count("hashtag", 2, 30))),
                LimitCounter("YouTube", listOf(Count("caption", 31, 5000), Count("judul", 5, 100))),
            ),
            counters,
        )
        assertEquals(ScheduleState.Partial, Schedules.state(listOf("published", "pending")))
        assertEquals(ScheduleState.Published, Schedules.state(listOf("published", "skipped")))
        assertEquals(ScheduleState.Scheduled, Schedules.state(emptyList()))
        assertEquals("#2563EB", Platforms.nextColor(listOf("#e1306c", "#64748B", "#DC2626")))
        assertEquals(JsonArray(emptyList()), JsonArray(emptyList()))
    }
}
