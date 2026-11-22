package id.clipmi.core.render

import id.clipmi.core.testing.Fixtures
import id.clipmi.core.transcript.Word
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.fail
import kotlin.test.Test

/** Editor klip dikunci dengan urutan operasi `ClipEditor` desktop (tools/export-editor-fixtures.php). */
class EditorParityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val data = Json.parseToJsonElement(Fixtures.text("render/editor.json")).jsonObject

    private fun JsonElement?.isNull() = this == null || this is JsonNull

    private fun ranges(element: JsonElement?): List<Range> = element.takeUnless { it.isNull() }?.jsonArray?.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }.orEmpty()

    private fun state(o: JsonObject) = EditableClip(
        startTime = o.getValue("start_time").jsonPrimitive.double,
        endTime = o.getValue("end_time").jsonPrimitive.double,
        cuts = CutSet.from(o["cuts"] as? JsonObject),
        subtitleLines = o["subtitle_lines"].takeUnless { it.isNull() }?.let { json.decodeFromJsonElement(SubtitleEdits.serializer(), it) },
        subtitleEdited = o.getValue("subtitle_edited").jsonPrimitive.boolean,
    )

    private fun stateJson(c: EditableClip) = buildJsonObject {
        put("start_time", c.startTime)
        put("end_time", c.endTime)
        put("cuts", c.cuts.toJson() ?: JsonNull)
        put("subtitle_lines", c.subtitleLines?.let { json.encodeToJsonElement(SubtitleEdits.serializer(), it) } ?: JsonNull)
        put("subtitle_edited", c.subtitleEdited)
    }

    private fun assertJson(path: String, expected: JsonElement?, actual: JsonElement?) {
        when {
            expected.isNull() || actual.isNull() -> if (!(expected.isNull() && actual.isNull())) fail("$path: diharapkan $expected, didapat $actual")
            expected is JsonObject -> {
                val a = actual as? JsonObject ?: return fail("$path: bukan objek: $actual")
                for (key in expected.keys + a.keys) assertJson("$path.$key", expected[key], a[key])
            }
            expected is JsonArray -> {
                val a = actual as? JsonArray ?: return fail("$path: bukan array: $actual")
                assertEquals("$path: panjang", expected.size, a.size)
                expected.indices.forEach { assertJson("$path[$it]", expected[it], a[it]) }
            }
            else -> {
                val e = expected as JsonPrimitive
                val a = actual as JsonPrimitive
                if (!e.isString && e.booleanOrNull == null && e.doubleOrNull != null) assertEquals(path, e.double, a.doubleOrNull ?: Double.NaN, 0.0)
                else assertEquals(path, e.content, a.content)
            }
        }
    }

    @Test
    fun editorOperationsAreIdentical() {
        val segments = data.getValue("segments").jsonArray.map {
            val s = it.jsonObject
            SegmentWords(
                s.getValue("start_time").jsonPrimitive.double,
                s.getValue("end_time").jsonPrimitive.double,
                s["words"].takeUnless { w -> w.isNull() }?.let { w -> json.decodeFromJsonElement(ListSerializer(Word.serializer()), w) },
            )
        }
        val style = json.decodeFromJsonElement(CaptionStyle.serializer(), data.getValue("style"))
        val editor = ClipEditor(segments, style, data["projectDuration"].takeUnless { it.isNull() }?.jsonPrimitive?.double)

        for ((s, element) in data.getValue("sequences").jsonArray.withIndex()) {
            val sequence = element.jsonObject
            val silences = ranges(sequence["silences"])
            var clip = state(sequence.getValue("initial").jsonObject)

            for ((k, stepElement) in sequence.getValue("steps").jsonArray.withIndex()) {
                val step = stepElement.jsonObject
                val op = step.getValue("op").jsonPrimitive.content
                val args = step.getValue("args").jsonArray
                val name = "urutan $s langkah $k ($op $args)"
                val expectedResult = step["result"]
                fun arg(i: Int) = args[i].jsonPrimitive.double
                fun accepted(next: EditableClip?): EditableClip {
                    expectedResult.takeUnless { it.isNull() }?.jsonPrimitive?.boolean?.let { assertEquals("$name: diterima", it, next != null) }
                    return next ?: clip
                }

                clip = when (op) {
                    "nudge" -> editor.nudge(clip, args[0].jsonPrimitive.content, arg(1))
                    "snap" -> editor.snapToWords(clip)
                    "extend" -> editor.extend(clip, args[0].jsonPrimitive.content)
                    "range" -> editor.setRange(clip, arg(0), arg(1))
                    "cut" -> accepted(editor.cut(clip, arg(0), arg(1)))
                    "restore" -> accepted(editor.restore(clip, arg(0), arg(1)))
                    "hide" -> editor.hide(clip, arg(0), arg(1)) ?: clip
                    "show" -> editor.show(clip, arg(0), arg(1)) ?: clip
                    "edit" -> when (val result = editor.editWords(clip, arg(0), arg(1), args[2].jsonPrimitive.content)) {
                        is ClipEditor.EditResult.Done -> {
                            assertEquals("$name: pesan", null, expectedResult.takeUnless { it.isNull() }?.jsonPrimitive?.content)
                            result.clip
                        }
                        is ClipEditor.EditResult.Rejected -> {
                            assertEquals("$name: pesan", expectedResult?.jsonPrimitive?.content, result.message)
                            clip
                        }
                    }
                    "pause" -> accepted(editor.togglePause(clip, editor.pauses(clip, silences)[args[0].jsonPrimitive.int]))
                    "clean" -> accepted(editor.cleanPauses(clip, silences, arg(0)))
                    else -> fail("operasi tidak dikenal: $op").let { clip }
                }

                assertJson("$name keadaan", step.getValue("state"), stateJson(clip))
                assertJson(
                    "$name kata",
                    step.getValue("words"),
                    JsonArray(editor.words(clip).map { w -> buildJsonObject { put("text", w.text); put("start", w.start); put("end", w.end); put("cut", w.cut); put("hidden", w.hidden) } }),
                )
                assertJson(
                    "$name jeda",
                    step["pauses"],
                    JsonArray(editor.pauses(clip, silences).map { p ->
                        buildJsonObject {
                            put("after", p.after); put("start", p.start); put("end", p.end); put("length", p.length)
                            put("cut", JsonArray(listOf(JsonPrimitive(p.cut.first), JsonPrimitive(p.cut.second)))); put("removed", p.removed)
                        }
                    }),
                )
            }
        }
    }

    @Test
    fun silenceLogIsParsedLikeDesktop() {
        for (element in data.getValue("silenceLogs").jsonArray) {
            val case = element.jsonObject
            val actual = ClipSilence.parse(case.getValue("log").jsonPrimitive.content, case.getValue("start").jsonPrimitive.double, case.getValue("end").jsonPrimitive.double)
            assertEquals(ranges(case["result"]), actual)
        }
    }
}
