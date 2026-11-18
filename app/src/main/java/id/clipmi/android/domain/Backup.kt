package id.clipmi.android.domain

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.work.WorkManager
import id.clipmi.android.BuildConfig
import id.clipmi.android.MainActivity
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.VideoStatus
import id.clipmi.core.analysis.ClipText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

/**
 * Cadangkan & Pulihkan (Fase 5): karena data hanya ada di HP, pengguna menyimpannya sendiri ke satu file ZIP di
 * penyimpanan HP atau Google Drive (lewat pemilih file sistem), lalu memulihkannya di HP yang sama atau HP baru.
 *
 * Isi: database (project, transkrip, klip, gaya, kamus, jadwal, statistik, laporan, pengaturan), gambar kecil, sampul,
 * gambar gaya, dan intro/outro, opsional video hasil render. Tidak ikut: API key (Keystore, tidak bisa dipindah) dan
 * video sumber (besar; diunduh atau dipilih ulang dari detail project).
 */
class Backup(private val context: Context, private val db: AppDatabase, private val workspace: Workspace) {
    data class Summary(
        val createdAt: String,
        val appVersion: String,
        val dbVersion: Int,
        val counts: Map<String, Int>,
        val files: Int,
        val renders: Int,
    )

    fun fileName(): String = "ClipMI-cadangan-${java.time.LocalDate.now()}.zip"

    /** Tabel yang dihitung di ringkasan cadangan, dengan labelnya. */
    private val TABLES = linkedMapOf(
        "projects" to "Project", "clips" to "Klip", "schedules" to "Jadwal", "social_accounts" to "Akun sosmed",
        "post_stats" to "Catatan statistik", "caption_styles" to "Gaya subtitle", "frame_styles" to "Gaya bingkai",
        "correction_entries" to "Kamus koreksi", "performance_reports" to "Laporan analisis",
    )

    fun tableLabel(table: String) = TABLES[table] ?: table

    /**
     * Menulis cadangan ke [uri]. Video hasil render ikut bila [includeRenders]. [progress] menerima teks langkah.
     */
    suspend fun export(uri: Uri, includeRenders: Boolean, progress: (String) -> Unit = {}): Summary = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // WAL ditulis ke file database dulu supaya salinannya lengkap.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        val counts = counts(dbFile)
        val files = backupFiles()
        val renders = if (includeRenders) db.clips().schedulable().filter { it.outputPath != null } else emptyList()
        var renderCount = 0

