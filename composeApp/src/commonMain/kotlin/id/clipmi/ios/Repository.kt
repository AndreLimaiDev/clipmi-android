package id.clipmi.ios

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ClipSource
import id.clipmi.android.data.CorrectionEntryEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.SourceType
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.ChatPaste
import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ImportSegment
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.analysis.PromptOptions
import id.clipmi.core.analysis.PromptSegment
import id.clipmi.core.analysis.PromptStyles
import id.clipmi.core.transcript.CorrectionApplier
import id.clipmi.core.transcript.Segment
import id.clipmi.core.transcript.TranscriptParser
import id.clipmi.core.transcript.TranscriptTidier
import id.clipmi.core.transcript.Word
import id.clipmi.core.youtube.SubtitlePicker
import id.clipmi.core.youtube.YouTubeUrl
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Hasil tempel balasan chat AI (sama dengan ImportOutcome Android). */
data class ReplyOutcome(val created: Int, val skipped: Int, val errors: List<String>) {
    fun message(): String = "$created kandidat klip ditambahkan" +
        (if (skipped > 0) ", $skipped sudah ada" else "") +
        (if (errors.isNotEmpty()) ", ${errors.size} dilewati" else "")
}

/**
 * Logika project iOS di atas database Room bersama Android: project YouTube, transkrip dari subtitle YouTube
 * (TranscriptParser + TranscriptTidier + kamus koreksi, seperti `Transcripts` Android), dan analisis manual (prompt
 * disalin ke chat AI, balasan JSON ditempel), seperti `Analysis` Android. Path file relatif terhadap Documents,
 * karena path Documents berubah setiap aplikasi dipasang ulang.
 */
