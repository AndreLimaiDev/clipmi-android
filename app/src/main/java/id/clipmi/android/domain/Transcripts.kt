package id.clipmi.android.domain

import androidx.room.withTransaction
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.core.transcript.CorrectionApplier
import id.clipmi.core.transcript.Segment
import id.clipmi.core.transcript.TranscriptParser
import id.clipmi.core.transcript.TranscriptTidier
import id.clipmi.core.transcript.Word
import id.clipmi.core.youtube.TranscriptSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ImportResult(val segmentCount: Int, val correctionCount: Int)

/**
 * Mem-parsing, merapikan, menerapkan kamus koreksi, lalu menyimpan transkrip ke project, menggantikan transkrip lama
 * (padanan `TranscriptImporter` dan `CorrectionApplier::applyToProject` desktop).
 */
class Transcripts(private val db: AppDatabase) {
    private val json = Json
    private val wordsSerializer = ListSerializer(Word.serializer())

    /** @throws id.clipmi.core.transcript.TranscriptParseException bila file tidak bisa dibaca. */
    suspend fun import(projectId: Long, content: ByteArray, source: TranscriptSource, language: String? = "id"): ImportResult {
        // Parsing sebelum transaksi, jadi file yang rusak tidak menyentuh transkrip lama.
        val tidied = TranscriptTidier().tidy(TranscriptParser.parse(content))
        val corrections = correctionApplier()
        var correctionCount = 0
        val segments = tidied.map { segment ->
            val (corrected, count) = corrections.applyToSegment(segment)
            correctionCount += count
            corrected
        }

        db.withTransaction {
            db.segments().replace(projectId, segments.map { it.toEntity(projectId) })
            db.projects().modify(projectId) {
                it.copy(
                    transcriptStatus = TranscriptStatus.Ready.value,
                    transcriptSource = source.value,
                    transcriptLanguage = language,
                    // Pesan error video tetap disimpan; hanya error transkrip yang dianggap selesai.
                    errorMessage = if (it.video == VideoStatus.Failed) it.errorMessage else null,
                )
            }
        }
        return ImportResult(segments.size, correctionCount)
    }

    suspend fun correctionApplier(): CorrectionApplier =
        CorrectionApplier(db.corrections().active().map { it.wrongText to it.correctText })

    /** Menerapkan ulang kamus aktif ke transkrip project; jumlah koreksi yang mengubah teks. */
    suspend fun applyCorrections(projectId: Long, applier: CorrectionApplier? = null): Int {
        val corrections = applier ?: correctionApplier()
        if (corrections.isEmpty) return 0
        var total = 0
        db.withTransaction {
            val changed = db.segments().forProject(projectId).mapNotNull { entity ->
                val (corrected, count) = corrections.applyToSegment(entity.toSegment())
                if (count == 0) return@mapNotNull null
                total += count
                entity.copy(text = corrected.text, words = encode(corrected.words), wordsEstimated = corrected.wordsEstimated)
            }
            db.segments().updateAll(changed)
        }
        return total
    }

    /** Kamus diterapkan ulang ke semua project; jumlah project yang berubah dan total koreksi. */
    suspend fun applyCorrectionsToAll(): Pair<Int, Int> {
        val applier = correctionApplier()
        var projects = 0
        var total = 0
        for (project in db.projects().all()) {
            val count = applyCorrections(project.id, applier)
            if (count > 0) projects++
            total += count
        }
        return projects to total
    }

    fun decodeWords(words: String?): List<Word> = words?.let { json.decodeFromString(wordsSerializer, it) }.orEmpty()

    private fun encode(words: List<Word>): String = json.encodeToString(wordsSerializer, words)

    private fun Segment.toEntity(projectId: Long) = TranscriptSegmentEntity(
        projectId = projectId,
        sequence = sequence,
        startTime = startTime,
        endTime = endTime,
        text = text,
        words = encode(words),
        wordsEstimated = wordsEstimated,
    )

    private fun TranscriptSegmentEntity.toSegment() = Segment(sequence, startTime, endTime, text, decodeWords(words), wordsEstimated)
}
