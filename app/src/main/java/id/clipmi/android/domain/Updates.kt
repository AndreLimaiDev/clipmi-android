package id.clipmi.android.domain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.clipmi.android.BuildConfig
import id.clipmi.android.ClipMiApp
import id.clipmi.android.MainActivity
import id.clipmi.android.data.SettingDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

/**
 * Status pembaruan aplikasi yang diingat di HP: rilis terbaru yang ditemukan (beserta catatannya), versi yang
 * dilewati pengguna ("Nanti"), waktu terakhir dicek, dan versi terakhir yang catatan "yang baru"-nya sudah dilihat.
 */
class Updates(private val dao: SettingDao) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Rilis lebih baru dari yang terpasang dan belum dilewati pengguna; null bila tidak ada. */
    val available: Flow<AppUpdater.Release?> = combine(dao.observe(KEY_LATEST), dao.observe(KEY_DISMISSED)) { latest, dismissed ->
        decode(latest)?.takeIf { AppUpdater.compare(it.version, BuildConfig.VERSION_NAME) > 0 && it.version != dismissed }
    }

    /** Rilis terbaru yang diketahui (termasuk yang dilewati), untuk Pengaturan. */
    val latest: Flow<AppUpdater.Release?> = combine(dao.observe(KEY_LATEST), dao.observe(KEY_CHECKED)) { latest, _ ->
        decode(latest)?.takeIf { AppUpdater.compare(it.version, BuildConfig.VERSION_NAME) > 0 }
    }

    val checkedAt: Flow<String?> = dao.observe(KEY_CHECKED)

    suspend fun saveCheck(release: AppUpdater.Release?) {
        // Sudah terbaru: rilis yang tersimpan tetap dipakai untuk catatan "yang baru" bila sama dengan versi terpasang.
        if (release != null) dao.put(KEY_LATEST, json.encodeToString(AppUpdater.Release.serializer(), release))
        dao.put(KEY_CHECKED, System.currentTimeMillis().toString())
    }

    suspend fun dismiss(version: String) = dao.put(KEY_DISMISSED, version)

    suspend fun isDismissed(version: String): Boolean = dao.get(KEY_DISMISSED) == version

    /**
     * Lembar "siap dipasang" untuk [version] belum pernah dibuka otomatis; menandainya sekaligus, jadi lembar itu hanya
     * muncul sendiri sekali per versi (setelahnya lewat kartu di Project atau Pengaturan).
     */
    suspend fun markPrompted(version: String): Boolean {
        if (dao.get(KEY_PROMPTED) == version) return false
        dao.put(KEY_PROMPTED, version)
        return true
    }

    /**
     * Catatan rilis versi yang baru terpasang, sekali saja setelah pembaruan; null bila sudah dilihat atau tidak ada.
     * Pemasangan pertama hanya menandai versi ini sebagai sudah dilihat.
     */
    suspend fun takeWhatsNew(): AppUpdater.Release? {
        val seen = dao.get(KEY_SEEN)
        if (seen == BuildConfig.VERSION_NAME) return null
        dao.put(KEY_SEEN, BuildConfig.VERSION_NAME)
        if (seen == null) return null
        return decode(dao.get(KEY_LATEST))?.takeIf { it.version == BuildConfig.VERSION_NAME && it.notes.isNotBlank() }
    }

    private fun decode(value: String?): AppUpdater.Release? =
        value?.let { runCatching { json.decodeFromString(AppUpdater.Release.serializer(), it) }.getOrNull() }

    companion object {
        private const val KEY_LATEST = "update.latest"
        private const val KEY_DISMISSED = "update.dismissed"
        private const val KEY_CHECKED = "update.checked_at"
        private const val KEY_PROMPTED = "update.prompted"
        private const val KEY_SEEN = "update.seen_version"

        const val NOTIFICATION_ID = 1005

        /** Notifikasi "versi baru tersedia" atau "siap dipasang"; ketuk membuka lembar pembaruan di aplikasi. */
        fun notify(context: Context, title: String, text: String) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, ClipMiApp.CHANNEL_UPDATE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        }
    }
}
