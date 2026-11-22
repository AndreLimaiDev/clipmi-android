package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.core.php.Php
import id.clipmi.core.render.AssWriter
import id.clipmi.core.render.ClipRender
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.render.Composition
import id.clipmi.core.render.PreviewTracks
import id.clipmi.core.render.SegmentWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Isian editor klip iOS (subset EditorState Android): rentang waktu, judul, hook, gaya subtitle, dan gaya bingkai.
 * Pratinjau dihitung core seperti render: [composition] untuk tata letak video dan [tracks] (ASS) untuk libass.
 */
class EditorState(
    private val repo: Repository,
    private val renderer: IosRenderer,
    private val scope: CoroutineScope,
    val clip: ClipEntity,
    val project: ProjectEntity,
    val captionStyles: List<CaptionStyleEntity>,
    val frameStyles: List<FrameStyleEntity>,
    private val segments: List<SegmentWords>,
    /** Panjang video sumber (detik), batas rentang klip. */
    val sourceDuration: Double,
    /** Ukuran video sumber setelah rotasi, untuk tata letak pratinjau dan mode "fit". */
    val sourceSize: Pair<Int, Int>?,
) {
    var start by mutableStateOf(clip.startTime)
    var end by mutableStateOf(clip.endTime)
    var title by mutableStateOf(clip.title)
    var hook by mutableStateOf(clip.hook.orEmpty())
    var showHook by mutableStateOf(clip.showHookOverlay)
    var captionStyleId by mutableStateOf(clip.captionStyleId ?: captionStyles.firstOrNull { it.isDefault }?.id)
    var frameStyleId by mutableStateOf(clip.frameStyleId ?: frameStyles.firstOrNull { it.isDefault }?.id)

    var spec by mutableStateOf<ClipSpec?>(null)
        private set
    var composition by mutableStateOf<Composition?>(null)
        private set
    var tracks by mutableStateOf<PreviewTracks?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    val engine: MediaEngine get() = renderer.engine

    /** Klip ini dari database (status render terbaru). */
    fun liveClip(): Flow<ClipEntity?> = repo.db.clips().observe(clip.projectId).map { list -> list.firstOrNull { it.id == clip.id } }

    private val core = ClipRender(AssWriter(IosFontMeasurer(renderer.engine, renderer.fontsDir)))
    private var job: Job? = null

    val changed: Boolean
        get() = start != clip.startTime || end != clip.endTime || title != clip.title || hook != clip.hook.orEmpty() ||
            showHook != clip.showHookOverlay || captionStyleId != (clip.captionStyleId ?: captionStyles.firstOrNull { it.isDefault }?.id) ||
            frameStyleId != (clip.frameStyleId ?: frameStyles.firstOrNull { it.isDefault }?.id)

    /** Klip dengan isian editor (belum disimpan). */
    fun edited(): ClipEntity = clip.copy(
        startTime = Php.round(start, 3),
        endTime = Php.round(end, 3),
        title = title.trim().ifEmpty { clip.title },
        hook = hook.trim().ifEmpty { null },
        showHookOverlay = showHook,
        captionStyleId = captionStyleId,
        frameStyleId = frameStyleId,
    )

    /** Menghitung ulang komposisi dan trek pratinjau (ditunda sebentar saat isian berubah cepat). */
    fun refresh(immediate: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(150)
            val next = renderer.spec(edited(), project.cropPosition)
            val layout = core.composition(next, if (next.frame.mode == "fit" && next.frame.framesVideo()) sourceSize else null)
            spec = next
            composition = layout
            tracks = core.previewTracks(next, layout, segments)
        }
    }

    /** Geser awal/akhir klip (detik), dalam batas video dan minimal 1 detik. */
    fun nudgeStart(delta: Double) {
        start = (start + delta).coerceIn(0.0, end - 1.0)
        refresh()
    }

    fun nudgeEnd(delta: Double) {
        end = (end + delta).coerceIn(start + 1.0, if (sourceDuration > 0) sourceDuration else end + delta)
        refresh()
    }

    suspend fun save(): ClipEntity {
        saving = true
        try {
            val now = nowMillis()
            val updated = edited().copy(editedAt = now, updatedAt = now)
            repo.db.clips().update(updated)
            return updated
        } finally {
            saving = false
        }
    }
}
