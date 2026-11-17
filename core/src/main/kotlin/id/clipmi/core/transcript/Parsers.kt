package id.clipmi.core.transcript

import id.clipmi.core.php.Php
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Pintu masuk parsing (padanan `App\Transcript\TranscriptParser`): menyeragamkan encoding dan akhir baris. */
object TranscriptParser {
    fun parse(bytes: ByteArray): List<Cue> = parse(decode(bytes))

    fun parse(text: String): List<Cue> {
        val content = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = if (Php.ltrim(content).startsWith("{")) Json3Parser.parse(content) else SubtitleParser.parse(content)
        if (cues.isEmpty()) {
            throw TranscriptParseException("File tidak berisi subtitle yang bisa dibaca. Pastikan formatnya SRT, VTT, atau json3.")
        }
        return cues
    }

    /** File dari Subtitle Edit atau Notepad lama bisa berupa UTF-16 (dengan BOM) atau Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        ) {
            return String(bytes, Charsets.UTF_16)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}

/**
 * Parser json3 YouTube (padanan `Json3Parser`). Pada subtitle otomatis tiap "seg" satu kata dengan offset waktunya;
 * pada subtitle manual satu seg berisi satu kalimat tanpa waktu per kata.
 */
object Json3Parser {
    private data class Piece(val text: String, val start: Double, val hasOffset: Boolean)

    fun parse(content: String): List<Cue> {
        val data = runCatching { Json.parseToJsonElement(content) }.getOrNull()
        val events = (data as? JsonObject)?.get("events")
        val list = when (events) {
            is JsonArray -> events
            is JsonObject -> events.values.toList()
            else -> throw TranscriptParseException("Format json3 tidak dikenali.")
        }

        return list.mapNotNull { event ->
            if (event !is JsonObject) return@mapNotNull null
            val pieces = pieces(event)
            // Event tanpa teks: definisi jendela atau penanda baris baru.
            if (pieces.isEmpty()) return@mapNotNull null

            val start = number(event["tStartMs"]) / 1000
            val end = start + number(event["dDurationMs"]) / 1000
            val hasWordTiming = pieces.size > 1 || pieces[0].hasOffset
            Cue(
                Php.round(start, 3),
                Php.round(end, 3),
                pieces.joinToString(" ") { it.text },
                if (hasWordTiming) words(pieces, end) else null,
            )
        }
    }

    private fun pieces(event: JsonObject): List<Piece> {
        val start = number(event["tStartMs"]) / 1000
        val segs = when (val s = event["segs"]) {
            is JsonArray -> s
            is JsonObject -> s.values.toList()
            else -> emptyList()
        }
        return segs.mapNotNull { seg ->
            if (seg !is JsonObject) return@mapNotNull null
            val text = Php.trim(string(seg["utf8"]))
            if (text.isEmpty()) return@mapNotNull null
            val offset = seg["tOffsetMs"]
            Piece(text, start + number(offset) / 1000, offset != null && offset !is JsonNull)
        }
    }

    private fun words(pieces: List<Piece>, end: Double): List<Word> =
        pieces.flatMapIndexed { i, piece -> WordTimer.distribute(piece.text, piece.start, pieces.getOrNull(i + 1)?.start ?: end) }

    /** `$x ?? 0` lalu dipakai sebagai angka. */
    private fun number(element: JsonElement?): Double =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: 0.0

    private fun string(element: JsonElement?): String =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
}

/**
 * Parser SRT dan WebVTT (padanan `SubtitleParser`): blok dipisah baris kosong dengan satu baris "mulai --> selesai";
 * blok sebelum cue pertama (header WEBVTT, STYLE, REGION) dan blok NOTE dilewati.
 */
object SubtitleParser {
    private val TIMING = Regex("""^\s*(${Timecode.PATTERN})\s*-->\s*(${Timecode.PATTERN})""")
    private val INLINE_TIME = Regex("""<(${Timecode.PATTERN})>""")
    private val BLOCKS = Regex("""\n{2,}""")
    private val NOTE = Regex("""^NOTE\b""")

    private class Entry(val start: Double, val end: Double, val lines: MutableList<String>)

    fun parse(content: String): List<Cue> {
        val entries = mutableListOf<Entry>()
        for (block in Php.trim(content).split(BLOCKS)) {
            val lines = block.split("\n")
            val timingIndex = lines.indexOfFirst { TIMING.containsMatchIn(it) }.takeIf { it >= 0 }

            if (timingIndex != null) {
                val m = TIMING.find(lines[timingIndex])!!
                entries += Entry(
                    Timecode.toSeconds(m.groupValues[1]),
                    Timecode.toSeconds(m.groupValues[2]),
                    lines.drop(timingIndex + 1).toMutableList(),
                )
                continue
            }

            // Subtitle otomatis YouTube memisahkan baris timing dan teks dengan baris berisi satu spasi. Bila file
            // pernah disimpan ulang di editor, spasi itu hilang dan teksnya jadi blok tersendiri: disambung ke cue
            // sebelumnya.
            if (entries.isNotEmpty() && !NOTE.containsMatchIn(lines[0])) {
                entries.last().lines += lines
            }
        }
        return entries.map { cue(it.start, it.end, it.lines) }
    }

    private fun cue(start: Double, end: Double, rawLines: List<String>): Cue {
        val lines = rawLines.map(Php::trim).filter { it.isNotEmpty() }

        // VTT bergulir YouTube: baris pertama mengulang kalimat sebelumnya, baris bertag waktu adalah ucapan baru.
        val timedLines = lines.filter { INLINE_TIME.containsMatchIn(it) }
        if (timedLines.isEmpty()) return Cue(start, end, lines.joinToString(" "))

        val words = timedWords(timedLines.joinToString(" "), start, end)
        return Cue(start, end, words.joinToString(" ") { it.text }, words)
    }

    /** "kata<00:00:01.360><c> kata</c>..." menjadi daftar kata bertimestamp. */
    private fun timedWords(text: String, start: Double, end: Double): List<Word> {
        // preg_split(..., PREG_SPLIT_DELIM_CAPTURE): teks, waktu, teks, waktu, ...
        val chunks = mutableListOf<Pair<Double, String>>()
        var last = 0
        var chunkStart = start
        for (m in INLINE_TIME.findAll(text)) {
            chunks += chunkStart to text.substring(last, m.range.first)
            chunkStart = Timecode.toSeconds(m.groupValues[1])
            last = m.range.last + 1
        }
        chunks += chunkStart to text.substring(last)

        return chunks.flatMapIndexed { i, (chunkBegin, chunkText) ->
            WordTimer.distribute(Php.stripTags(chunkText), chunkBegin, chunks.getOrNull(i + 1)?.first ?: end)
        }
    }
}
