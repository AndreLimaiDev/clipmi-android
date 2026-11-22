package id.clipmi.core.render

import id.clipmi.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import id.clipmi.core.testing.assertEquals
import kotlin.test.Test

/** Jalur "ikuti penceramah" dikunci dengan `SpeakerTrack` dan `ClipRenderer::filter` desktop (tools/export-speaker-fixtures.php). */
class SpeakerTrackParityTest {
    private val data = Json.parseToJsonElement(Fixtures.text("render/speaker.json")).jsonObject

    private fun keyframes(element: JsonElement): List<List<Double>> = element.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }

    @Test
    fun buildPositionAndExpressionMatchDesktop() {
        var points = 0
        data.getValue("builds").jsonArray.forEachIndexed { i, element ->
            val o = element.jsonObject
            val samples = o.getValue("samples").jsonArray.map { (it as? JsonPrimitive)?.doubleOrNull }
            val built = SpeakerTrack.build(
                samples, o.getValue("interval").jsonPrimitive.double, o.getValue("duration").jsonPrimitive.double,
                o.getValue("cuts").jsonArray.map { it.jsonPrimitive.double },
            )
            assertEquals("build #$i", keyframes(o.getValue("keyframes")), built)
            points += built.size
            o.getValue("positions").jsonArray.forEach { p ->
                val (t, expected) = p.jsonArray.map { it.jsonPrimitive.double }
                assertEquals("positionAt #$i t=$t", expected, SpeakerTrack.positionAt(built, t), 1e-12)
            }
            (o["expression"] as? JsonPrimitive)?.contentOrNull?.let { assertEquals("expression #$i", it, SpeakerTrack.expression(built)) }
        }
        println("Ikuti penceramah: ${data.getValue("builds").jsonArray.size} jalur, $points keyframe")
    }

    @Test
    fun filterGraphsWithKeyframesMatchDesktop() {
        data.getValue("filters").jsonArray.forEachIndexed { i, element ->
            val o = element.jsonObject
            val f = o.getValue("frame").jsonObject
            fun text(key: String) = (f[key] as? JsonPrimitive)?.contentOrNull
            fun number(key: String) = (f[key] as? JsonPrimitive)?.intOrNull
            val area = f["template_area"] as? JsonObject
            val frame = FrameStyle(
                aspect = text("aspect"),
                mode = text("mode")!!,
                background = text("background"),
                backgroundColor = text("background_color"),
                videoHeight = number("video_height"),
                videoPosition = text("video_position"),
                zoom = number("zoom"),
                templateArea = area?.let { TemplateArea(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double, it.getValue("width").jsonPrimitive.double, it.getValue("height").jsonPrimitive.double) },
            )
            val source = o["source"].takeUnless { it == null || it is JsonNull }?.jsonArray?.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int }
            val composition = Composition.make(frame, source, 50, null, o.getValue("cropY").jsonPrimitive.int, keyframes(o.getValue("keyframes")))
            val inputs = o.getValue("inputs") as? JsonObject ?: JsonObject(emptyMap())
            val graph = RenderGraph.filter(composition, RenderGraph.Inputs(null, inputs["template"]?.jsonPrimitive?.intOrNull, null), null)
            assertEquals("filter #$i", o.getValue("filter").jsonPrimitive.content, graph)
        }
    }
}
