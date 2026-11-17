package id.clipmi.android.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import id.clipmi.android.ClipMiApp

/**
 * Antrean kerja seperti desktop: satu antrean berurutan per jenis (unduh, AI, render). Setiap antrean adalah rantai
 * unique work WorkManager (APPEND_OR_REPLACE), jadi pekerjaan dalam satu antrean jalan satu per satu, tetap jalan saat
 * aplikasi ditutup, dan dilanjutkan sistem setelah proses dimatikan.
 */
enum class WorkQueue(val uniqueName: String, val notificationId: Int) {
    DOWNLOAD("queue-download", 1001),
    AI("queue-ai", 1002),
    RENDER("queue-render", 1003),
    LAB("queue-lab", 1009);

    /** Tipe foreground service: render memakai mediaProcessing (Android 15+), sisanya dataSync. */
    val serviceType: Int
        get() = if (this == RENDER && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    fun enqueue(context: Context, request: OneTimeWorkRequest) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Notifikasi progres untuk foreground service; progress null = tak tentu. */
fun foregroundInfo(context: Context, queue: WorkQueue, title: String, text: String, progress: Int?): ForegroundInfo {
    val notification = NotificationCompat.Builder(context, ClipMiApp.CHANNEL_WORK)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, progress ?: 0, progress == null)
        .build()
    return ForegroundInfo(queue.notificationId, notification, queue.serviceType)
}
