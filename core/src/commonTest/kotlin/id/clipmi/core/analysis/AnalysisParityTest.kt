package id.clipmi.core.analysis

import id.clipmi.core.testing.Fixtures
import id.clipmi.core.transcript.Word
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import id.clipmi.core.testing.assertEquals
import kotlin.test.Test

/**
 * PromptBuilder dan ClipImporter dikunci dengan output ClipMI desktop (tools/export-analysis-fixtures.php, kode
 * desktop dijalankan apa adanya dengan SQLite di memori).
 */
class AnalysisParityTest {


    private fun obj(name: String) = Json.parseToJsonElement(Fixtures.text("analysis/$name")).jsonObject

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test
    fun defaultTemplateIsIdentical() {
        assertEquals(obj("prompts.json").string("template"), PromptBuilder.DEFAULT_TEMPLATE)
    }

    @Test
    fun promptsAreIdentical() {
        val data = obj("prompts.json")
        val project = data.getValue("project").jsonObject
        val segments = data.getValue("segments").jsonArray.map {
            val s = it.jsonObject
            PromptSegment(s.getValue("sequence").jsonPrimitive.int, s.getValue("start_time").jsonPrimitive.double, s.getValue("end_time").jsonPrimitive.double, s.string("text")!!)
        }
        val settings = data.getValue("settings").jsonObject
        val expected = data.getValue("prompts").jsonObject

        for ((name, value) in expected) {
            val prompts = value.jsonArray.map { it.jsonPrimitive.content }
            val actual = if (name == "untimed") {
                PromptBuilder.build("Tanpa durasi", null, segments.take(5))
            } else {
                val (template, maxTokens) = settings.getValue(name).jsonArray.let { (it[0] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content to it[1].jsonPrimitive.intOrNull }
                PromptBuilder.build(project.string("title")!!, project.getValue("duration_seconds").jsonPrimitive.double, segments, template, maxTokens)
            }
            assertEquals("$name: jumlah bagian", prompts.size, actual.size)
            prompts.forEachIndexed { i, prompt -> assertEquals("$name bagian ${i + 1}", prompt, actual[i]) }
        }
    }

    /** Balasan dibaca berurutan ke project yang sama, seperti menempel beberapa bagian; klip yang sama dilewati. */
    @Test
    fun importIsIdentical() {
        val prompts = obj("prompts.json")
        val wordsSerializer = ListSerializer(Word.serializer())
        val segments = prompts.getValue("segments").jsonArray.map {
            val s = it.jsonObject
            ImportSegment(
                s.getValue("sequence").jsonPrimitive.int,
                s.getValue("start_time").jsonPrimitive.double,
                s.getValue("end_time").jsonPrimitive.double,
                (s["words"] as? JsonArray)?.let { w -> Json.decodeFromJsonElement(wordsSerializer, w) },
            )
        }
        val data = obj("import.json")
        val replies = data.getValue("replies").jsonObject
        val results = data.getValue("results").jsonObject

        val clips = mutableListOf<ClipDraft>()
        for ((name, reply) in replies) {
            val expected = results.getValue(name).jsonObject
            val parsed = runCatching { ClipImporter.parse(reply.jsonPrimitive.content, segments) }
            expected.string("exception")?.let {
                assertEquals("$name: pesan", it, parsed.exceptionOrNull()?.message)
                continue
            }
            val result = parsed.getOrThrow()
            var created = 0
            var skipped = 0
            for (draft in result.drafts) {
                val exists = clips.any {
                    it.startTime in draft.startTime - ClipImporter.DUPLICATE_TOLERANCE..draft.startTime + ClipImporter.DUPLICATE_TOLERANCE &&
                        it.endTime in draft.endTime - ClipImporter.DUPLICATE_TOLERANCE..draft.endTime + ClipImporter.DUPLICATE_TOLERANCE
                }
                if (exists) skipped++ else { clips += draft; created++ }
            }
            assertEquals("$name: dibuat", expected.getValue("created").jsonPrimitive.int, created)
            assertEquals("$name: dilewati", expected.getValue("skipped").jsonPrimitive.int, skipped)
            assertEquals("$name: error", expected.getValue("errors").jsonArray.map { it.jsonPrimitive.content }, result.errors)
        }

        val expectedClips = data.getValue("clips").jsonArray.map { it.jsonObject }
        assertEquals("jumlah klip", expectedClips.size, clips.size)
        expectedClips.zip(clips).forEachIndexed { i, (e, a) ->
            val label = "klip ${i + 1}"
            assertEquals("$label title", e.string("title"), a.title)
            assertEquals("$label start", e.getValue("start_time").jsonPrimitive.double, a.startTime, 0.0)
            assertEquals("$label end", e.getValue("end_time").jsonPrimitive.double, a.endTime, 0.0)
            assertEquals("$label score", e.getValue("score").jsonPrimitive.intOrNull, a.score)
            assertEquals("$label hook", e.string("hook"), a.hook)
            assertEquals("$label caption", e.string("caption"), a.caption)
            assertEquals("$label reason", e.string("reason"), a.reason)
            assertEquals("$label hashtags", (e["hashtags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList<String>(), a.hashtags)
            assertEquals("$label keywords", (e["keywords"] as? JsonArray)?.map { it.jsonPrimitive.content }, a.keywords)
            assertEquals("$label series", e.string("series"), a.series)
            assertEquals("$label part", e.getValue("part_number").jsonPrimitive.intOrNull, a.partNumber)
            assertEquals("$label displayTitle", e.string("display_title"), ClipText.displayTitle(a.title, a.partNumber))
            assertEquals("$label shareText", e.string("share_text"), ClipText.shareText(a.title, a.partNumber, a.caption, a.hashtags))
        }
    }
}
