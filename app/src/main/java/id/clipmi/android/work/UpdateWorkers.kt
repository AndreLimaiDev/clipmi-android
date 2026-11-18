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

/** Pemeriksaan pembaruan harian dan unduhan APK di latar. */
object UpdateQueue {
    private const val PERIODIC = "update-check"
    const val DOWNLOAD = "update-download"
    const val KEY_RELEASE = "release"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR = "error"

    /** Cek sekali sehari, hanya lewat Wi-Fi (jaringan tanpa kuota). */
    fun schedulePeriodic(context: Context) {
        if (!AppUpdater(context).enabled) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Unduh APK [release] di latar (jaringan apa pun yang tersambung; pengguna yang memintanya). */
    fun download(context: Context, release: AppUpdater.Release) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf(KEY_RELEASE to Json.encodeToString(AppUpdater.Release.serializer(), release)))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.KEEP, request)
    }
}

/** Cek rilis terbaru; bila ada versi baru yang belum dilewati, notifikasi sekali per versi. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ClipMiApp).container
        val release = try {
            AppUpdater(applicationContext).check()
        } catch (e: UpdateException) {
            return Result.success()
        }
        container.updates.saveCheck(release)
        if (release != null && container.updates.markNotified(release.version)) {
            Updates.notify(applicationContext, "ClipMI ${release.version} tersedia", "Ketuk untuk melihat yang baru dan memperbarui.")
        }
        return Result.success()
    }
}

/** Unduh dan periksa APK pembaruan dengan progres di notifikasi; selesai = notifikasi "siap dipasang". */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = inputData.getString(UpdateQueue.KEY_RELEASE)?.let { Json.decodeFromString(AppUpdater.Release.serializer(), it) } ?: return Result.failure()
        val title = "Mengunduh ClipMI ${release.version}"
        setForeground(foregroundInfo(applicationContext, WorkQueue.UPDATE, title, "Mulai…", null))
        var last = -1
        return try {
            AppUpdater(applicationContext).download(release) { p ->
                if (p != null && p != last) {
                    last = p
                    setProgressAsync(workDataOf(UpdateQueue.KEY_PROGRESS to p))
                    runCatching { setForegroundAsync(foregroundInfo(applicationContext, WorkQueue.UPDATE, title, "$p%", p)) }
                }
            }
            Updates.notify(applicationContext, "ClipMI ${release.version} siap dipasang", "Ketuk untuk memasang pembaruan.")
            Result.success()
        } catch (e: UpdateException) {
            Result.failure(workDataOf(UpdateQueue.KEY_ERROR to e.message))
        }
    }
}
