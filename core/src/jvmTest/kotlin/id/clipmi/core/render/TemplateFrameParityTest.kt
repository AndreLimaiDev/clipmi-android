package id.clipmi.core.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Area transparan template dikunci dengan `TemplateFrame::analyze` desktop (tools/export-template-fixtures.php: gambar
 * acak, kanal alpha diperkecil FFmpeg desktop). Pembulatan penskala FFmpeg tidak ditiru bit demi bit, jadi tepi
 * setengah transparan boleh bergeser satu sel kisi (0,5% layar); sebagian besar kasus harus identik.
 */
class TemplateFrameParityTest {
    private val root = File(id.clipmi.core.FIXTURE_DIR, "template")

    @Test
    fun areaSamaDenganDesktop() {
        val cases = Json.parseToJsonElement(File(root, "template.json").readText()).jsonObject.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        var shifted = 0
        for (case in cases.map { it.jsonObject }) {
            val file = case.getValue("file").jsonPrimitive.content
            val image = ImageIO.read(File(root, file))
            val alpha = IntArray(image.width * image.height) { i -> image.getRGB(i % image.width, i / image.width) ushr 24 }
            val expectedError = case["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val actual = runCatching { TemplateFrame.analyze(alpha, image.width, image.height) }
            if (expectedError != null) {
                val message = actual.exceptionOrNull()?.message
                if (message != expectedError) failures += "$file: galat \"$expectedError\", didapat ${actual.getOrNull() ?: message}"
                continue
            }
            val result = actual.getOrElse { failures += "$file: ${it.message}"; continue }
            val area = case.getValue("area").jsonObject
            val expected = TemplateArea(
                area.getValue("x").jsonPrimitive.double, area.getValue("y").jsonPrimitive.double,
                area.getValue("width").jsonPrimitive.double, area.getValue("height").jsonPrimitive.double,
            )
            val cell = 1.0 / TemplateFrame.GRID + 1e-9
            val near = listOf(result.area.x - expected.x, result.area.y - expected.y, result.area.width - expected.width, result.area.height - expected.height)
                .all { kotlin.math.abs(it) <= cell }
            if (!near || result.aspect != case.getValue("aspect").jsonPrimitive.content) {
                failures += "$file: $expected ${case["aspect"]}, didapat ${result.area} ${result.aspect}"
            } else if (result.area != expected) {
                shifted++
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${cases.size} beda:\n" + failures.joinToString("\n"))
        assertEquals(40, cases.size)
        if (shifted > 2) fail("$shifted template bergeser satu sel kisi (maksimal 2)")
    }
}
