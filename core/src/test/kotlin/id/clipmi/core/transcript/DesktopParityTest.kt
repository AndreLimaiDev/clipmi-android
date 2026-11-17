package id.clipmi.core.transcript

import id.clipmi.core.php.Php
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Padanan dikunci dengan output ClipMI desktop: fixture di resources/fixtures/transcript diekspor oleh
 * tools/export-transcript-fixtures.php memakai PHP desktop (8.3). Setiap angka harus identik (bukan sekadar dekat).
 * Fixture dari subtitle video asli ada di input/real (tidak di-commit); bila tidak ada, hanya fixture sintetis diuji.
 */
class DesktopParityTest {
    private val root = File(javaClass.getResource("/fixtures/transcript/corrections.json")!!.toURI()).parentFile
    private val json = Json { ignoreUnknownKeys = true }

    private val corrections = CorrectionApplier(
        json.parseToJsonElement(File(root, "corrections.json").readText()).jsonArray.map {
            it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content
        },
    )

    @Test
    fun roundMatchesPhp83() {
        val data = json.parseToJsonElement(File(root, "round.json").readText()).jsonObject
        val input = data.getValue("in").jsonArray
        val output = data.getValue("out").jsonArray
        var mismatches = 0
        val examples = mutableListOf<String>()
        for (i in input.indices) {
            val value = input[i].jsonPrimitive.double
            val expected = output[i].jsonPrimitive.double
            val actual = Php.round(value, 3)
            if (actual != expected) {
                mismatches++
                if (examples.size < 10) examples += "round($value, 3): PHP $expected, Kotlin $actual"
            }
        }
        assertEquals("Selisih dengan PHP:\n" + examples.joinToString("\n"), 0, mismatches)
    }

    @Test
    fun srtWriterMatchesDesktop() {
        val data = json.parseToJsonElement(File(root, "srt-writer.json").readText()).jsonObject
        val cues = data.getValue("cues").jsonArray.map {
            val row = it.jsonArray
            Srt.Line(row[0].jsonPrimitive.double, row[1].jsonPrimitive.double, row[2].jsonPrimitive.content)
        }
        assertEquals(data.getValue("srt").jsonPrimitive.content, Srt.write(cues))
    }

    @Test
    fun synthetic() = checkAll(File(root, "expected").listFiles { f -> f.isFile }!!.toList())

    @Test
    fun realSubtitles() {
        val files = File(root, "expected/real").listFiles { f -> f.isFile }.orEmpty().toList()
            .filter { inputFor(it) != null }
        if (files.isEmpty()) println("Fixture subtitle asli (input/real) tidak ada; dilewati.")
        checkAll(files)
    }

    private fun inputFor(expected: File): File? {
        val dir = File(root, "input/" + expected.parentFile.relativeTo(File(root, "expected")).path)
        return dir.listFiles { f -> f.isFile && f.nameWithoutExtension == expected.nameWithoutExtension }?.firstOrNull()
    }

    private fun checkAll(files: List<File>) {
        assertTrue(files.isNotEmpty() || files.isEmpty())
        val failures = files.mapNotNull { expected ->
            runCatching { check(expected) }.exceptionOrNull()?.let { "${expected.name}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun check(expectedFile: File) {
        val expected = json.parseToJsonElement(expectedFile.readText()).jsonObject
        val input = inputFor(expectedFile) ?: error("input tidak ditemukan")

        expected["error"]?.let { error ->
            val e = runCatching { TranscriptParser.parse(input.readBytes()) }.exceptionOrNull()
            assertEquals(error.jsonPrimitive.content, e?.message)
            return
        }

        val cues = TranscriptParser.parse(input.readBytes())
        val expectedCues = expected.getValue("cues").jsonArray.map { it.jsonObject.toCue() }
        compareLists("cues", expectedCues, cues)

        val segments = TranscriptTidier().tidy(cues)
        val segmentSerializer = ListSerializer(Segment.serializer())
        compareLists("segments", json.decodeFromJsonElement(segmentSerializer, expected.getValue("segments")), segments)

        val corrected = expected.getValue("corrected").jsonObject
        var count = 0
        val correctedSegments = segments.map { segment ->
            val (s, n) = corrections.applyToSegment(segment)
            count += n
            s
        }
        assertEquals("jumlah koreksi", corrected.getValue("count").jsonPrimitive.int, count)
        compareLists("corrected", json.decodeFromJsonElement(segmentSerializer, corrected.getValue("segments")), correctedSegments)
    }

    private fun JsonObject.toCue() = Cue(
        start = getValue("start").jsonPrimitive.double,
        end = getValue("end").jsonPrimitive.double,
        text = getValue("text").jsonPrimitive.content,
        words = (get("words") as? kotlinx.serialization.json.JsonArray)?.let {
            json.decodeFromJsonElement(ListSerializer(Word.serializer()), it)
        },
        wordsEstimated = getValue("wordsEstimated").jsonPrimitive.boolean,
    )

    private fun <T> compareLists(label: String, expected: List<T>, actual: List<T>) {
        val first = (0 until maxOf(expected.size, actual.size)).firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) }
            ?: return
        fail(
            "$label berbeda (PHP ${expected.size}, Kotlin ${actual.size}) mulai indeks $first:\n" +
                "  PHP:    ${expected.getOrNull(first)}\n  Kotlin: ${actual.getOrNull(first)}",
        )
    }
}
