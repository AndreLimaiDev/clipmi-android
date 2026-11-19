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
import id.clipmi.android.domain.AppUpdater
import id.clipmi.android.domain.UpdateException
import id.clipmi.android.domain.Updates
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Pemeriksaan pembaruan (harian dan saat aplikasi dibuka) dan unduhan APK di latar. */
object UpdateQueue {
    private const val PERIODIC = "update-check"
    private const val CHECK_NOW = "update-check-now"
    const val DOWNLOAD = "update-download"
    const val KEY_RELEASE = "release"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR = "error"
    const val KEY_AUTO = "auto"

    /** Cek sekali sehari selama tersambung internet; unduhannya sendiri menunggu Wi-Fi. */
    fun schedulePeriodic(context: Context) {
        if (!AppUpdater(context).enabled) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Cek sekarang (aplikasi baru dibuka): versi baru langsung diunduh di latar lewat Wi-Fi. */
    fun checkNow(context: Context) {
        if (!AppUpdater(context).enabled) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Unduh APK [release] di latar. [auto] (tanpa diminta pengguna): hanya lewat Wi-Fi/jaringan tanpa kuota, dan tidak
     * mengganggu unduhan yang sudah ada. Diminta pengguna: jaringan apa pun, menggantikan unduhan otomatis yang masih
     * menunggu Wi-Fi.
     */
    fun download(context: Context, release: AppUpdater.Release, auto: Boolean = false) {
        val network = if (auto) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf(KEY_RELEASE to Json.encodeToString(AppUpdater.Release.serializer(), release), KEY_AUTO to auto))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, if (auto) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Cek rilis terbaru. Versi baru yang belum dilewati pengguna langsung diunduh di latar (Wi-Fi); pengguna baru diberi
 * tahu setelah APK-nya siap dipasang (notifikasi dan lembar pembaruan saat aplikasi dibuka).
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ClipMiApp).container
        val updater = AppUpdater(applicationContext)
        val release = try {
            updater.check()
        } catch (e: UpdateException) {
            return Result.success()
        }
        container.updates.saveCheck(release)
        if (release != null && !container.updates.isDismissed(release.version) && updater.downloaded(release.version) == null) {
            UpdateQueue.download(applicationContext, release, auto = true)
        }
        return Result.success()
    }
}

/**
 * Unduh dan periksa APK pembaruan; selesai = notifikasi "siap dipasang". Unduhan yang diminta pengguna menampilkan
 * progres di notifikasi; unduhan otomatis berjalan diam-diam di latar.
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = inputData.getString(UpdateQueue.KEY_RELEASE)?.let { Json.decodeFromString(AppUpdater.Release.serializer(), it) } ?: return Result.failure()
        val auto = inputData.getBoolean(UpdateQueue.KEY_AUTO, false)
        val title = "Mengunduh ClipMI ${release.version}"
        if (!auto) setForeground(foregroundInfo(applicationContext, WorkQueue.UPDATE, title, "Mulai…", null))
        var last = -1
        return try {
            AppUpdater(applicationContext).download(release) { p ->
                if (p != null && p != last) {
                    last = p
                    setProgressAsync(workDataOf(UpdateQueue.KEY_PROGRESS to p))
                    if (!auto) runCatching { setForegroundAsync(foregroundInfo(applicationContext, WorkQueue.UPDATE, title, "$p%", p)) }
                }
            }
            Updates.notify(applicationContext, "ClipMI ${release.version} siap dipasang", "Sudah diunduh. Ketuk untuk melihat yang baru dan memasangnya.")
            Result.success()
        } catch (e: UpdateException) {
            Result.failure(workDataOf(UpdateQueue.KEY_ERROR to e.message))
        }
    }
}
