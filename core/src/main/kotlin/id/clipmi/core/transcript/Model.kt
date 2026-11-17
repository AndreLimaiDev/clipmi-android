package id.clipmi.core.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Satu kata bertimestamp (detik, dibulatkan ke milidetik). Format JSON sama dengan kolom `words` desktop. */
@Serializable
data class Word(val text: String, val start: Double, val end: Double)

/** Satu potongan subtitle mentah hasil parser, sebelum dirapikan menjadi segmen (padanan `App\Transcript\Cue`). */
data class Cue(
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<Word>? = null,
    val wordsEstimated: Boolean = false,
) {
    val duration: Double get() = end - start

    /** `explode(' ', $text)`: dipisah per satu spasi persis. */
    fun tokens(): List<String> = if (text.isEmpty()) emptyList() else text.split(' ')
}

/** Segmen kalimat hasil perapian, siap disimpan sebagai TranscriptSegment (nama kolom sama dengan desktop). */
@Serializable
data class Segment(
    val sequence: Int,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val text: String,
    val words: List<Word>,
    @SerialName("words_estimated") val wordsEstimated: Boolean,
)

class TranscriptParseException(message: String) : Exception(message)
