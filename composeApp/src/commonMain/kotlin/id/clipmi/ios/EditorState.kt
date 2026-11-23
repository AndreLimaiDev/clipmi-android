package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.ClipEditor
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.Composition
import id.clipmi.core.render.CutSet
import id.clipmi.core.render.EditableClip
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.HookLayout
import id.clipmi.core.render.PreviewTracks
import id.clipmi.core.render.Range
import id.clipmi.core.render.SegmentWords
import id.clipmi.core.render.SpeakerTrackData
import id.clipmi.core.render.SubtitleEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Semua yang bisa diubah di editor iOS; satu langkah urungkan = satu snapshot (subset EditorSnapshot Android). */
data class EditorSnapshot(
    val clip: EditableClip,
    val title: String,
    val captionStyleId: Long?,
    val frameStyleId: Long?,
    val hook: String?,
    val showHookOverlay: Boolean,
    val hookLayout: JsonObject?,
    val keywords: List<String>,
    val coverTime: Double? = null,
    val coverPath: String? = null,
    val coverTitle: Boolean = false,
    val coverInVideo: Boolean = true,
    val coverLayout: String? = null,
    val cropPosition: Int? = null,
    val cropZoom: Int? = null,
    val cropY: Int? = null,
    /** Posisi crop manual per adegan saat mengikuti pembicara (waktu project). */
    val speakerManual: List<SpeakerTrackData.ManualPosition> = emptyList(),
    /** Intro/outro dari pustaka. */
    val introId: Long? = null,
    val outroId: Long? = null,
) {
    val hasCover: Boolean get() = coverTime != null || coverPath != null
}

/**
 * Keadaan editor klip iOS, padanan EditorState Android: snapshot saat ini, riwayat urungkan/ulangi, penyimpanan ke
 * database setiap perubahan, komposisi pratinjau, dan subtitle pratinjau (ASS yang sama dengan render) yang dihitung
 * ulang sesaat setelah perubahan berhenti.
 */
