package id.clipmi.core.transcript

import kotlin.math.roundToLong

/** Penulis SRT untuk hasil transkripsi API (padanan `Transcriber::toSrt` desktop). */
object Srt {
    data class Line(val start: Double, val end: Double, val text: String)

    fun write(cues: List<Line>): String = cues.mapIndexed { i, cue ->
        "${i + 1}\n${time(cue.start)} --> ${time(maxOf(cue.end, cue.start + 0.1))}\n${cue.text}\n"
    }.joinToString("\n")

    private fun time(seconds: Double): String {
        // (int) round($seconds * 1000): setengah menjauhi nol, sama dengan PHP untuk angka positif.
        val ms = phpRoundToLong(seconds * 1000)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    private fun phpRoundToLong(value: Double): Long = id.clipmi.core.php.Php.round(value, 0).roundToLong()
}
