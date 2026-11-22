package id.clipmi.core.analysis

import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.assertFalse
import id.clipmi.core.testing.assertTrue
import kotlin.test.Test

class PromptStylesTest {
    @Test
    fun kajianWithoutOptionsIsTheDesktopPrompt() {
        assertEquals(PromptBuilder.DEFAULT_TEMPLATE, PromptStyles.template(PromptStyles.KAJIAN))
        assertEquals(PromptBuilder.DEFAULT_TEMPLATE, PromptStyles.template(PromptStyles.KAJIAN, PromptOptions()))
    }

    @Test
    fun everyStyleKeepsTheSharedFormat() {
        val tail = PromptBuilder.DEFAULT_TEMPLATE.substring(PromptBuilder.DEFAULT_TEMPLATE.indexOf("Transkrip di bawah berformat"))
        (PromptStyles.BUILT_IN + PromptStyles.custom("x", "Uji", listOf("Cari momen lucu"))).forEach { style ->
            val t = PromptStyles.template(style)
            assertTrue(style.id, t.endsWith(tail))
            assertTrue(style.id, t.contains("Video: {{judul}} (durasi {{durasi}})"))
            val body = t.replace("{{durasi}}", "")
            assertFalse(style.id, body.contains("{durasi}") || body.contains("{maks}") || body.contains("{jumlah}"))
        }
    }

    @Test
    fun optionsChangeDurationCountAndFocus() {
        val t = PromptStyles.template(PromptStyles.PODCAST, PromptOptions(minSeconds = 15, maxSeconds = 30, minClips = 3, maxClips = 5, focus = "sabar"))
        assertTrue(t.contains("- Durasi ideal 15–30 detik."))
        assertTrue(t.contains("lebih panjang dari 30 detik"))
        assertTrue(t.contains("Pilih 3–5 klip"))
        assertTrue(t.contains("- Utamakan momen yang membahas: sabar."))
    }

    @Test
    fun overrideOnlyAppliesToKajianAndKeepsOptions() {
        assertEquals("X", PromptStyles.template(PromptStyles.KAJIAN, null, "X"))
        assertEquals("X\n\nTambahan kriteria:\n- Utamakan momen yang membahas: rezeki. Bila tidak ada, pilih momen terbaik lainnya.",
            PromptStyles.template(PromptStyles.KAJIAN, PromptOptions(focus = "rezeki"), "X"))
        assertFalse(PromptStyles.template(PromptStyles.STORY, null, "X") == "X")
    }

    @Test
    fun customStyleStartsWithUserCriteria() {
        val t = PromptStyles.template(PromptStyles.custom("x", "Khutbah", listOf("- Lewati khutbah pertama berbahasa Arab", "")))
        assertTrue(t.contains("Kriteria klip:\n- Lewati khutbah pertama berbahasa Arab\n- Durasi ideal 30–90 detik."))
    }
}
