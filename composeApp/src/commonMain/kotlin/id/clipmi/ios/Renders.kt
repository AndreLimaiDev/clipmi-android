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
class Renders(private val renderer: IosRenderer, private val scope: CoroutineScope) {
    var active by mutableStateOf<Long?>(null)
        private set
    var fraction by mutableStateOf(0.0)
        private set
    private var job: Job? = null

    fun start(clipId: Long) {
        if (active != null) return
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
            }
        }
    }

    fun cancel() = renderer.cancel()
}