class EditorState(
    private val repo: Repository,
    val renderer: IosRenderer,
    private val scope: CoroutineScope,
    val entity: ClipEntity,
    val project: ProjectEntity,
    val captionStyles: List<CaptionStyleEntity>,
    val frameStyles: List<FrameStyleEntity>,
    val segments: List<SegmentWords>,
    /** Video sumber (path absolut). */
    val source: String,
    /** Panjang video sumber (detik), batas rentang klip. */
    val sourceDuration: Double?,
    /** Ukuran video sumber setelah rotasi, untuk tata letak pratinjau dan mode "fit". */
    val sourceSize: Pair<Int, Int>?,
    /** Frame kecil video untuk latar blur pratinjau (path absolut), atau null. */
    val thumbnail: String?,
    initial: EditorSnapshot,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val undo = ArrayDeque<EditorSnapshot>()
    private val redo = ArrayDeque<EditorSnapshot>()
    private val saving = Mutex()
    private var lastSave: Job? = null
    private var gestureStart: EditorSnapshot? = null
    private var tracksJob: Job? = null
    private val core = ClipRender(AssWriter(IosFontMeasurer(renderer.engine, renderer.fontsDir)))

    // Dipakai komposisi awal (spec → jalur pembicara), jadi dideklarasikan lebih dulu.
    /** Hasil deteksi pembicara klip ini (dari database atau deteksi di editor); null = belum pernah dideteksi. */
    var speakerBase by mutableStateOf(decodeSpeakerTrack(entity.speakerTrack))
        private set

    /** Progres deteksi pembicara 0–1; null = tidak sedang mendeteksi. */
    var speakerProgress by mutableStateOf<Float?>(null)
        private set
    var speakerError by mutableStateOf<String?>(null)
        private set
    private var speakerJob: Job? = null
    private var trackCache: Pair<Triple<Double, Double, Any?>, SpeakerTrackData?>? = null

    var snapshot by mutableStateOf(initial)
        private set

    /** Pustaka intro/outro yang filenya ada (diisi saat editor dibuka). */
    var bumpers by mutableStateOf<List<BumperEntity>>(emptyList())

    /** Intro/outro bawaan project (dipasang ke klip baru di project ini). */
    var projectBumpers by mutableStateOf(project.introId to project.outroId)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var composition by mutableStateOf(layout(initial))
        private set
    var tracks by mutableStateOf<PreviewTracks?>(null)
        private set
    var silences by mutableStateOf<List<Range>?>(null)
        private set
    var silenceError by mutableStateOf<String?>(null)
        private set
    private var silenceJob: Job? = null

    val engine: MediaEngine get() = renderer.engine

    /** Keadaan saat gerakan yang sedang berjalan dimulai (atau keadaan sekarang bila tidak ada gerakan). */
    val gestureBase: EditorSnapshot get() = gestureStart ?: snapshot

    /** Klip mengikuti pembicara: tanpa posisi crop sendiri dan gaya bingkainya memotong video. */
    fun followsSpeaker(s: EditorSnapshot = snapshot): Boolean = s.cropPosition == null && frameStyle(s).usesCropPosition()

    /** Jalur pembicara untuk rentang dan posisi manual [s]; null bila hasil deteksi tidak mencakup rentang klip. */
    fun speakerTrack(s: EditorSnapshot = snapshot): SpeakerTrackData? {
        val base = speakerBase ?: return null
        val key = Triple(s.clip.startTime, s.clip.endTime, base to s.speakerManual)
        trackCache?.let { (k, v) -> if (k == key) return v }
        val track = if (base.covers(s.clip.startTime, s.clip.endTime)) base.copy(manual = s.speakerManual).retarget(s.clip.startTime, s.clip.endTime) else null
        trackCache = key to track
        return track
    }

    fun speakerKeyframes(s: EditorSnapshot = snapshot): List<List<Double>>? =
        if (followsSpeaker(s)) speakerTrack(s)?.keyframes?.takeIf { it.isNotEmpty() } else null

    /** Yang disimpan di kolom speaker_track: jalur rentang ini, atau hasil deteksi lama (perlu dideteksi ulang). */
    private fun storedTrack(s: EditorSnapshot): SpeakerTrackData? = speakerTrack(s) ?: speakerBase?.copy(manual = s.speakerManual)

    /** Perlu dideteksi: mengikuti pembicara tetapi hasil deteksi belum mencakup rentang klip. */
    fun needsSpeakerDetection(s: EditorSnapshot = snapshot): Boolean = followsSpeaker(s) && speakerTrack(s) == null

    /** Mendeteksi pembicara di rentang klip saat ini; posisi manual yang masih di dalam rentang tetap dipakai. */
    fun detectSpeaker() {
        if (speakerJob?.isActive == true) return
        val s = snapshot
        speakerError = null
        speakerProgress = 0f
        speakerJob = scope.launch {
            try {
                val result = renderer.speakers.detect(entitySnapshot(s), project, s.clip.startTime, s.clip.endTime, s.speakerManual) { speakerProgress = it }
                speakerBase = result
                // Hasil deteksi bukan langkah urungkan; posisi manual di luar rentang dibuang.
                update(snapshot.copy(speakerManual = result.manual))
                persist()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                speakerError = e.message ?: "Deteksi gagal."
            } finally {
                speakerProgress = null
            }
        }
    }

    /** Klip ini dari database (status render terbaru). */
    fun liveClip(): Flow<ClipEntity?> = repo.db.clips().observeOne(entity.id)

    fun captionStyle(s: EditorSnapshot = snapshot): CaptionStyle = renderer.captionFor(s.captionStyleId, captionStyles)

    fun frameStyle(s: EditorSnapshot = snapshot): FrameStyle = renderer.frameFor(s.frameStyleId, frameStyles)

    /** Id gaya yang benar-benar dipakai (klip, bawaan, atau yang pertama), untuk menandai pilihan. */
    fun captionStyleId(): Long? = captionStyles.firstOrNull { it.id == snapshot.captionStyleId }?.id
        ?: captionStyles.firstOrNull { it.isDefault }?.id ?: captionStyles.firstOrNull()?.id

    fun frameStyleId(): Long? = frameStyles.firstOrNull { it.id == snapshot.frameStyleId }?.id
        ?: frameStyles.firstOrNull { it.isDefault }?.id ?: frameStyles.firstOrNull()?.id

    fun editor() = ClipEditor(segments, captionStyle(), sourceDuration ?: project.durationSeconds)

    fun spec(s: EditorSnapshot = snapshot) = ClipSpec(
        startTime = s.clip.startTime,
        endTime = s.clip.endTime,
        title = s.title,
        partNumber = entity.partNumber,
        hook = s.hook,
        keywords = s.keywords,
        cuts = s.clip.cuts.toJson(),
        showHookOverlay = s.showHookOverlay,
        hookLayout = s.hookLayout,
        subtitleLines = s.clip.subtitleLines,
        subtitleEdited = s.clip.subtitleEdited,
        cropZoom = s.cropZoom,
        cropY = s.cropY,
        cropPosition = s.cropPosition,
        projectCropPosition = project.cropPosition,
        style = captionStyle(s),
        frame = frameStyle(s),
        speakerKeyframes = speakerKeyframes(s),
        introSeconds = s.introId?.let { id -> bumpers.firstOrNull { it.id == id } }?.duration,
        outroSeconds = s.outroId?.let { id -> bumpers.firstOrNull { it.id == id } }?.duration,
    )

    /** Klip dengan isian editor saat ini (untuk sampul: kuncinya ikut berubah begitu tampilan berubah). */
    fun entitySnapshot(s: EditorSnapshot = snapshot): ClipEntity = entity.copy(
        title = s.title, startTime = s.clip.startTime, endTime = s.clip.endTime, cuts = s.clip.cuts.toJson()?.toString(),
        subtitleLines = s.clip.subtitleLines?.let { json.encodeToString(SubtitleEdits.serializer(), it) }, subtitleEdited = s.clip.subtitleEdited,
        hook = s.hook, showHookOverlay = s.showHookOverlay, hookLayout = s.hookLayout?.toString(), keywords = renderer.encodeKeywords(s.keywords),
        captionStyleId = s.captionStyleId, frameStyleId = s.frameStyleId, coverTime = s.coverTime, coverPath = s.coverPath,
        coverTitle = s.coverTitle, coverInVideo = s.coverInVideo, coverLayout = s.coverLayout, introId = s.introId, outroId = s.outroId,
        cropPosition = s.cropPosition, cropZoom = s.cropZoom, cropY = s.cropY, speakerTrack = storedTrack(s)?.let(::encodeSpeakerTrack),
    )

    fun hookLayout(): HookLayout = spec().hookLayout()

    /** Tata letak pratinjau; ukuran sumber selalu diberikan (sama dengan EditorPreview Android). */
    private fun layout(s: EditorSnapshot): Composition {
        val spec = spec(s)
        return Composition.make(spec.frame, sourceSize, spec.effectiveCropPosition(), spec.effectiveZoom(), spec.effectiveCropY(), spec.speakerKeyframes)
    }

    /** Perubahan satu langkah (masuk riwayat). Mengembalikan false bila ditolak (null) atau tidak mengubah apa-apa. */
    fun apply(change: (EditorSnapshot) -> EditorSnapshot?): Boolean {
        val next = change(snapshot) ?: return false
        if (next == snapshot) return false
        push(snapshot)
        set(next)
        return true
    }

    fun applyClip(change: (EditableClip) -> EditableClip?): Boolean = apply { s -> change(s.clip)?.let { s.copy(clip = it) } }

    /** Perubahan terus-menerus (seret, geser slider): satu langkah riwayat untuk satu gerakan. */
    fun drag(change: (EditorSnapshot) -> EditorSnapshot) {
        if (gestureStart == null) gestureStart = snapshot
        update(change(snapshot))
    }

    fun endDrag() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != snapshot) {
            push(start)
            persist()
        }
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(snapshot)
        set(previous)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot)
        set(next)
    }

    fun toggleKeyword(phrase: String) = apply { it.copy(keywords = Keywords.toggle(it.keywords, phrase)) }

    private fun push(previous: EditorSnapshot) {
        undo.addLast(previous)
        while (undo.size > HISTORY) undo.removeFirst()
        redo.clear()
    }

    private fun set(next: EditorSnapshot) {
        update(next)
        persist()
    }

    /** Mengganti snapshot: komposisi langsung, subtitle pratinjau sesaat kemudian, jeda dicari ulang bila rentang berubah. */
    private fun update(next: EditorSnapshot) {
        val rangeChanged = next.clip.startTime != snapshot.clip.startTime || next.clip.endTime != snapshot.clip.endTime
        snapshot = next
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        if (rangeChanged) {
            silenceJob?.cancel()
            silences = null
            silenceError = null
        }
        composition = layout(next)
        refreshTracks()
    }

    /** Disimpan ke database (kolom editor saja, supaya status render yang berubah di latar tidak tertimpa). */
    private fun persist() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        val s = snapshot
        lastSave = scope.launch {
            saving.withLock {
                val clips = repo.db.clips()
                val fresh = clips.find(entity.id) ?: return@withLock
                val keywords = renderer.encodeKeywords(s.keywords)
                if (s.title != fresh.title || s.hook != fresh.hook || keywords != fresh.keywords) {
                    clips.updateText(entity.id, s.title, s.hook, fresh.caption, fresh.hashtags, keywords, changesRender = true)
                }
                clips.updateEditor(
                    entity.id, s.clip.startTime, s.clip.endTime,
                    s.clip.cuts.toJson()?.toString(),
                    s.clip.subtitleLines?.let { json.encodeToString(SubtitleEdits.serializer(), it) },
                    s.clip.subtitleEdited, s.captionStyleId, s.frameStyleId, s.cropPosition, s.cropZoom, s.cropY,
                    s.hook, s.showHookOverlay, s.hookLayout?.toString(), keywords,
                    s.coverTime, s.coverPath, s.coverTitle, s.coverInVideo, s.coverLayout, s.introId, s.outroId,
                )
                if (speakerBase != null) renderer.speakers.save(entity.id, storedTrack(s))
            }
        }
    }

    /** Menunggu penyimpanan terakhir selesai (sebelum render). */
    suspend fun flush() {
        lastSave?.join()
    }

    /** Subtitle pratinjau dihitung ulang sesaat setelah perubahan berhenti. */
    fun refreshTracks() {
        tracksJob?.cancel()
        tracksJob = scope.launch {
            delay(if (tracks == null) 0 else 120)
            val spec = spec()
            // Komposisi yang sama dengan render (ukuran sumber hanya untuk mode "fit").
            val render = core.composition(spec, if (spec.frame.mode == "fit" && spec.frame.framesVideo()) sourceSize else null)
            tracks = core.previewTracks(spec, render, segments)
        }
    }

    /** Jeda dari suara klip (silencedetect), dibaca sekali per rentang klip. */
    fun detectSilences() {
        if (silences != null || silenceJob?.isActive == true) return
        val clip = snapshot.clip
        silenceError = null
        silenceJob = scope.launch {
            runCatching { renderer.silences(source, clip.startTime, clip.duration()) }
                .onSuccess { silences = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) silenceError = it.message ?: "Jeda tidak terbaca." }
        }
    }

    /** Folder gambar sampul dari galeri (relatif Documents). */
    fun coverImagePath(): String = "${repo.projectDir(project.id)}/cover-images/clip-${entity.id}-${nowMillis()}.jpg"

    fun absolute(relative: String) = repo.absolute(relative)

    fun hashtags(clip: ClipEntity): List<String> = repo.hashtags(clip)

    /** Intro/outro klip ini dijadikan bawaan project (klip baru di project ini memakainya). */
    suspend fun saveProjectBumpers() {
        val s = snapshot
        repo.db.projects().setBumpers(project.id, s.introId, s.outroId)
        projectBumpers = s.introId to s.outroId
    }

    companion object {
        const val HISTORY = 50

        suspend fun load(repo: Repository, renderer: IosRenderer, scope: CoroutineScope, clipId: Long): EditorState? {
            val clip = repo.db.clips().find(clipId) ?: return null
            val project = repo.db.projects().find(clip.projectId) ?: return null
            val source = project.videoPath?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 } ?: return null
            val info = renderer.probe(source)
            val captionStyles = repo.db.styles().captionStyles()
            val frameStyles = repo.db.styles().frameStyles()
            val spec = renderer.specFor(clip, project.cropPosition, renderer.captionFor(clip.captionStyleId, captionStyles), renderer.frameFor(clip.frameStyleId, frameStyles))
            // Latar blur pratinjau: satu frame di awal klip (Android memakai thumbnail YouTube).
            val thumbnail = repo.absolute("${repo.projectDir(project.id)}/editor-thumb.jpg")
            createDirectory(repo.absolute(repo.projectDir(project.id)))
            val hasThumbnail = renderer.videoFrame(source, clip.startTime + 0.5, thumbnail)
            val snapshot = EditorSnapshot(
                clip = EditableClip(spec.startTime, spec.endTime, CutSet.from(spec.cuts), spec.subtitleLines, spec.subtitleEdited),
                title = clip.title,
                captionStyleId = clip.captionStyleId,
                frameStyleId = clip.frameStyleId,
                hook = clip.hook,
                showHookOverlay = clip.showHookOverlay,
                hookLayout = spec.hookLayout,
                keywords = spec.keywords.orEmpty(),
                coverTime = clip.coverTime,
                coverPath = clip.coverPath,
                coverTitle = clip.coverTitle,
                coverInVideo = clip.coverInVideo,
                coverLayout = clip.coverLayout,
                cropPosition = clip.cropPosition,
                cropZoom = clip.cropZoom,
                cropY = clip.cropY,
                speakerManual = decodeSpeakerTrack(clip.speakerTrack)?.manual.orEmpty(),
                introId = clip.introId,
                outroId = clip.outroId,
            )
            val bumpers = repo.db.bumpers().all().filter { fileSize(repo.absolute(it.path)) > 0 }
            return EditorState(
                repo, renderer, scope, clip, project, captionStyles, frameStyles, renderer.segments(project.id), source,
                info?.duration ?: project.durationSeconds,
                info?.let { i -> i.width?.let { w -> i.height?.let { h -> w to h } } },
                thumbnail.takeIf { hasThumbnail },
                snapshot,
            ).also {
                it.bumpers = bumpers
                it.refreshTracks()
            }
        }
    }
}
