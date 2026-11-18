package id.clipmi.core.render

import id.clipmi.core.analysis.Keywords
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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Kode render dikunci dengan output ClipMI desktop (tools/export-render-fixtures.php: kode desktop dijalankan apa adanya
 * dengan SQLite di memori). Semua teks (filtergraph FFmpeg, ASS) harus identik byte demi byte.
 */
class RenderParityTest {
    private val root = File(javaClass.getResource("/fixtures/render/composition.json")!!.toURI()).parentFile
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private fun load(name: String) = Json.parseToJsonElement(File(root, "$name.json").readText()).jsonObject

    private fun JsonElement?.isNull() = this == null || this is JsonNull

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** Objek JSON tersimpan; `[]` (array PHP kosong) dan null sama-sama berarti "tidak ada". */
    private fun JsonElement?.storedObject(): JsonObject? = this as? JsonObject

    private fun frame(element: JsonElement) = json.decodeFromJsonElement(FrameStyle.serializer(), element)

    private fun style(element: JsonElement) = json.decodeFromJsonElement(CaptionStyle.serializer(), element)

    private fun lines(element: JsonElement?) = if (element.isNull()) emptyList() else json.decodeFromJsonElement(ListSerializer(SubtitleLine.serializer()), element!!)

    private fun ranges(list: List<Range>) = JsonArray(list.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })

    /** Bandingkan JSON: angka sebagai double (PHP menulis 1 untuk 1.0), objek tanpa memedulikan urutan kunci. */
    private fun assertJson(path: String, expected: JsonElement?, actual: JsonElement?) {
        when {
            expected.isNull() || actual.isNull() -> if (!(expected.isNull() && actual.isNull())) fail("$path: diharapkan $expected, didapat $actual")
            expected is JsonObject -> {
                actual as? JsonObject ?: fail("$path: bukan objek: $actual")
                actual as JsonObject
                val keys = expected.keys + actual.keys
                for (key in keys) assertJson("$path.$key", expected[key], actual[key])
            }
            expected is JsonArray -> {
                actual as? JsonArray ?: fail("$path: bukan array: $actual")
                actual as JsonArray
                assertEquals("$path: panjang", expected.size, actual.size)
                expected.indices.forEach { assertJson("$path[$it]", expected[it], actual[it]) }
            }
            else -> {
                val e = expected as JsonPrimitive
                val a = actual as JsonPrimitive
                if (!e.isString && e.booleanOrNull == null && e.doubleOrNull != null) {
                    assertEquals(path, e.double, a.doubleOrNull ?: fail("$path: bukan angka: $a").let { 0.0 }, 0.0)
                } else {
                    assertEquals(path, e.content, a.content)
                }
            }
        }
    }

    @Test
    fun compositionAndFilterGraphsAreIdentical() {
        val data = load("composition")
        for ((i, element) in data.getValue("cases").jsonArray.withIndex()) {
            val case = element.jsonObject
            val f = frame(case.getValue("frame"))
            val source = case["source"].takeUnless { it.isNull() }?.jsonArray?.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int }
            val composition = Composition.make(
                f, source,
                case.getValue("cropX").jsonPrimitive.int,
                case["zoom"].takeUnless { it.isNull() }?.jsonPrimitive?.double,
                case.getValue("cropY").jsonPrimitive.int,
            )
            assertJson("komposisi #$i", case.getValue("composition"), json.encodeToJsonElement(Composition.serializer(), composition))
            assertEquals("framesVideo #$i", case.getValue("framesVideo").jsonPrimitive.boolean, composition.framesVideo())
            assertEquals("ringkasan #$i", case.string("summary"), f.summary())

            val inputs = case.getValue("inputs").let { it as? JsonObject ?: JsonObject(emptyMap()) }
            val graph = RenderGraph.filter(
                composition,
                RenderGraph.Inputs(inputs["background"]?.jsonPrimitive?.intOrNull, inputs["template"]?.jsonPrimitive?.intOrNull, inputs["logo"]?.jsonPrimitive?.intOrNull),
                case["keep"].string(),
            )
            assertEquals("filtergraph #$i ${f.mode}", case.string("filter"), graph)
        }

        for (element in data.getValue("anchors").jsonArray) {
            val case = element.jsonObject
            val composition = Composition.make(FrameStyle(aspect = "4:5", mode = "crop", titleBar = true))
            val (x, y) = composition.anchorExpression(case.getValue("x").jsonPrimitive.double, case.getValue("y").jsonPrimitive.double, "W", "H", "w", "h")
            val expected = case.getValue("result").jsonArray
            assertEquals(expected[0].jsonPrimitive.content, x)
            assertEquals(expected[1].jsonPrimitive.content, y)
        }
    }

    private fun JsonObject.string(key: String) = this[key].string()

    /** Lebar teks dari ukuran yang diekspor desktop (TextMeasure/GD); gagal bila diminta teks yang tidak tercatat. */
    private fun measurerFor(name: String, measure: JsonObject?) = TextMeasurer { family, size, text ->
        if (measure == null) fail("$name: lebar teks diminta tanpa data ukuran")
        assertEquals("$name font", measure!!.string("family"), family)
        assertEquals("$name ukuran", measure.getValue("size").jsonPrimitive.int, size)
        measure.getValue("widths").jsonObject[text]?.jsonPrimitive?.double ?: run { fail("$name: tidak ada lebar untuk \"$text\""); 0.0 }
    }

    @Test
    fun assFilesAreIdentical() {
        val data = load("ass")
        for ((i, element) in data.getValue("cases").jsonArray.withIndex()) {
            val case = element.jsonObject
            val measurer = measurerFor("ASS #$i", case["measure"] as? JsonObject)
            val titleBar = case["titleBar"].takeUnless { it.isNull() }?.jsonObject?.let {
                TitleBarText(it.string("text")!!, it.string("background"), it.string("color"), it.getValue("height").jsonPrimitive.int)
            }
            val canvas = case.getValue("canvas").jsonArray.let { Triple(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, it[2].jsonPrimitive.double) }
            val hookLayout = case["hookLayout"].takeUnless { it.isNull() }?.let { json.decodeFromJsonElement(HookLayout.serializer(), it) }

            val actual = AssWriter(measurer).build(
                lines(case["lines"]),
                case.getValue("duration").jsonPrimitive.double,
                case.string("hook"),
                style(case.getValue("style")),
                case["centerY"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                titleBar,
                canvas,
                case["hookTop"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                case.getValue("hookSize").jsonPrimitive.int,
                case.getValue("keywords").jsonArray.map { it.jsonPrimitive.content },
                hookLayout,
            )
            assertEquals("ASS #$i", case.string("ass"), actual)
        }

        for (element in data.getValue("times").jsonArray) {
            val case = element.jsonObject
            assertEquals(case.string("ass"), AssWriter.time(case.getValue("seconds").jsonPrimitive.double))
        }
    }

    @Test
    fun hookLayoutIsIdentical() {
        val data = load("hooklayout")
        for ((i, element) in data.getValue("resolve").jsonArray.withIndex()) {
            val case = element.jsonObject
            val actual = HookLayout.resolve(case["stored"].storedObject(), case.getValue("length").jsonPrimitive.double, case.getValue("lead").jsonPrimitive.double)
            assertJson("hook #$i", case.getValue("result"), json.encodeToJsonElement(HookLayout.serializer(), actual))
        }
        for ((i, element) in data.getValue("merge").jsonArray.withIndex()) {
            val case = element.jsonObject
            val actual = HookLayout.merge(case["stored"].storedObject(), case.getValue("changes").jsonObject)
            assertJson("merge #$i", case.getValue("result").let { it as? JsonObject ?: JsonObject(emptyMap()) }, actual)
        }
    }

    @Test
    fun keywordsAreIdentical() {
        val data = load("keywords")
        for ((i, element) in data.getValue("mark").jsonArray.withIndex()) {
            val case = element.jsonObject
            val expected = (case["result"] as? JsonObject)?.keys?.map(String::toInt)?.toSet().orEmpty()
            val actual = Keywords.mark(case.getValue("texts").jsonArray.map { it.jsonPrimitive.content }, case.getValue("keywords").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("mark #$i", expected, actual)
        }
        for ((i, element) in data.getValue("toggle").jsonArray.withIndex()) {
            val case = element.jsonObject
            val actual = Keywords.toggle(case.getValue("keywords").jsonArray.map { it.jsonPrimitive.content }, case.string("phrase")!!)
            assertEquals("toggle #$i", case.getValue("result").jsonArray.map { it.jsonPrimitive.content }, actual)
        }
    }

    @Test
    fun clipCutsAreIdentical() {
        for ((i, element) in load("cuts").getValue("cases").jsonArray.withIndex()) {
            val case = element.jsonObject
            val start = case.getValue("start").jsonPrimitive.double
            val end = case.getValue("end").jsonPrimitive.double
            val cuts = ClipCuts.normalize(ClipCuts.parse(case.getValue("raw")), start, end)
            val kept = ClipCuts.kept(cuts, start, end)
            val from = case.getValue("from").jsonPrimitive.double
            val to = case.getValue("to").jsonPrimitive.double
            val probe = case.getValue("probe").jsonPrimitive.double
            assertJson("normalize #$i", case["normalized"], ranges(cuts))
            assertJson("kept #$i", case["kept"], ranges(kept))
            assertEquals("removed #$i", case.getValue("removed").jsonPrimitive.double, ClipCuts.removed(cuts), 0.0)
            assertJson("add #$i", case["added"], ranges(ClipCuts.add(cuts, from, to, start, end)))
            assertJson("subtract #$i", case["subtracted"], ranges(ClipCuts.subtract(cuts, from, to, start, end)))
            assertEquals("map #$i", case.getValue("mapped").jsonPrimitive.double, ClipCuts.map(cuts, start, probe), 0.0)
            assertEquals("covers #$i", case.getValue("covers").jsonPrimitive.boolean, ClipCuts.covers(cuts, probe))
            assertEquals("select #$i", case.string("select"), if (kept.isEmpty()) "" else ClipCuts.selectExpression(kept, start))
        }
    }

    @Test
    fun subtitlesAndPreviewTracksAreIdentical() {
        val data = load("subtitles")
        val segments = data.getValue("segments").jsonArray.map {
            val s = it.jsonObject
            SegmentWords(
                s.getValue("start_time").jsonPrimitive.double,
                s.getValue("end_time").jsonPrimitive.double,
                s["words"].takeUnless { w -> w.isNull() }?.let { w -> json.decodeFromJsonElement(ListSerializer(Word.serializer()), w) },
            )
        }
        for ((i, element) in data.getValue("clips").jsonArray.withIndex()) {
            val case = element.jsonObject
            val render = ClipRender(AssWriter(measurerFor("klip #$i", case["measure"] as? JsonObject)))
            val c = case.getValue("clip").jsonObject
            val edits = c["subtitle_lines"].takeUnless { it.isNull() }?.let { json.decodeFromJsonElement(SubtitleEdits.serializer(), it) }
            val clip = ClipSpec(
                startTime = c.getValue("start_time").jsonPrimitive.double,
                endTime = c.getValue("end_time").jsonPrimitive.double,
                title = c.string("title")!!,
                partNumber = c["part_number"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                hook = c.string("hook"),
                keywords = c["keywords"].takeUnless { it.isNull() }?.jsonArray?.map { it.jsonPrimitive.content },
                cuts = c["cuts"].storedObject(),
                showHookOverlay = c.getValue("show_hook_overlay").jsonPrimitive.boolean,
                hookLayout = c["hook_layout"].storedObject(),
                subtitleLines = edits,
                subtitleEdited = c.getValue("subtitle_edited").jsonPrimitive.boolean,
                cropZoom = c["crop_zoom"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                cropY = c["crop_y"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                cropPosition = c["crop_position"].takeUnless { it.isNull() }?.jsonPrimitive?.int,
                projectCropPosition = c.getValue("project_crop_position").jsonPrimitive.int,
                style = style(c.getValue("style")),
                frame = frame(c.getValue("frame")),
            )
            val lineList = { list: List<SubtitleLine> -> json.encodeToJsonElement(ListSerializer(SubtitleLine.serializer()), list) }

            assertJson("baris #$i", case["lines"], lineList(render.lines(clip, segments)))

            // Koreksi: diterapkan ke baris saat itu, dengan rentang klip saat dikoreksi.
            (case["edits"] as? JsonObject)?.let { texts ->
                val applied = SubtitleBuilder.applyEdits(lines(case["original"]), texts.map { (k, v) -> k.toInt() to v.jsonPrimitive.content }, edits!!.from, edits.to)
                assertJson("koreksi #$i", c["subtitle_lines"], json.encodeToJsonElement(SubtitleEdits.serializer(), applied))
            }

            assertJson("relatif #$i", case["relative"], lineList(render.relativeLines(clip, segments)))
            assertJson("potongan #$i", case["cutRanges"], ranges(clip.cutRanges()))
            assertJson("tanpa subtitle #$i", case["wordsOffSubtitle"], ranges(clip.wordsOffSubtitle()))
            assertEquals("durasi hasil #$i", case.getValue("outputDuration").jsonPrimitive.double, clip.outputDuration(), 0.0)
            assertJson("hook #$i", case["hookLayout"], json.encodeToJsonElement(HookLayout.serializer(), clip.hookLayout()))

            val composition = render.composition(clip)
            assertJson("komposisi #$i", case["composition"], json.encodeToJsonElement(Composition.serializer(), composition))
            val preview = render.previewTracks(clip, composition, segments)
            val expected = case.getValue("preview").jsonObject
            assertEquals("pratinjau subtitle #$i", expected.string("subs"), preview.subs)
            assertEquals("pratinjau hook #$i", expected.string("hook"), preview.hook)
        }

        for ((i, element) in data.getValue("editLine").jsonArray.withIndex()) {
            val case = element.jsonObject
            val line = json.decodeFromJsonElement(SubtitleLine.serializer(), case.getValue("line"))
            assertJson("editLine #$i", case["result"], json.encodeToJsonElement(SubtitleLine.serializer(), SubtitleBuilder.editLine(line, case.string("text")!!)))
        }
    }

    @Test
    fun bumperJoinOpenerAndAudioAreIdentical() {
        val data = load("renderer")
        assertEquals(data.string("audioFilter"), RenderGraph.AUDIO_FILTER)
        for ((i, element) in data.getValue("join").jsonArray.withIndex()) {
            val case = element.jsonObject
            val parts = case.getValue("parts").jsonObject.mapValues { (_, v) ->
                val p = v.jsonArray
                RenderGraph.BumperPart(
                    p[0].jsonPrimitive.int, p[1].jsonPrimitive.boolean, p[2].jsonPrimitive.double,
                    p.getOrNull(3)?.jsonPrimitive?.content, p.getOrNull(4)?.jsonPrimitive?.double,
                )
            }
            val actual = RenderGraph.joinBumpers(
                case.string("graph")!!, frame(case.getValue("frame")), parts, case.string("fps")!!,
                case.string("keep"), case.getValue("mainAudio").jsonPrimitive.boolean, case.getValue("length").jsonPrimitive.double,
            )
            assertEquals("join #$i", case.string("result"), actual)
        }
        for ((i, element) in data.getValue("opener").jsonArray.withIndex()) {
            val case = element.jsonObject
            val actual = RenderGraph.opener(
                case.getValue("input").jsonPrimitive.int, frame(case.getValue("frame")), case["center"].takeUnless { it.isNull() }?.jsonPrimitive?.double,
                case.getValue("cropPosition").jsonPrimitive.int, case.string("fps")!!, case.getValue("seconds").jsonPrimitive.double,
            )
            assertEquals("opener #$i", case.string("result"), actual)
        }
        for (element in data.getValue("audio").jsonArray) {
            val case = element.jsonObject
            assertEquals(case.getValue("result").jsonArray.map { it.jsonPrimitive.content }, RenderGraph.audioFilter(case.string("keep")))
        }
    }

    @Test
    fun lgplReplacesBoxblurOnly() {
        assertEquals(
            "[bg]scale=270:480,avgblur=sizeX=8,avgblur=sizeX=8,scale=1080:1920[back];boxblurX",
            RenderGraph.lgpl("[bg]scale=270:480,boxblur=8:2,scale=1080:1920[back];boxblurX"),
        )
    }
}
