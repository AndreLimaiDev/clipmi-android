package id.clipmi.core.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentEditorTest {
    private val segment = Segment(
        sequence = 1, startTime = 10.0, endTime = 14.0, text = "kita doakan yang bertugas",
        words = listOf(Word("kita", 10.0, 10.5), Word("doakan", 10.5, 11.5), Word("yang", 11.5, 12.0), Word("bertugas", 12.0, 14.0)),
        wordsEstimated = false,
    )

    @Test
    fun sameTextKeepsSegment() {
        assertSame(segment, SegmentEditor.edit(segment, "  kita doakan  yang bertugas "))
    }

    @Test
    fun emptyTextIsRejected() {
        assertNull(SegmentEditor.edit(segment, "   "))
    }

    @Test
    fun replacedWordKeepsItsTiming() {
        val edited = SegmentEditor.edit(segment, "kita doakan yang bertugas,")!!.let { it }
        // Hanya kata terakhir yang berubah, jumlah kata sama: waktu tetap.
        assertEquals(listOf(10.0 to 10.5, 10.5 to 11.5, 11.5 to 12.0, 12.0 to 14.0), edited.words.map { it.start to it.end })
        assertEquals("bertugas,", edited.words.last().text)
        assertFalse(edited.wordsEstimated)
    }

    @Test
    fun splitWordIsRetimedInsideItsOwnSpan() {
        val edited = SegmentEditor.edit(segment, "kita doa kan yang bertugas")!!
        assertEquals(listOf("kita", "doa", "kan", "yang", "bertugas"), edited.words.map { it.text })
        // "doakan" (10.5–11.5) dibagi ke "doa" dan "kan"; kata lain tidak bergeser.
        assertEquals(10.0 to 10.5, edited.words[0].start to edited.words[0].end)
        assertEquals(10.5, edited.words[1].start, 1e-9)
        assertEquals(11.0, edited.words[1].end, 1e-9)
        assertEquals(11.5, edited.words[2].end, 1e-9)
        assertEquals(11.5 to 12.0, edited.words[3].start to edited.words[3].end)
        assertTrue(edited.wordsEstimated)
        assertEquals("kita doa kan yang bertugas", edited.text)
    }

    @Test
    fun removedWordsShrinkWithoutMovingOthers() {
        val edited = SegmentEditor.edit(segment, "kita yang bertugas")!!
        assertEquals(listOf("kita", "yang", "bertugas"), edited.words.map { it.text })
        assertEquals(listOf(10.0 to 10.5, 11.5 to 12.0, 12.0 to 14.0), edited.words.map { it.start to it.end })
    }

    @Test
    fun wordAddedAtEndSharesTheLastWordSpan() {
        val edited = SegmentEditor.edit(segment, "kita doakan yang bertugas semua")!!
        assertEquals(listOf("kita", "doakan", "yang", "bertugas", "semua"), edited.words.map { it.text })
        // "bertugas" (12–14) dibagi dengan "semua"; kata sebelumnya tetap.
        assertEquals(11.5 to 12.0, edited.words[2].start to edited.words[2].end)
        assertEquals(12.0, edited.words[3].start, 1e-9)
        assertTrue(edited.words[4].end - edited.words[4].start > 0.5)
        assertEquals(14.0, edited.words[4].end, 1e-9)
    }

    @Test
    fun segmentWithoutWordsIsDistributed() {
        val plain = segment.copy(words = emptyList(), wordsEstimated = true)
        val edited = SegmentEditor.edit(plain, "kami doakan")!!
        assertEquals(10.0, edited.words.first().start, 1e-9)
        assertEquals(14.0, edited.words.last().end, 1e-9)
    }
}
