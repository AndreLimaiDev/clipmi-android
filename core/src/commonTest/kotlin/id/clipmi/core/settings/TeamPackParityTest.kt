package id.clipmi.core.settings

import id.clipmi.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.fail
import kotlin.test.Test

/** Paket Standar Tim dikunci dengan `TeamPack::parse/preview` desktop (tools/export-teampack-fixtures.php). */
class TeamPackParityTest {
    private val data = Json.parseToJsonElement(Fixtures.text("settings/teampack.json")).jsonObject

    /** JSON sama: angka dibandingkan nilainya, boolean PHP dan angka 0/1 dibedakan seperti aslinya. */
    private fun same(path: String, expected: JsonElement, actual: JsonElement?) {
        when (expected) {
            is JsonObject -> {
                val a = actual as? JsonObject ?: return fail("$path: objek diharapkan, dapat $actual")
                assertEquals("$path: kunci", expected.keys.toList(), a.keys.toList())
                expected.forEach { (k, v) -> same("$path.$k", v, a[k]) }
            }
            is JsonArray -> {
                if (expected.isEmpty() && actual is JsonObject && actual.isEmpty()) return
                val a = actual as? JsonArray ?: return fail("$path: larik diharapkan, dapat $actual")
                assertEquals("$path: panjang", expected.size, a.size)
                expected.forEachIndexed { i, v -> same("$path[$i]", v, a[i]) }
            }
            is JsonNull -> assertEquals(path, JsonNull, actual)
            is JsonPrimitive -> {
                val a = actual as? JsonPrimitive ?: return fail("$path: nilai diharapkan, dapat $actual")
                when {
                    expected.isString -> assertEquals(path, expected.content, a.content)
                    expected.content == "true" || expected.content == "false" -> assertEquals(path, expected.content, a.content)
                    else -> assertEquals(path, expected.double, a.doubleOrNull ?: Double.NaN, 0.0)
                }
            }
        }
    }

    private fun file(f: PackFile?): JsonElement = f?.let { buildJsonObject { put("extension", it.extension); put("size", it.bytes().size) } } ?: JsonNull

    @Test
    fun parseAndPreviewMatchDesktop() {
        val existing = data.getValue("existing").jsonObject
        fun names(key: String) = existing.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        var entries = 0
        data.getValue("cases").jsonArray.forEachIndexed { i, case ->
            val o = case.jsonObject
            val json = o.getValue("json").jsonPrimitive.content
            val error = (o["error"] as? JsonPrimitive)?.contentOrNull
            val pack = try {
                TeamPack.parse(json).also { if (error != null) fail("#$i: harusnya gagal \"$error\"") }
            } catch (e: TeamPackException) {
                assertEquals("#$i", error, e.message)
                return@forEachIndexed
            }
            val r = o.getValue("result").jsonObject
            assertEquals("#$i prompt", (r["prompt_template"] as? JsonPrimitive)?.contentOrNull, pack.promptTemplate)
            assertEquals("#$i max", (r["prompt_max_tokens"] as? JsonPrimitive)?.longOrNull, pack.promptMaxTokens)
            same("#$i styles", r.getValue("caption_styles"), JsonArray(pack.captionStyles))
            same("#$i corrections", r.getValue("corrections"), JsonArray(pack.corrections))
            val frames = pack.frameStyles.mapIndexed { j, style -> JsonObject(style + pack.frameImages[j].mapValues { file(it.value) }) }
            same("#$i frames", r.getValue("frame_styles"), JsonArray(frames))
            same(
                "#$i bumpers", r.getValue("bumpers"),
                JsonArray(pack.bumpers.map { b -> buildJsonObject { put("kind", b.kind); put("name", b.name); put("duration", b.duration); put("video", file(b.video)) } }),
            )
            assertEquals("#$i skipped", r.getValue("skipped").jsonPrimitive.int, pack.skipped)
            val p = o.getValue("preview").jsonObject
            val preview = TeamPack.preview(pack, names("styles"), names("frames"), names("corrections"))
            assertEquals(
                "#$i preview",
                listOf("styles_new", "styles_updated", "frames_new", "frames_updated", "corrections_new", "corrections_updated", "bumpers").map { p.getValue(it).jsonPrimitive.int } +
                    listOf(if (p.getValue("prompt").jsonPrimitive.boolean) 1 else 0, p.getValue("skipped").jsonPrimitive.int),
                listOf(preview.stylesNew, preview.stylesUpdated, preview.framesNew, preview.framesUpdated, preview.correctionsNew, preview.correctionsUpdated, preview.bumpers, if (preview.prompt) 1 else 0, preview.skipped),
            )
            entries += pack.captionStyles.size + pack.frameStyles.size + pack.corrections.size + pack.bumpers.size + pack.skipped
        }
        println("Paket Standar Tim: ${data.getValue("cases").jsonArray.size} paket, $entries entri")
    }
}
