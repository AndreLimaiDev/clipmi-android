package id.clipmi.android.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.android.AppContainer
import id.clipmi.android.data.BumperEntity
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.domain.FontMeasurer
import id.clipmi.android.domain.decodeSpeakerTrack
import id.clipmi.android.domain.encodeSpeakerTrack
import id.clipmi.android.domain.toCore
import id.clipmi.core.analysis.Keywords
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.ClipEditor
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Semua yang bisa diubah di editor; satu langkah urungkan = satu snapshot. */
data class EditorSnapshot(
    val clip: EditableClip,
    val captionStyleId: Long?,
    val frameStyleId: Long?,
    val cropPosition: Int?,
    val cropZoom: Int?,
    val cropY: Int?,
    val hook: String?,
    val showHookOverlay: Boolean,
    val hookLayout: JsonObject?,
    val keywords: List<String>,
    val coverTime: Double? = null,
    val coverPath: String? = null,
    val coverTitle: Boolean = false,
    val coverInVideo: Boolean = true,
    val introId: Long? = null,
    val outroId: Long? = null,
    /** Posisi crop manual per adegan saat mengikuti pembicara (waktu project). */
    val speakerManual: List<SpeakerTrackData.ManualPosition> = emptyList(),
) {
    val hasCover: Boolean get() = coverTime != null || coverPath != null
}

/**
 * Keadaan editor klip: snapshot saat ini, riwayat urungkan/ulangi (paling banyak [HISTORY] langkah), penyimpanan ke
 * database setiap perubahan, dan subtitle pratinjau (ASS yang sama dengan render) yang dihitung ulang di latar.
 */
