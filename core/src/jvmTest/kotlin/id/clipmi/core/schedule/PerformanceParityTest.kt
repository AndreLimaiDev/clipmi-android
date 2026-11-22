package id.clipmi.core.schedule

import id.clipmi.core.analysis.AnalysisResultException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Analisis Performa dikunci dengan keluaran kode desktop (tools/export-performance-fixtures.php). */
class PerformanceParityTest {
    private val cases = Json.parseToJsonElement(id.clipmi.core.testing.Fixtures.text("schedule/performance.json")).jsonArray
    private val seconds = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun JsonElement?.lng(): Long? = (this as? JsonPrimitive)?.longOrNull

    private fun row(o: JsonObject) = DigestPost(
        rowId = o.getValue("row_id").jsonPrimitive.long,
        scheduleId = o.getValue("schedule_id").jsonPrimitive.long,
        accountId = o.getValue("account_id").jsonPrimitive.long,
        account = o.getValue("account").jsonPrimitive.content,
        platform = o.getValue("platform").jsonPrimitive.content,
        publishedAt = LocalDateTime.parse(o.getValue("published_at").jsonPrimitive.content, seconds),
        stats = o.getValue("stats").jsonArray.map { it.jsonObject }.map {
            StatPoint(LocalDateTime.parse(it.getValue("at").jsonPrimitive.content, seconds), StatNumbers(it["views"].lng(), it["likes"].lng(), it["comments"].lng(), it["shares"].lng()))
        },
        title = o.getValue("title").jsonPrimitive.content,
        hook = (o["hook"] as? JsonPrimitive)?.contentOrNull,
        hookModel = (o["hook_model"] as? JsonPrimitive)?.contentOrNull,
        finalDuration = o.getValue("final_duration").jsonPrimitive.double,
        lead = (o["lead"] as? JsonPrimitive)?.contentOrNull,
        series = o.getValue("series").jsonPrimitive.boolean,
        keywords = o.getValue("keywords").jsonArray.map { it.jsonPrimitive.content },
        project = o.getValue("project").jsonPrimitive.content,
        caption = o.getValue("caption").jsonPrimitive.content,
        hashtags = o.getValue("hashtags").jsonPrimitive.int,
    )

    /** JSON sama: angka dibandingkan nilainya (PHP menulis 2.0, Kotlin bisa 2). */
    private fun same(path: String, expected: JsonElement, actual: JsonElement) {
        when (expected) {
            is JsonObject -> {
                val a = actual as? JsonObject ?: fail("$path: objek diharapkan, dapat $actual").let { return }
                assertEquals("$path: kunci", expected.keys.toList(), a.keys.toList())
                expected.forEach { (k, v) -> same("$path.$k", v, a.getValue(k)) }
            }
            is JsonArray -> {
                // Objek PHP kosong dan larik kosong sama-sama "[]".
                if (expected.isEmpty() && actual is JsonObject && actual.isEmpty()) return
                val a = actual as? JsonArray ?: fail("$path: larik diharapkan, dapat $actual").let { return }
                assertEquals("$path: panjang", expected.size, a.size)
                expected.forEachIndexed { i, v -> same("$path[$i]", v, a[i]) }
            }
            is JsonNull -> assertEquals(path, JsonNull, actual)
            is JsonPrimitive -> {
                val a = actual as? JsonPrimitive ?: fail("$path: nilai diharapkan, dapat $actual").let { return }
                if (expected.isString) assertEquals(path, expected.content, a.content)
                else if (expected.doubleOrNull != null) assertEquals(path, expected.double, a.doubleOrNull ?: Double.NaN, 0.0)
                else assertEquals(path, expected.content, a.content)
            }
        }
    }

    @Test
    fun digestPromptAndParseMatchDesktop() {
        var posts = 0
        var parsed = 0
        cases.forEachIndexed { i, case ->
            val o = case.jsonObject
            val now = LocalDateTime.parse(o.getValue("now").jsonPrimitive.content, seconds)
            val digest = PerformanceDigest.build(o.getValue("rows").jsonArray.map { row(it.jsonObject) }, now)
            val expected = o.getValue("digest").jsonObject
            same("digest#$i", expected, digest)
            posts += PerformanceDigest.postCount(digest)
            assertEquals("thin#$i", o.getValue("thin").jsonPrimitive.int, PerformanceDigest.thinAccounts(digest).size)
            (o["prompt"] as? JsonPrimitive)?.contentOrNull?.let { assertEquals("prompt#$i", it, PerformancePrompt.build(digest)) }

            o.getValue("parse").jsonArray.forEachIndexed { j, p ->
                val reply = p.jsonObject.getValue("reply").jsonPrimitive.content
                val error = (p.jsonObject["error"] as? JsonPrimitive)?.contentOrNull
                try {
                    val result = PerformancePrompt.parse(reply, digest)
                    if (error != null) fail("parse#$i/$j: harusnya gagal \"$error\"")
                    same("parse#$i/$j", p.jsonObject.getValue("result"), result)
                } catch (e: AnalysisResultException) {
                    assertEquals("parse#$i/$j", error, e.message)
                }
                parsed++
            }
        }
        println("Analisis performa: ${cases.size} kasus, $posts postingan, $parsed balasan")
    }
}
