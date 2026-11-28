package id.clipmi.core.analysis

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.assertThrows
import kotlin.test.Test

/** Balasan yang disalin dengan tombol salin ChatGPT (JSON ber-escape Markdown/HTML) bisa dibaca setelah dirapikan. */
class ChatPasteTest {
    // Tombol salin ChatGPT web: `\` di akhir baris, spasi awal baris jadi &#x20;.
    private val web = """
        {\
        &#x20; "clips": [\
        &#x20;   {\
        &#x20;     "title": "Kalau Memilih Pemimpin",\
        &#x20;     "start_segment": "S0019",\
        &#x20;     "hashtags": [\
        &#x20;       "#kajianislam",\
        &#x20;       "#dakwah"\
        &#x20;     ],\
        &#x20;     "series": null,\
        &#x20;     "part": null\
        &#x20;   },\
        &#x20;   {\
        &#x20;     "title": "Siapa Pemimpin Terbaik Setelah Rasulullah ﷺ?",\
        &#x20;     "start_segment": "S0027"\
        &#x20;   }\
        &#x20; ]\
        }
    """.trimIndent()

    // Aplikasi ChatGPT di HP: satu baris, kurung siku jadi \[ dan \].
    private val mobile = """{ "clips": \[ { "title": "Jin Itu Bisa Muslim?", "hashtags": \["#jin", "#islam"\], "keywords": \["jin", "salat"\] }, { "title": "Apa Arti Kata Jin?", "keywords": \["Jin", "janin"\] } \] }"""

    @Test
    fun webCopyIsReadableAfterCleaning() {
        assertThrows(AnalysisResultException::class) { ClipImporter.decode(web) }
        val clips = ClipImporter.decode(ChatPaste.clean(web))
        assertEquals(2, clips.size)
        assertEquals("Siapa Pemimpin Terbaik Setelah Rasulullah ﷺ?", clips[1].jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun mobileCopyIsReadableAfterCleaning() {
        assertThrows(AnalysisResultException::class) { ClipImporter.decode(mobile) }
        val clips = ClipImporter.decode(ChatPaste.clean(mobile))
        assertEquals(2, clips.size)
        assertEquals("Apa Arti Kata Jin?", clips[1].jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun curlyQuotesAreReadableAsLastResort() {
        // Smart Punctuation iOS / aplikasi catatan: tanda kutip lengkung.
        val curly = "{“clips”: [{“title”: “Sabar itu indah”}]}"
        assertThrows(AnalysisResultException::class) { ClipImporter.decode(curly) }
        val clips = ChatPaste.firstReadable(curly) { ClipImporter.decode(it) }
        assertEquals("Sabar itu indah", clips[0].jsonObject.getValue("title").jsonPrimitive.content)
        // Balasan yang sudah benar dibaca apa adanya (kutip lengkung di dalam teks tidak diubah).
        val fine = "{\"clips\": [{\"title\": \"Kata “jin”\"}]}"
        assertEquals("Kata “jin”", ChatPaste.firstReadable(fine) { ClipImporter.decode(it) }[0].jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun validJsonEscapesAreKept() {
        val json = """{"clips": [{"title": "Kata \"jin\" dan C:\\folder\nbaris \u00e9 \/"}]}"""
        assertEquals(json, ChatPaste.clean(json))
    }
}