@Stable
class EditorState(
    val container: AppContainer,
    private val scope: CoroutineScope,
    val entity: ClipEntity,
    project: ProjectEntity,
    val segments: List<SegmentWords>,
    val source: File,
    initial: EditorSnapshot,
) {
    /** Project klip; intro/outro bawaannya bisa diubah dari tab Sampul. */
    var project by mutableStateOf(project)
    private val json = Json { ignoreUnknownKeys = true }
    private val undo = ArrayDeque<EditorSnapshot>()
    private val redo = ArrayDeque<EditorSnapshot>()
    private val saving = Mutex()
    private var gestureStart: EditorSnapshot? = null

    /** Keadaan saat gerakan yang sedang berjalan dimulai (atau keadaan sekarang bila tidak ada gerakan). */
    val gestureBase: EditorSnapshot get() = gestureStart ?: snapshot
    private var tracksJob: Job? = null
    private val fonts = container.renderer.fontsDir()
    private val core = ClipRender(AssWriter(FontMeasurer(fonts)))
    val thumbnail: File? = container.workspace.file(project.thumbnailPath)

    /** Ukuran video pratinjau (untuk posisi crop tetap dari jalur pembicara). */
    val sourceSize: Pair<Int, Int>? by lazy { id.clipmi.android.domain.MediaInfo.videoSize(source) }

    fun fontsDir(): File = fonts

    var snapshot by mutableStateOf(initial)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var captionStyles by mutableStateOf<List<CaptionStyleEntity>>(emptyList())
    var frameStyles by mutableStateOf<List<FrameStyleEntity>>(emptyList())
    /** Pustaka intro/outro; hanya yang filenya ada. */
    var bumpers by mutableStateOf<List<BumperEntity>>(emptyList())
    var tracks by mutableStateOf<PreviewTracks?>(null)
        private set
    var silences by mutableStateOf<List<Range>?>(null)
        private set
    var silenceError by mutableStateOf<String?>(null)
        private set

    val projectDuration: Double? get() = project.durationSeconds

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

    /** Klip mengikuti pembicara: tanpa posisi crop sendiri dan gaya bingkainya memotong video. */
    fun followsSpeaker(s: EditorSnapshot = snapshot): Boolean = s.cropPosition == null && frameStyle().usesCropPosition()

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
                val result = container.speakers.detect(entitySnapshot(s), project, s.clip.startTime, s.clip.endTime, s.speakerManual) { speakerProgress = it }
                speakerBase = result
                // Hasil deteksi bukan langkah urungkan; posisi manual di luar rentang dibuang.
                snapshot = snapshot.copy(speakerManual = result.manual)
                persist()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                speakerError = e.message ?: e.javaClass.simpleName
            } finally {
                speakerProgress = null
            }
        }
    }

    /**
     * Gaya yang sedang diedit di sheet "Edit gaya" (belum disimpan): pratinjau langsung memakainya. Null = memakai gaya
     * tersimpan.
     */
    var captionDraft by mutableStateOf<CaptionStyleEntity?>(null)
        private set
    var frameDraft by mutableStateOf<FrameStyleEntity?>(null)
        private set

    /** Gaya subtitle tersimpan yang dipakai klip (gaya klip, bawaan, atau yang pertama). */
    fun captionEntity(): CaptionStyleEntity? =
        snapshot.captionStyleId?.let { id -> captionStyles.firstOrNull { it.id == id } } ?: captionStyles.firstOrNull { it.isDefault } ?: captionStyles.firstOrNull()

    fun frameEntity(): FrameStyleEntity? =
        snapshot.frameStyleId?.let { id -> frameStyles.firstOrNull { it.id == id } } ?: frameStyles.firstOrNull { it.isDefault } ?: frameStyles.firstOrNull()

    fun captionStyle(): CaptionStyle = (captionDraft ?: captionEntity())?.toCore() ?: CaptionStyle.DEFAULT

    fun frameStyle(): FrameStyle = (frameDraft ?: frameEntity())?.toCore() ?: FrameStyle.DEFAULT

    fun editCaption(draft: CaptionStyleEntity?) {
        captionDraft = draft
        refreshTracks()
    }

    fun editFrame(draft: FrameStyleEntity?) {
        frameDraft = draft
        refreshTracks()
    }

    fun editor() = ClipEditor(segments, captionStyle(), projectDuration)

    fun spec(s: EditorSnapshot = snapshot) = ClipSpec(
        startTime = s.clip.startTime,
        endTime = s.clip.endTime,
        title = entity.title,
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
        style = captionStyle(),
        frame = frameStyle(),
        introSeconds = s.introId?.let { id -> bumpers.firstOrNull { it.id == id } }?.duration,
        outroSeconds = s.outroId?.let { id -> bumpers.firstOrNull { it.id == id } }?.duration,
        speakerKeyframes = speakerKeyframes(s),
    )

    /** Klip dengan isian editor saat ini (untuk sampul: kuncinya ikut berubah begitu tampilan berubah). */
    fun entitySnapshot(s: EditorSnapshot = snapshot): ClipEntity = entity.copy(
        startTime = s.clip.startTime, endTime = s.clip.endTime, cuts = s.clip.cuts.toJson()?.toString(), hook = s.hook,
        showHookOverlay = s.showHookOverlay, hookLayout = s.hookLayout?.toString(), captionStyleId = s.captionStyleId, frameStyleId = s.frameStyleId,
        cropPosition = s.cropPosition, cropZoom = s.cropZoom, cropY = s.cropY, coverTime = s.coverTime, coverPath = s.coverPath,
        coverTitle = s.coverTitle, coverInVideo = s.coverInVideo, introId = s.introId, outroId = s.outroId,
        speakerTrack = storedTrack(s)?.let(::encodeSpeakerTrack),
    )

    fun composition() = core.composition(spec())

    fun hookLayout(): HookLayout = spec().hookLayout()

    /** Perubahan satu langkah (masuk riwayat). Mengembalikan false bila ditolak (null) atau tidak mengubah apa-apa. */
    fun apply(change: (EditorSnapshot) -> EditorSnapshot?): Boolean {
        val next = change(snapshot) ?: return false
        if (next == snapshot) return false
        push(snapshot)
        set(next)
        return true
    }

    fun applyClip(change: (EditableClip) -> EditableClip?): Boolean = apply { s -> change(s.clip)?.let { s.copy(clip = it) } }

    /** Perubahan terus-menerus (seret, cubit): satu langkah riwayat untuk satu gerakan. */
    fun drag(change: (EditorSnapshot) -> EditorSnapshot) {
        if (gestureStart == null) gestureStart = snapshot
        snapshot = change(snapshot)
        refreshTracks()
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
        set(previous, history = false)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot)
        set(next, history = false)
    }

    fun toggleKeyword(phrase: String) = apply { it.copy(keywords = Keywords.toggle(it.keywords, phrase)) }

    private fun push(previous: EditorSnapshot) {
        undo.addLast(previous)
        while (undo.size > HISTORY) undo.removeFirst()
        redo.clear()
    }

    private fun set(next: EditorSnapshot, history: Boolean = true) {
        val rangeChanged = next.clip.startTime != snapshot.clip.startTime || next.clip.endTime != snapshot.clip.endTime
        snapshot = next
        if (!history) Unit
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        if (rangeChanged) silences = null
        persist()
        refreshTracks()
    }

    private fun persist() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        val s = snapshot
        scope.launch {
            saving.withLock {
                container.database.clips().updateEditor(
                    entity.id, s.clip.startTime, s.clip.endTime,
                    s.clip.cuts.toJson()?.toString(),
                    s.clip.subtitleLines?.let { json.encodeToString(SubtitleEdits.serializer(), it) },
                    s.clip.subtitleEdited, s.captionStyleId, s.frameStyleId, s.cropPosition, s.cropZoom, s.cropY,
                    s.hook, s.showHookOverlay, s.hookLayout?.toString(),
                    s.keywords.takeIf { it.isNotEmpty() }?.let { container.analysis.encodeList(it) },
                    s.coverTime, s.coverPath, s.coverTitle, s.coverInVideo, s.introId, s.outroId,
                )
                if (speakerBase != null) container.speakers.save(entity.id, storedTrack(s))
            }
        }
    }

    /** Subtitle pratinjau dihitung ulang sesaat setelah perubahan berhenti. */
    fun refreshTracks() {
        tracksJob?.cancel()
        tracksJob = scope.launch {
            delay(if (tracks == null) 0 else 120)
            val spec = spec()
            tracks = withContext(Dispatchers.Default) { core.previewTracks(spec, core.composition(spec), segments) }
        }
    }

    /** Jeda dari suara klip (silencedetect), dibaca sekali per rentang klip. */
    fun detectSilences() {
        if (silences != null) return
        val clip = snapshot.clip
        silenceError = null
        scope.launch {
            silences = runCatching { container.ffmpeg.silences(source, clip.startTime, clip.duration()) }
                .onFailure { silenceError = it.message }
                .getOrNull()
        }
    }

    companion object {
        const val HISTORY = 50

        suspend fun load(container: AppContainer, scope: CoroutineScope, clipId: Long): EditorState? {
            val clip = container.database.clips().find(clipId) ?: return null
            val project = container.database.projects().find(clip.projectId) ?: return null
            val source = container.workspace.file(project.previewPath ?: project.videoPath)?.takeIf { it.isFile } ?: return null
            val spec = container.renderer.spec(clip, project)
            val snapshot = EditorSnapshot(
                clip = EditableClip(spec.startTime, spec.endTime, CutSet.from(spec.cuts), spec.subtitleLines, spec.subtitleEdited),
                captionStyleId = clip.captionStyleId,
                frameStyleId = clip.frameStyleId,
                cropPosition = clip.cropPosition,
                cropZoom = clip.cropZoom,
                cropY = clip.cropY,
                hook = clip.hook,
                showHookOverlay = clip.showHookOverlay,
                hookLayout = spec.hookLayout,
                keywords = spec.keywords.orEmpty(),
                coverTime = clip.coverTime,
                coverPath = clip.coverPath,
                coverTitle = clip.coverTitle,
                coverInVideo = clip.coverInVideo,
                introId = clip.introId,
                outroId = clip.outroId,
                speakerManual = decodeSpeakerTrack(clip.speakerTrack)?.manual.orEmpty(),
            )
            return EditorState(container, scope, clip, project, container.renderer.segments(project.id), source, snapshot)
        }
    }
}
