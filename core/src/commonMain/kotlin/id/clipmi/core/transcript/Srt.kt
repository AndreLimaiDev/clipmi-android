package id.clipmi.core.transcript

import id.clipmi.core.php.Fmt
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
        return "${Fmt.pad(ms / 3_600_000, 2)}:${Fmt.pad(ms / 60_000 % 60, 2)}:${Fmt.pad(ms / 1000 % 60, 2)},${Fmt.pad(ms % 1000, 3)}"
    }

    private fun phpRoundToLong(value: Double): Long = id.clipmi.core.php.Php.round(value, 0).roundToLong()
}
