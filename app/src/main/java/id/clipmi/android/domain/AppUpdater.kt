package id.clipmi.android.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import id.clipmi.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateException(message: String) : Exception(message)

/**
 * Pembaruan dalam aplikasi tanpa Play Store: rilis terbaru di GitHub (repo dari `-Pclipmi.updateRepo`) dibandingkan
 * dengan versi terpasang, APK-nya diunduh, diperiksa (nama paket, versi lebih baru, kunci tanda tangan sama, dan
 * SHA-256 bila tertulis di catatan rilis), lalu pemasangan diminta ke sistem. Pengguna tetap mengetuk "Pasang".
 */
class AppUpdater(private val context: Context) {
    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long?, val sha256: String?)

    val repo: String get() = BuildConfig.UPDATE_REPO
    val enabled: Boolean get() = repo.isNotBlank()
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Rilis terbaru bila lebih baru dari versi terpasang, atau null bila sudah terbaru. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        if (!enabled) throw UpdateException("Pembaruan dalam aplikasi belum aktif di build ini.")
        val request = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "ClipMI-Android").build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) throw UpdateException("Belum ada rilis di GitHub.")
                if (!response.isSuccessful) throw UpdateException("GitHub menolak permintaan (${response.code}). Coba lagi nanti.")
                response.body.string()
            }
        } catch (e: IOException) {
            throw UpdateException("Tidak bisa terhubung ke GitHub. Periksa koneksi internet.")
        }
        val release = Json.parseToJsonElement(body).jsonObject
        val version = release.str("tag_name").removePrefix("v").removePrefix("V")
        if (compare(version, currentVersion) <= 0) return@withContext null
        val asset = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { it.str("name").endsWith(".apk", ignoreCase = true) }
            ?: throw UpdateException("Rilis $version belum punya file APK.")
        val notes = release.str("body")
        Release(
            version = version,
            notes = notes,
            apkUrl = asset.str("browser_download_url"),
            apkName = asset.str("name"),
            size = (asset["size"] as? JsonPrimitive)?.longOrNull,
            sha256 = Regex("\\b[0-9a-fA-F]{64}\\b").find(notes)?.value?.lowercase(),
        )
    }

    /** Mengunduh dan memeriksa APK rilis; [progress] 0–100 (null bila ukuran tidak diketahui). */
    suspend fun download(release: Release, progress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "ClipMI-${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "ClipMI-Android").build()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("Unduhan gagal (${response.code}).")
                val total = response.body.contentLength().takeIf { it > 0 } ?: release.size
                var done = 0L
                response.body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            progress(total?.let { (done * 100 / it).toInt() })
                        }
                    }
                }
            }
        } catch (e: IOException) {
            file.delete()
            throw UpdateException("Unduhan terputus. Coba lagi.")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && release.sha256 != sha) {
            file.delete()
            throw UpdateException("File APK tidak cocok dengan SHA-256 di catatan rilis. Jangan dipasang; hubungi koordinator tim.")
        }
        verify(file)
        file
    }

    /** APK harus ClipMI, versinya lebih baru, dan ditandatangani kunci yang sama dengan aplikasi terpasang. */
    private fun verify(file: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("File APK rusak.").also { file.delete() }
        if (archive.packageName != context.packageName) throw UpdateException("APK ini bukan ClipMI.").also { file.delete() }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) throw UpdateException("APK ini tidak lebih baru dari yang terpasang.").also { file.delete() }
        val mine = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }.orEmpty().toSet()
        val theirs = archive.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }.orEmpty().toSet()
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) {
            file.delete()
            throw UpdateException("APK ini ditandatangani kunci lain, jadi tidak bisa memperbarui ClipMI yang terpasang.")
        }
    }

    /** Sistem mengizinkan ClipMI meminta pemasangan aplikasi (izin "Pasang aplikasi tidak dikenal"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Halaman pengaturan sistem untuk izin itu; pengguna sendiri yang menyalakannya. */
    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Membuka pemasang sistem untuk [apk]; pengguna mengetuk Pasang/Perbarui. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Bandingkan versi "1.2.10" angka demi angka; bagian bukan angka dianggap 0. */
        fun compare(a: String, b: String): Int {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
