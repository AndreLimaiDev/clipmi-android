package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Unduhan YouTube yang sedang berjalan (satu per satu: Python tertanam hanya satu). Progres hanya di memori; status
 * akhir disimpan ke database lewat [Repository.finishDownload].
 */
class Downloads(private val repo: Repository, private val engine: MediaEngine, private val scope: CoroutineScope) {
    var active by mutableStateOf<Long?>(null)
        private set
    var fraction by mutableStateOf(0.0)
        private set
    var status by mutableStateOf("")
        private set

    /** Dipanggil setelah unduhan selesai disimpan (misalnya analisis otomatis bila transkrip siap). */
    var onFinished: (suspend (Long) -> Unit)? = null

    fun start(id: Long) {
        if (active != null) return
        active = id
        fraction = 0.0
        status = "Menyiapkan…"
        scope.launch {
            val url = repo.db.projects().find(id)?.sourceUrl
            if (url == null) {
                active = null
                return@launch
            }
            repo.markDownloading(id)
            engine.downloadYouTube(
                url, repo.absolute(repo.projectDir(id)),
                object : DownloadListener {
                    override fun progress(fraction: Double, status: String) {
                        this@Downloads.fraction = fraction
                        this@Downloads.status = status
                    }

                    override fun finished(resultJson: String) {
                        scope.launch {
                            try {
                                repo.finishDownload(id, resultJson)
                                onFinished?.invoke(id)
                            } finally {
                                active = null
                            }
                        }
                    }
                },
            )
        }
    }
}
