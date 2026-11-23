package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Render yang sedang berjalan (satu per satu: FFmpeg di dalam proses hanya satu). VideoToolbox tidak bisa encode saat
 * aplikasi di latar, jadi aplikasi harus tetap terbuka; status akhir disimpan di klip (render_status).
 */
class Renders(val renderer: IosRenderer, val scope: CoroutineScope) {
    var active by mutableStateOf<Long?>(null)
        private set
    var fraction by mutableStateOf(0.0)
        private set
    private var job: Job? = null

    /** Klip yang menunggu giliran render (satu per satu). */
    var queued by mutableStateOf<List<Long>>(emptyList())
        private set

    /** Merender beberapa klip berurutan (padanan antrean render Android). */
    fun startAll(clipIds: List<Long>) {
        val fresh = clipIds.filter { it != active && it !in queued }
        if (fresh.isEmpty()) return
        queued = queued + fresh
        if (active == null) next()
    }

    private fun next() {
        val id = queued.firstOrNull() ?: return
        queued = queued.drop(1)
        start(id)
    }

    fun start(clipId: Long) {
        if (active != null) {
            if (clipId != active && clipId !in queued) queued = queued + clipId
            return
        }
        active = clipId
        fraction = 0.0
        job = scope.launch {
            try {
                renderer.render(clipId) { fraction = it }
            } catch (e: Exception) {
                // Pesan gagal sudah disimpan di klip (render_error).
            } finally {
                active = null
                job = null
                next()
            }
        }
    }

    /** Membatalkan render yang berjalan sekaligus antreannya. */
    fun cancel() {
        queued = emptyList()
        renderer.cancel()
    }

    fun unqueue(clipId: Long) {
        queued = queued - clipId
    }
}
