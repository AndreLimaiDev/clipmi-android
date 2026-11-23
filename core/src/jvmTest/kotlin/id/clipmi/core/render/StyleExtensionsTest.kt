package id.clipmi.core.render

import id.clipmi.core.transcript.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pengaturan khusus ClipMI HP (tidak ada di desktop): kotak sorot yang digambar, crop gaya bingkai, template penuh. */
class StyleExtensionsTest {
    private val measurer = TextMeasurer { _, size, text -> text.length * size * 0.5 }
    private val words = listOf(Word("Sabar", 0.0, 0.5), Word("itu", 0.5, 1.0), Word("indah", 1.0, 1.5))
    private val line = SubtitleLine(0.0, 1.5, "Sabar itu indah", words)
    private val boxStyle = CaptionStyle.DEFAULT.copy(highlightActiveWord = true, highlightStyle = "box", highlightColor = "#2563EB")

    @Test
    fun oldBoxUnchangedWithoutNewSettings() {
        val ass = AssWriter(measurer).build(listOf(line), 2.0, null, boxStyle)
        assertFalse(ass.contains("\\an7\\pos"))
        assertTrue(ass.contains("Dialogue: 0,0:00:00.00,0:00:00.50,Box"))
    }

    @Test
    fun drawnBoxPerWordWithRoundedCorners() {
        val ass = AssWriter(measurer).build(listOf(line), 2.0, null, boxStyle.copy(highlightRadius = 20, highlightHeight = 50))
        val boxes = ass.lines().filter { it.contains(",Box,") }
        assertEquals(3, boxes.size)
        boxes.forEach { assertTrue(it, it.contains("\\an7\\pos(") && it.contains("\\p1}m ") && it.contains("b ")) }
        // Kotak ke-2 di kanan kotak pertama.
        val x = boxes.map { Regex("""\\pos\((-?\d+),""").find(it)!!.groupValues[1].toInt() }
        assertTrue(x[0] < x[1] && x[1] < x[2])
    }

    @Test
    fun drawnBoxWidthAndVerticalPosition() {
        fun box(style: CaptionStyle): Pair<Int, Int> {
            val first = AssWriter(measurer).build(listOf(line), 2.0, null, style).lines().first { it.contains(",Box,") }
            val m = Regex("""\\pos\((-?\d+),(-?\d+)\)""").find(first)!!
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        val low = boxStyle.copy(highlightHeight = 40)
        val (_, bottomTop) = box(low)
        val (_, centerTop) = box(low.copy(highlightY = 50))
        val (_, topTop) = box(low.copy(highlightY = 0))
        // Bawaan kotak rendah = rata bawah (seperti stabilo); 50 = tengah; 0 = atas.
        assertEquals(bottomTop, box(low.copy(highlightY = 100)).second)
        assertTrue(topTop < centerTop && centerTop < bottomTop)
        // Lebih lebar = mulai lebih ke kiri, tetap berpusat di kata.
        assertTrue(box(low.copy(highlightWidth = 150)).first < box(low).first)
        assertEquals(HighlightBox(6, 45, 100, 100), boxStyle.copy(highlightRadius = 6, highlightHeight = 45).highlightBox())
    }

    @Test
    fun drawnBoxWrapsLongLinesItself() {
        val long = (1..8).map { Word("panjangsekali$it", it.toDouble(), it + 0.5) }
        val wide = SubtitleLine(1.0, 9.0, long.joinToString(" ") { it.text }, long)
        val ass = AssWriter(measurer).build(listOf(wide), 10.0, null, boxStyle.copy(highlightRadius = 20))
        val boxes = ass.lines().filter { it.contains(",Box,") }
        assertEquals(8, boxes.size)
        // Baris dipecah sendiri (\N) dan kotak baris kedua lebih rendah dari baris pertama.
        assertTrue(ass.lines().first { it.contains(",Sub,") }.contains("\\N"))
        val tops = boxes.map { Regex("""\\pos\(-?\d+,(-?\d+)\)""").find(it)!!.groupValues[1].toInt() }
        assertTrue(tops.distinct().size > 1)
        assertTrue(tops.last() > tops.first())
    }

    @Test
    fun frameCropAndFullTemplate() {
        val area = TemplateArea(0.1, 0.2, 0.8, 0.5)
        val frame = FrameStyle(mode = "template", templateArea = area)
        val inArea = frame.videoRect()!!
        assertTrue(inArea.width < frame.canvasWidth())
        val full = frame.copy(templateFill = "full").videoRect()!!
        assertEquals(Rect(0, 0, frame.canvasWidth(), frame.canvasHeight()), full)
        // Judul dan subtitle tetap mengikuti area transparan.
        assertEquals(Composition.make(frame).titleBar, Composition.make(frame.copy(templateFill = "full")).titleBar)

        val spec = ClipSpec(startTime = 0.0, endTime = 3.0, title = "Uji", frame = frame.copy(cropX = 20, cropY = 70))
        assertEquals(20, spec.effectiveCropPosition())
        assertEquals(70, spec.effectiveCropY())
        assertEquals(35, spec.copy(cropPosition = 35).effectiveCropPosition())
    }

    @Test
    fun coverTitlePosition() {
        val layout = CoverTitleLayout(model = "editorial", text = "Sabar itu indah", x = 30, y = 20)
        val ass = CoverTitle.build(layout, "Judul", emptyList(), 1080, 1920, measurer)
        val default = CoverTitle.build(layout.copy(x = null, y = null), "Judul", emptyList(), 1080, 1920, measurer)
        assertTrue(ass.contains("\\pos(324,"))
        assertFalse(ass == default)
    }
}
