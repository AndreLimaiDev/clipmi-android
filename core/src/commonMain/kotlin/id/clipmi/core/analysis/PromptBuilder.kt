package id.clipmi.core.analysis

import id.clipmi.core.php.Php
import id.clipmi.core.transcript.Timecode
import kotlin.math.ceil

/** Satu segmen transkrip untuk prompt (ID segmen = nomor urut, misalnya S0012). */
data class PromptSegment(val sequence: Int, val startTime: Double, val endTime: Double, val text: String) {
    val code: String get() = "S" + id.clipmi.core.php.Fmt.pad(sequence, 4)
}

/**
 * Menyusun prompt pemilihan momen untuk chat AI mana pun (padanan `App\Analysis\PromptBuilder` desktop). Prompt yang
 * sama dipakai mode manual (salin/bagikan) dan Mode API.
 */
object PromptBuilder {
    /** Bisa diganti lewat pengaturan "prompt_template". {{judul}} dan {{durasi}} diisi otomatis. */
    val DEFAULT_TEMPLATE: String = """
        Kamu adalah editor konten dakwah yang memilih momen terbaik dari kajian untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).

        Video: {{judul}} (durasi {{durasi}})

        Kriteria klip:
        - Durasi ideal 30–90 detik.
        - Berdiri sendiri: penonton paham tanpa menonton video lengkapnya.
        - Dibuka dengan kalimat yang langsung menarik perhatian di 3 detik pertama.
        - Tidak memotong ayat, hadits, atau kalimat di tengah. Mulai di awal kalimat dan akhiri di akhir kalimat.
        - Lewati salam pembuka, pengumuman, dan bagian yang konteksnya bisa menyesatkan jika dipotong.
        - Pilih 5–15 klip sesuai panjang dan isi kajian, urutkan dari yang paling kuat.
        - Kisah atau penjelasan yang lebih panjang dari 90 detik boleh dipecah menjadi seri: beri judul seri yang sama di "series" dan nomor urut di "part" (1, 2, 3, …). Klip yang bukan seri memakai null untuk keduanya.

        Transkrip di bawah berformat "ID | mulai-selesai | teks". Tentukan awal dan akhir klip dengan ID segmen (misalnya "S0012"), jangan menulis detik sendiri.

        Balas HANYA dengan JSON valid, tanpa penjelasan di luar JSON, berformat:
        {
          "clips": [
            {
              "title": "Judul singkat yang menarik",
              "start_segment": "S0012",
              "end_segment": "S0019",
              "score": 85,
              "hook": "Teks pembuka di layar untuk 3 detik pertama",
              "caption": "Caption untuk unggahan",
              "hashtags": ["#kajian", "#nasihat"],
              "keywords": ["sabar", "ajal"],
              "reason": "Alasan singkat momen ini dipilih",
              "series": null,
              "part": null
            }
          ]
        }

        "score" adalah angka 1–100 untuk seberapa kuat klip ini menarik penonton.
        "keywords" berisi 2–5 kata atau frasa pendek (maksimal 3 kata) paling penting di klip, ditulis persis seperti di transkrip; kata-kata ini diwarnai di subtitle.
    """.trimIndent()

    const val DEFAULT_MAX_TOKENS = 120_000

    /** Segmen yang diulang di awal bagian berikutnya, supaya momen di perbatasan tidak hilang. */
    private const val OVERLAP_SEGMENTS = 15

    /** Satu prompt, atau beberapa bila transkrip melebihi batas panjang. */
    fun build(
        title: String,
        durationSeconds: Double?,
        segments: List<PromptSegment>,
        template: String? = null,
        maxTokens: Int? = null,
    ): List<String> {
        val header = strtr(
            template ?: DEFAULT_TEMPLATE,
            mapOf(
                "{{judul}}" to title,
                "{{durasi}}" to if (durationSeconds != null && durationSeconds != 0.0) Timecode.format(durationSeconds) else "tidak diketahui",
            ),
        )
        val lines = segments.map(::line)
        val budget = (maxTokens ?: DEFAULT_MAX_TOKENS) - estimateTokens(header) - 300
        val chunks = split(lines, maxOf(1000, budget))

        if (chunks.size == 1) return listOf(header + "\n\nTranskrip:\n" + chunks[0].joinToString("\n"))

        return chunks.mapIndexed { i, chunk ->
            header + "\n\nTranskrip ini panjang sehingga dibagi menjadi ${chunks.size} bagian. Ini bagian ${i + 1}." +
                " Pilih momen hanya dari bagian ini. Bagian-bagian saling tumpang-tindih sedikit, jadi momen yang terpotong di awal atau akhir bagian boleh dilewati." +
                "\n\nTranskrip (bagian ${i + 1}):\n" + chunk.joinToString("\n")
        }
    }

    fun line(segment: PromptSegment): String =
        "${segment.code} | ${Timecode.format(segment.startTime)}-${Timecode.format(segment.endTime)} | ${segment.text}"

    /** Perkiraan kasar (±3,5 karakter per token untuk teks Indonesia); cukup untuk memutuskan pemecahan. */
    fun estimateTokens(text: String): Int = ceil(Php.mbStrlen(text) / 3.5).toInt()

    private fun split(lines: List<String>, budget: Int): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var tokens = 0
        lines.forEachIndexed { i, line ->
            val lineTokens = estimateTokens(line) + 1
            if (current.isNotEmpty() && tokens + lineTokens > budget) {
                chunks += current
                val from = maxOf(0, i - OVERLAP_SEGMENTS)
                current = lines.subList(from, from + minOf(OVERLAP_SEGMENTS, i)).toMutableList()
                tokens = estimateTokens(current.joinToString("\n"))
            }
            current += line
            tokens += lineTokens
        }
        if (current.isNotEmpty() || chunks.isEmpty()) chunks += current
        return chunks
    }

    /** `strtr($s, $pairs)`: kunci terpanjang dicoba lebih dulu, teks hasil penggantian tidak dipindai ulang. */
    internal fun strtr(s: String, pairs: Map<String, String>): String {
        val keys = pairs.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val key = keys.firstOrNull { s.startsWith(it, i) }
            if (key != null) {
                out.append(pairs.getValue(key))
                i += key.length
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }
}