        resolver.openOutputStream(uri, "wt")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                progress("Menyalin database…")
                zip.putNextEntry(ZipEntry("database/${AppDatabase.NAME}"))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files.forEachIndexed { i, file ->
                    if (i % 20 == 0) progress("Menyalin berkas ${i + 1} dari ${files.size}…")
                    zip.putNextEntry(ZipEntry("workspace/" + workspace.relative(file)))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                renders.forEachIndexed { i, clip ->
                    progress("Menyalin video hasil ${i + 1} dari ${renders.size}…")
                    val input = runCatching { resolver.openInputStream(Uri.parse(clip.outputPath)) }.getOrNull() ?: return@forEachIndexed
                    input.use {
                        zip.putNextEntry(ZipEntry("renders/${clip.id}.mp4"))
                        it.copyTo(zip)
                        zip.closeEntry()
                    }
                    renderCount++
                }
                val summary = Summary(java.time.OffsetDateTime.now().withNano(0).toString(), BuildConfig.VERSION_NAME, db.openHelper.readableDatabase.version, counts, files.size, renderCount)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest(summary).toString().toByteArray())
                zip.closeEntry()
                return@withContext summary
            }
        } ?: throw BackupException("File tujuan tidak bisa ditulis.")
    }

    /** Berkas workspace yang dicadangkan: semua kecuali video sumber, pratinjau, info YouTube, dan berkas sementara. */
    private suspend fun backupFiles(): List<File> {
        val sources = db.projects().all().flatMap { listOfNotNull(it.videoPath, it.previewPath) }.mapNotNull(workspace::file).map { it.absolutePath }.toSet()
        return workspace.root.walkTopDown().filter { it.isFile }.filter { file ->
            val rel = workspace.relative(file)
            file.absolutePath !in sources && !rel.startsWith("tmp/") && !Regex("^projects/\\d+/youtube/").containsMatchIn(rel) &&
                !rel.endsWith(".part") && !rel.contains("/work/")
        }.toList()
    }

    /** Membaca ringkasan cadangan di [uri] tanpa mengubah apa pun (pratinjau sebelum ditimpa). */
    suspend fun inspect(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: throw BackupException("File cadangan tidak bisa dibuka.")
        stream.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (entry.name == MANIFEST) return@withContext parseManifest(zip.readBytes().decodeToString())
                }
            }
        }
        throw BackupException("File ini bukan cadangan ClipMI.")
    }

    /**
     * Memulihkan cadangan: database dan berkas di HP ini diganti isi cadangan, lalu aplikasi dibuka ulang. Video hasil
     * render didaftarkan lagi ke galeri saat aplikasi terbuka ([finishRestore]).
     */
    suspend fun restore(uri: Uri, progress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val summary = inspect(uri)
        if (summary.dbVersion > db.openHelper.readableDatabase.version) throw BackupException("Cadangan ini dibuat oleh ClipMI versi yang lebih baru. Perbarui aplikasi dulu.")

        val staging = File(context.filesDir, "restore").apply { deleteRecursively(); mkdirs() }
        progress("Membaca cadangan…")
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val target = File(staging, entry.name).canonicalFile
                    // Nama berkas di ZIP tidak boleh keluar dari folder sementara.
                    if (!target.path.startsWith(staging.canonicalPath + File.separator)) throw BackupException("File cadangan rusak.")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        } ?: throw BackupException("File cadangan tidak bisa dibuka.")
        val restoredDb = File(staging, "database/${AppDatabase.NAME}")
        if (!restoredDb.isFile) throw BackupException("Database di cadangan tidak ditemukan.")

        progress("Mengganti data…")
        WorkManager.getInstance(context).cancelAllWork().result.get()
        db.close()
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        restoredDb.copyTo(dbFile, overwrite = true)
        workspace.root.deleteRecursively()
        File(staging, "workspace").takeIf { it.isDirectory }?.copyRecursively(workspace.root, overwrite = true)
        workspace.root.mkdirs()
        File(staging, "renders").takeIf { it.isDirectory }?.renameTo(File(context.filesDir, PENDING_RENDERS))
        staging.deleteRecursively()
        File(context.filesDir, RESTORED_FLAG).writeText(summary.createdAt)

        // Aplikasi dibuka ulang supaya database dibaca dari awal.
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /**
     * Langkah setelah pulih (dipanggil saat aplikasi dibuka): video sumber yang tidak ikut ditandai dihapus (bisa diunduh
     * atau dipilih ulang), dan video hasil render dari cadangan didaftarkan lagi ke galeri.
     * @return pesan untuk pengguna, atau null bila tidak ada pemulihan.
     */
    suspend fun finishRestore(): String? = withContext(Dispatchers.IO) {
        val flag = File(context.filesDir, RESTORED_FLAG)
        if (!flag.isFile) return@withContext null
        val now = System.currentTimeMillis()
        var sources = 0
        for (project in db.projects().all()) {
            val missing = project.videoPath != null && workspace.file(project.videoPath)?.isFile != true
            if (missing || project.video == VideoStatus.Ready && project.videoPath == null) {
                db.projects().update(project.copy(videoStatus = VideoStatus.Deleted.value, videoPath = null, previewPath = null, sourceDeletedAt = now, updatedAt = now))
                sources++
            }
        }
        val renders = File(context.filesDir, PENDING_RENDERS)
        var registered = 0
        renders.listFiles()?.forEach { file ->
            val clip = file.nameWithoutExtension.toLongOrNull()?.let { db.clips().find(it) }
            val project = clip?.let { db.projects().find(it.projectId) }
            if (clip != null && project != null) {
                runCatching { publish(file, clip.id, ClipText.displayTitle(clip.title, clip.partNumber), project.title) }.getOrNull()?.let { uri ->
                    db.clips().update(clip.copy(outputPath = uri.toString()))
                    registered++
                }
            }
            file.delete()
        }
        renders.deleteRecursively()
        // Klip yang hasil render-nya tidak ikut: file galeri lamanya (di HP lain) tidak ada, jadi tampil "File hilang".
        flag.delete()
        "Cadangan dipulihkan." +
            (if (registered > 0) " $registered video hasil dikembalikan ke galeri." else "") +
            (if (sources > 0) " $sources project perlu video sumbernya diunduh atau dipilih ulang dari detail project." else "")
    }

    private fun publish(file: File, clipId: Long, title: String, project: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, String.format(Locale.ROOT, "%03d - %s.mp4", clipId, Renderer.safeName(title, 100)))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipMI/" + Renderer.safeName(project, 80))
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("galeri menolak")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun counts(dbFile: File): Map<String, Int> =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            TABLES.keys.associateWith { table -> runCatching { sql.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }.getOrDefault(0) }
        }

    private fun manifest(s: Summary): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", 1)
        put("created_at", s.createdAt)
        put("app_version", s.appVersion)
        put("db_version", s.dbVersion)
        put("counts", buildJsonObject { s.counts.forEach { (k, v) -> put(k, v) } })
        put("files", s.files)
        put("renders", s.renders)
    }

    private fun parseManifest(text: String): Summary {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (o == null || o["format"]?.jsonPrimitive?.content != FORMAT) throw BackupException("File ini bukan cadangan ClipMI.")
        return Summary(
            createdAt = o["created_at"]?.jsonPrimitive?.content.orEmpty(),
            appVersion = o["app_version"]?.jsonPrimitive?.content.orEmpty(),
            dbVersion = o["db_version"]?.jsonPrimitive?.int ?: 0,
            counts = o["counts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }.orEmpty(),
            files = o["files"]?.jsonPrimitive?.int ?: 0,
            renders = o["renders"]?.jsonPrimitive?.int ?: 0,
        )
    }

    companion object {
        const val FORMAT = "clipmi-android-backup"
        const val MANIFEST = "clipmi-backup.json"
        private const val RESTORED_FLAG = "restored.flag"
        private const val PENDING_RENDERS = "restore-renders"
    }
}
