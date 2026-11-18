package id.clipmi.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.ClipMiApp
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.domain.Settings
import id.clipmi.android.domain.Wall
import id.clipmi.android.domain.YtDlp
import id.clipmi.android.domain.YtDlpException
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.StatsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Antrean pengambilan statistik postingan: sekarang (dari layar) dan berkala (sampai hari ke-31). */
object StatsQueue {
    private const val QUEUE = "queue-stats"
    private const val PERIODIC = "stats-refresh"
    const val KEY_ROWS = "rows"

    /** Mengambil statistik postingan [rowIds] secepatnya (berurutan, satu antrean). */
    fun fetch(context: Context, rowIds: List<Long>) {
        val request = OneTimeWorkRequestBuilder<StatsWorker>()
            .setInputData(workDataOf(KEY_ROWS to rowIds.toLongArray()))
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Pembaruan otomatis tiap jam selama ada internet (desktop: scheduler tiap jam selama aplikasi terbuka). Postingan
     * yang belum waktunya dilewati, jadi jarak sebenarnya mengikuti umur postingan (3, 12, atau 48 jam).
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<StatsWorker>(1, TimeUnit.HOURS).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/**
 * Mengambil statistik postingan (`FetchPostStats` desktop) dan menyimpannya sebagai catatan bertanggal. Tanpa daftar
 * baris: pembaruan berkala untuk postingan yang sudah waktunya (`StatsRefresher`). Kegagalan dicatat di baris akun
 * tujuan supaya tampil di layar.
 */
class StatsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as ClipMiApp).container

    override suspend fun doWork(): Result {
        val dao = container.database.schedules()
        val rows = inputData.getLongArray(StatsQueue.KEY_ROWS)?.toList()?.distinct() ?: run {
            val now = Wall.now()
            dao.refreshCandidates(Wall.millis(PostStats.refreshFrom(now)))
                .filter { PostStats.isDue(Wall.of(it.publishedAt ?: return@filter false), it.statsCheckedAt?.let(Wall::of), now) }
                .map { it.id }
        }
        val fetcher = PostStatsFetcher(container.settings, container.ytDlp)
        for (id in rows) {
            if (isStopped) break
            val row = dao.target(id) ?: continue
            val url = row.url ?: continue
            val now = Wall.millis(Wall.now())
            try {
                val (numbers, source) = fetcher.fetch(url)
                dao.insertStat(PostStatEntity(scheduleAccountId = row.id, views = numbers.views, likes = numbers.likes, comments = numbers.comments, shares = numbers.shares, source = source, recordedAt = now))
                dao.setStatsChecked(row.id, now, null)
            } catch (e: StatsException) {
                dao.setStatsChecked(row.id, now, e.message)
            }
        }
        return Result.success()
    }
}

/**
 * Membaca statistik publik sebuah postingan dari link-nya (`PostStatsFetcher` desktop): YouTube lewat YouTube Data API
 * bila API key diisi (resmi, tanpa login), selain itu lewat yt-dlp. Instagram dan Facebook biasanya menolak tanpa
 * login; isian manual tetap tersedia.
 */
class PostStatsFetcher(private val settings: Settings, private val ytDlp: YtDlp) {
    suspend fun fetch(url: String): Pair<StatNumbers, String> {
        val videoId = PostStats.youtubeApiVideoId(url)
        val key = settings.youtubeKey()
        if (videoId != null && key != null) return fromYouTubeApi(videoId, key) to StatSource.YOUTUBE_API

        val stats = try {
            ytDlp.stats(url)
        } catch (e: YtDlpException) {
            throw StatsException(PostStats.explain(url, e.message.orEmpty()))
        }
        if (stats.isEmpty) throw StatsException(PostStats.NO_NUMBERS)
        return stats to StatSource.YTDLP
    }

    private suspend fun fromYouTubeApi(videoId: String, key: String): StatNumbers = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "statistics").addQueryParameter("id", videoId).build()
        // Key lewat header, bukan query, supaya tidak ikut tercatat di log URL.
        val request = Request.Builder().url(url).header("X-Goog-Api-Key", key).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { Json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull()
                PostStats.parseYouTubeApi(response.code, body)
            }
        } catch (e: IOException) {
            throw StatsException("Tidak bisa terhubung ke YouTube. Periksa koneksi internet.")
        }
    }

    companion object {
        private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}
