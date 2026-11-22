package id.clipmi.core.php

import id.clipmi.core.transcript.TranscriptParser
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pengganti multiplatform harus sama persis dengan API JVM yang digantikannya (dulu dipakai langsung di core). */
class FmtJvmTest {
    @Test
    fun fixedMatchesStringFormat() {
        val random = Random(20261122)
        val special = listOf(0.0, -0.0, 0.5, 1.5, 2.5, 0.125, 0.005, 1.005, 2.675, -1.005, 1e-7, 123456789.125, 1e17, 9.995, 99.995, -0.0049)
        val values = special + List(200_000) {
            when (it % 4) {
                0 -> random.nextDouble(-1000.0, 1000.0)
                1 -> random.nextInt(-100000, 100000) / 1000.0
                2 -> random.nextDouble() * 10.0.pow(random.nextInt(-8, 12))
                else -> (random.nextInt(0, 2000) + 0.5) / 100.0
            }
        }
        for (v in values) for (d in 0..4) {
            assertEquals(String.format(Locale.ROOT, "%.${d}f", v), Fmt.fixed(v, d), "fixed($v, $d)")
        }
    }

    @Test
    fun padAndHexMatchStringFormat() {
        for (v in listOf(0L, 5L, 42L, 123L, 1234L, 99999L, -3L, -42L)) for (w in 2..4) {
            assertEquals(String.format(Locale.ROOT, "%0${w}d", v), Fmt.pad(v, w))
        }
        for (v in 0..255) assertEquals(String.format(Locale.ROOT, "%02X", v), Fmt.hex2(v))
    }

    @Test
    fun codePointsMatchJvm() {
        val text = "aé😀𝔸\uD800x\uDC00ß"
        assertEquals(text.codePointCount(0, text.length), CodePoints.count(text))
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            assertEquals(cp, CodePoints.at(text, i))
            assertEquals(String(Character.toChars(cp)), CodePoints.toText(cp))
            i += Character.charCount(cp)
        }
    }

    @Test
    fun subtitleDecodingMatchesJvmCharsets() {
        val random = Random(7)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt(0, 256).toByte() }
            assertEquals(String(bytes, charset("windows-1252")), decodeWindows1252(bytes))
            val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes
            val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + bytes
            assertEquals(String(le, Charsets.UTF_16), TranscriptParser.decode(le))
            assertEquals(String(be, Charsets.UTF_16), TranscriptParser.decode(be))
        }
    }

    private fun decodeWindows1252(bytes: ByteArray): String {
        // Tanpa BOM dan bukan UTF-8 sah: lewat jalur Windows-1252 TranscriptParser.decode.
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val text = TranscriptParser.decode(invalidUtf8 + bytes)
        return text.substring(2)
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
}