class Repository(val db: AppDatabase, val documents: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val wordsSerializer = ListSerializer(Word.serializer())
    private val strings = ListSerializer(String.serializer())

    fun projectDir(id: Long) = "projects/$id"

    fun absolute(relative: String) = "$documents/$relative"

    /** Project baru dari link YouTube; null bila bukan link video. Link yang sudah ada mengembalikan project lamanya. */
    suspend fun createYouTube(url: String): Pair<Long, Boolean>? {
        val videoId = YouTubeUrl.videoId(url) ?: return null
        db.projects().findByYouTubeId(videoId)?.let { return it.id to false }
        val id = db.projects().insert(
            ProjectEntity(
                title = ProjectEntity.youtubePlaceholderTitle(videoId),
                sourceType = SourceType.YOUTUBE,
                sourceUrl = YouTubeUrl.canonical(videoId),
                youtubeVideoId = videoId,
                videoStatus = VideoStatus.Downloading.value,
                videoProgress = 0,
                transcriptStatus = TranscriptStatus.Fetching.value,
            ),
        )
        createDirectory(absolute(projectDir(id)))
        return id to true
    }

    suspend fun markDownloading(id: Long) {
        db.projects().modify(id) {
            it.copy(
                videoStatus = VideoStatus.Downloading.value,
                videoProgress = 0,
                transcriptStatus = if (it.transcript == TranscriptStatus.Ready) it.transcriptStatus else TranscriptStatus.Fetching.value,
                errorMessage = null,
            )
        }
        createDirectory(absolute(projectDir(id)))
    }

    /** Unduhan yang terputus karena aplikasi ditutup (iOS tidak melanjutkannya di latar) ditandai gagal. */
    suspend fun recoverInterrupted() {
        for (project in db.projects().all()) {
            for (clip in db.clips().forProject(project.id)) {
                if (!RenderStatus.from(clip.renderStatus).isBusy) continue
                db.clips().update(
                    clip.copy(renderStatus = RenderStatus.Failed.value, renderProgress = null, renderError = "Render terhenti karena aplikasi ditutup. Render ulang."),
                )
            }
            if (AnalysisStatus.isActive(project.analysisStatus)) {
                db.projects().modify(project.id) {
                    it.copy(
                        analysisStatus = AnalysisStatus.FAILED,
                        analysisMessage = "Analisis terhenti karena aplikasi ditutup. Ketuk Lanjutkan untuk meneruskan bagian yang belum selesai.",
                    )
                }
            }
            // Transkripsi API yang terputus (pengambilan subtitle YouTube tidak memakai transcriptionMessage).
            if (project.transcript == TranscriptStatus.Fetching && project.transcriptionMessage != null && project.video == VideoStatus.Ready) {
                val restore = if (db.segments().count(project.id) > 0) TranscriptStatus.Ready else TranscriptStatus.NeedsTranscript
                db.projects().modify(project.id) {
                    it.copy(
                        transcriptStatus = restore.value, transcriptionResumeAt = null,
                        transcriptionMessage = "Transkripsi terhenti karena aplikasi ditutup. Jalankan lagi; potongan yang sudah selesai tidak dikirim ulang.",
                    )
                }
                continue
            }
            // Video dari galeri yang belum selesai disalin saat aplikasi ditutup.
            if (!project.isYouTube && project.video == VideoStatus.Processing) {
                db.projects().modify(project.id) {
                    it.copy(videoStatus = VideoStatus.Failed.value, errorMessage = "Video belum selesai disalin karena aplikasi ditutup. Hapus project ini lalu pilih videonya lagi.")
                }
                continue
            }
            if (project.video != VideoStatus.Downloading && project.transcript != TranscriptStatus.Fetching) continue
            db.projects().modify(project.id) {
                it.copy(
                    videoStatus = if (it.video == VideoStatus.Downloading) VideoStatus.Failed.value else it.videoStatus,
                    videoProgress = null,
                    transcriptStatus = if (it.transcript == TranscriptStatus.Fetching) TranscriptStatus.Failed.value else it.transcriptStatus,
                    errorMessage = "Unduhan terhenti karena aplikasi ditutup. Unduh ulang dari halaman project.",
                )
            }
        }
    }

    /** Hasil unduhan dari Swift (lihat [DownloadListener.finished]): judul, durasi, video, lalu transkrip. */
    suspend fun finishDownload(id: Long, resultJson: String) {
        val result = runCatching { json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
        val ok = result?.get("ok")?.jsonPrimitive?.booleanOrNull == true
        val dir = projectDir(id)
        val info = readText(absolute("$dir/youtube.info.json"))?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val title = info?.string("title") ?: result?.string("title")
        val duration = (info?.get("duration") ?: result?.get("duration"))?.jsonPrimitive?.doubleOrNull
        val error = result?.string("error") ?: "Unduhan gagal."
        db.projects().modify(id) {
            it.copy(
                title = if (title != null && it.title == ProjectEntity.youtubePlaceholderTitle(it.youtubeVideoId.orEmpty())) title else it.title,
                durationSeconds = duration ?: it.durationSeconds,
                videoStatus = if (ok) VideoStatus.Ready.value else VideoStatus.Failed.value,
                videoProgress = null,
                videoPath = if (ok) "$dir/${result!!.string("file") ?: "source.mp4"}" else it.videoPath,
                errorMessage = if (ok) null else error,
            )
        }
        importYouTubeTranscript(id, info)
    }

    /** Subtitle Indonesia terbaik (SubtitlePicker) menjadi transkrip; transkrip yang sudah siap tidak diganti. */
    private suspend fun importYouTubeTranscript(id: Long, info: JsonObject?) {
        val project = db.projects().find(id) ?: return
        if (project.transcript == TranscriptStatus.Ready) return
        val track = info?.let { SubtitlePicker.pick(it) }
        if (track == null) {
            db.projects().modify(id) {
                it.copy(transcriptStatus = if (info == null) TranscriptStatus.Failed.value else TranscriptStatus.NeedsTranscript.value)
            }
            return
        }
        val bytes = readBytes(absolute("${projectDir(id)}/youtube.${track.language}.json3"))
        if (bytes == null) {
            db.projects().modify(id) {
                it.copy(transcriptStatus = TranscriptStatus.Failed.value, errorMessage = "Subtitle tersedia di YouTube tetapi gagal diunduh. Coba lagi beberapa saat lagi.")
            }
            return
        }
        try {
            importTranscript(id, bytes, track.source.value)
        } catch (e: Exception) {
            db.projects().modify(id) { it.copy(transcriptStatus = TranscriptStatus.Failed.value, errorMessage = "Gagal membaca subtitle YouTube. ${e.message}") }
        }
    }

    /** Parsing, perapian, kamus koreksi, lalu transkrip project diganti (padanan `Transcripts.import` Android). */
    suspend fun importTranscript(id: Long, content: ByteArray, source: String, language: String = "id"): Int {
        val tidied = TranscriptTidier().tidy(TranscriptParser.parse(content))
        val corrections = CorrectionApplier(db.corrections().active().map { it.wrongText to it.correctText })
        val segments = tidied.map { corrections.applyToSegment(it).first }
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.segments().replace(id, segments.map { it.toEntity(id) })
                db.projects().modify(id) {
                    it.copy(
                        transcriptStatus = TranscriptStatus.Ready.value,
                        transcriptSource = source,
                        transcriptLanguage = language,
                        errorMessage = if (it.video == VideoStatus.Failed) it.errorMessage else null,
                    )
                }
            }
        }
        return segments.size
    }

    /** Prompt analisis (satu, atau beberapa bagian untuk transkrip panjang) dengan gaya Kajian. */
    /**
     * Satu prompt, atau beberapa bagian bila transkrip panjang (sama dengan Analysis.prompts Android): gaya prompt
     * project dan opsinya, template dan batas panjang dari Pengaturan bila [settings] diberikan.
     */
    suspend fun prompts(id: Long, settings: IosSettings? = null): List<String> {
        val project = db.projects().find(id) ?: return emptyList()
        if (project.transcript != TranscriptStatus.Ready) return emptyList()
        val segments = db.segments().forProject(id).map { PromptSegment(it.sequence, it.startTime, it.endTime, it.text) }
        if (segments.isEmpty()) return emptyList()
        val styles = settings?.promptStyles() ?: PromptStyles.BUILT_IN
        val style = styles.firstOrNull { it.id == project.promptStyle } ?: PromptStyles.KAJIAN
        val options = project.promptOptions?.let { runCatching { json.decodeFromString(PromptOptions.serializer(), it) }.getOrNull() }
        val template = PromptStyles.template(style, options, settings?.promptTemplate())
        return PromptBuilder.build(project.title, project.durationSeconds, segments, template, settings?.promptMaxTokens())
    }

    /**
     * Balasan JSON chat AI menjadi kandidat klip; kandidat lama tidak dihapus dan rentang yang sudah ada dilewati.
     * @throws AnalysisResultException bila balasan tidak berisi JSON klip yang bisa dibaca.
     */
    suspend fun importReply(id: Long, reply: String): ReplyOutcome {
        val segments = db.segments().forProject(id).map {
            ImportSegment(it.sequence, it.startTime, it.endTime, decodeWords(it.words).ifEmpty { null })
        }
        val parsed = try {
            ClipImporter.parse(reply, segments)
        } catch (e: AnalysisResultException) {
            runCatching { ClipImporter.parse(ChatPaste.clean(reply), segments) }.getOrNull() ?: throw e
        }
        var created = 0
        var skipped = 0
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val existing = db.clips().forProject(id).toMutableList()
                val project = db.projects().find(id)
                for (draft in parsed.drafts) {
                    val t = ClipImporter.DUPLICATE_TOLERANCE
                    if (existing.any { it.startTime in draft.startTime - t..draft.startTime + t && it.endTime in draft.endTime - t..draft.endTime + t }) {
                        skipped++
                        continue
                    }
                    val clip = ClipEntity(
                        projectId = id,
                        clipSeriesId = draft.series?.let { db.series().firstOrCreate(id, it) },
                        partNumber = draft.partNumber,
                        title = draft.title,
                        startTime = draft.startTime,
                        endTime = draft.endTime,
                        score = draft.score,
                        hook = draft.hook,
                        caption = draft.caption,
                        hashtags = json.encodeToString(strings, draft.hashtags),
                        keywords = draft.keywords?.let { json.encodeToString(strings, it) },
                        reason = draft.reason,
                        reviewStatus = ReviewStatus.Candidate.value,
                        source = ClipSource.AI,
                        introId = project?.introId,
                        outroId = project?.outroId,
                    )
                    db.clips().insert(clip)
                    existing += clip
                    created++
                }
            }
        }
        return ReplyOutcome(created, skipped, parsed.errors)
    }

    fun hashtags(clip: ClipEntity): List<String> =
        clip.hashtags?.let { runCatching { json.decodeFromString(strings, it) }.getOrNull() }.orEmpty()

    /**
     * Menghapus video sumber untuk menghemat ruang (padanan ProjectActions.deleteSource Android). Transkrip, klip, gambar
     * kecil, dan hasil render tetap ada. Mengembalikan jumlah byte yang dibebaskan (0 = tidak dihapus).
     */
    suspend fun deleteSource(projectId: Long): Long {
        val project = db.projects().find(projectId) ?: return 0
        if (project.video != VideoStatus.Ready) return 0
        if (db.clips().forProject(projectId).any { RenderStatus.from(it.renderStatus).isBusy }) return 0
        val files = listOfNotNull(project.videoPath, project.previewPath).map { absolute(it) }.filter { fileSize(it) > 0 }
        val freed = files.sumOf { fileSize(it) }
        files.forEach { removeItem(it) }
        db.projects().modify(projectId) {
            it.copy(videoStatus = VideoStatus.Deleted.value, videoPath = null, previewPath = null, sourceDeletedAt = nowMillis())
        }
        return freed
    }

    // --- Kamus koreksi (padanan Transcripts + ProjectActions.saveCorrection Android) ---------------------------------

    /** Menyimpan entri kamus; pesan kesalahan untuk pengguna, atau null bila tersimpan. */
    suspend fun saveCorrection(id: Long?, wrong: String, correct: String, active: Boolean): String? {
        val w = wrong.trim()
        val c = correct.trim()
        if (w.isEmpty() || c.isEmpty()) return "Teks salah dan teks benar wajib diisi."
        val duplicate = db.corrections().findByWrong(w)
        if (duplicate != null && duplicate.id != id) return "\"$w\" sudah ada di kamus."
        if (id == null) {
            db.corrections().insert(CorrectionEntryEntity(wrongText = w, correctText = c, isActive = active))
        } else {
            val existing = db.corrections().find(id) ?: return "Entri kamus tidak ditemukan."
            db.corrections().update(existing.copy(wrongText = w, correctText = c, isActive = active, updatedAt = nowMillis()))
        }
        return null
    }

    /** Menerapkan ulang kamus aktif ke transkrip project; jumlah koreksi yang mengubah teks. */
    suspend fun applyCorrections(projectId: Long, applier: CorrectionApplier? = null): Int {
        val corrections = applier ?: CorrectionApplier(db.corrections().active().map { it.wrongText to it.correctText })
        if (corrections.isEmpty) return 0
        var total = 0
        val changed = db.segments().forProject(projectId).mapNotNull { entity ->
            val segment = Segment(entity.sequence, entity.startTime, entity.endTime, entity.text, decodeWords(entity.words), entity.wordsEstimated)
            val (corrected, count) = corrections.applyToSegment(segment)
            if (count == 0) return@mapNotNull null
            total += count
            entity.copy(text = corrected.text, words = json.encodeToString(wordsSerializer, corrected.words), wordsEstimated = corrected.wordsEstimated)
        }
        if (changed.isNotEmpty()) db.segments().updateAll(changed)
        return total
    }

    /** Kamus diterapkan ulang ke semua project; jumlah project yang berubah dan total koreksi. */
    suspend fun applyCorrectionsToAll(): Pair<Int, Int> {
        val applier = CorrectionApplier(db.corrections().active().map { it.wrongText to it.correctText })
        var projects = 0
        var total = 0
        for (project in db.projects().all()) {
            val count = applyCorrections(project.id, applier)
            if (count > 0) projects++
            total += count
        }
        return projects to total
    }

    private fun decodeWords(value: String?): List<Word> =
        value?.let { runCatching { json.decodeFromString(wordsSerializer, it) }.getOrNull() }.orEmpty()

    private fun Segment.toEntity(projectId: Long) = TranscriptSegmentEntity(
        projectId = projectId,
        sequence = sequence,
        startTime = startTime,
        endTime = endTime,
        text = text,
        words = json.encodeToString(wordsSerializer, this.words),
        wordsEstimated = wordsEstimated,
    )

    private fun JsonObject.string(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
