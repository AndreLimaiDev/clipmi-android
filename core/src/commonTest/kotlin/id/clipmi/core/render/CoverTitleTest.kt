package id.clipmi.core.render

import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.assertTrue
import kotlin.test.Test

class CoverTitleTest {
    private val measure = TextMeasurer { _, size, text -> text.length * size * 0.5 }

    @Test
    fun starsMarkAccentAcrossWords() {
        val lines = CoverTitle.tokens("Perbaiki\n*Shalat mu*\nmaka Allah", emptyList(), 3)
        assertEquals(3, lines.size)
        assertEquals(listOf(true, true), lines[1].map { it.accent })
        assertEquals(listOf("Shalat", "mu"), lines[1].map { it.text })
        assertTrue(lines[0].none { it.accent })
    }

    @Test
    fun keywordsAccentWithoutStars() {
        val lines = CoverTitle.tokens("Perbaiki shalatmu sekarang", listOf("shalatmu"), 5)
        assertEquals(listOf(false, true, false), lines.single().map { it.accent })
    }

    @Test
    fun longSingleLineIsSplitEvenly() {
        val lines = CoverTitle.tokens("satu dua tiga empat lima", emptyList(), 2)
        assertEquals(listOf(2, 2, 1), lines.map { it.size })
    }

    @Test
    fun classicIsDefaultAndNotEncoded() {
        assertTrue(CoverTitle.parse(null).classic)
        assertEquals(null, CoverTitle.encode(CoverTitleLayout()))
        assertEquals("pop", CoverTitle.parse(CoverTitle.encode(CoverTitleLayout(model = "pop"))).model)
    }

    @Test
    fun everyModelProducesEvents() {
        for (model in CoverTitle.MODELS.keys - CoverTitle.CLASSIC) {
            val ass = CoverTitle.build(CoverTitleLayout(model = model), "Perbaiki Shalatmu Maka Allah Permudah", listOf("Shalatmu"), 1080, 1920, measure)
            assertTrue(model, ass.contains("PlayResX: 1080"))
            assertTrue(model, ass.lines().count { it.startsWith("Dialogue: 1") } >= 2)
        }
    }
}
