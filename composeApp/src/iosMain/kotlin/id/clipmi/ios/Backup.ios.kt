package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.VideoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSISO8601DateFormatter

class BackupException(message: String) : Exception(message)

/**
 * Cadangkan & Pulihkan di iPhone, format yang sama dengan `Backup` Android (`clipmi-android-backup`): ZIP berisi
 * `database/clipmi.db`, `workspace/…` (folder Documents iOS = folder workspace Android), opsional `renders/<id>.mp4`,
 * dan `clipmi-backup.json`. Jadi cadangan dari HP Android bisa dipulihkan di iPhone dan sebaliknya. Tidak ikut: API key
 * dan video sumber (diunduh ulang dari project).
 *
 * Database tidak bisa diganti selagi terbuka, jadi pemulihan disiapkan di folder sementara lalu diterapkan saat
 * aplikasi dibuka lagi ([applyPending], sebelum database dibuka).
 */
class IosBackup(private val repo: Repository) {
    data class Summary(
        val createdAt: String,
        val appVersion: String,
        val dbVersion: Int,
        val counts: Map<String, Int>,
        val files: Int,
        val renders: Int,
    )

    private val documents = repo.documents
    private val db get() = repo.db

    fun fileName(): String = "ClipMI-cadangan-${NSDateFormatter().apply { dateFormat = "yyyy-MM-dd" }.stringFromDate(NSDate())}.zip"

    fun tableLabel(table: String) = TABLES[table] ?: table

    private suspend fun dbVersion(): Int = db.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA user_version") { statement -> if (statement.step()) statement.getLong(0).toInt() else 0 }
    }

    private suspend fun counts(): Map<String, Int> = TABLES.keys.associateWith { table ->
        runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared("SELECT COUNT(*) FROM `$table`") { statement -> if (statement.step()) statement.getLong(0).toInt() else 0 }
            }
        }.getOrDefault(0)
    }

    /** Membuat cadangan di Documents; [progress] menerima teks langkah. @return path ZIP dan ringkasannya. */
    suspend fun export(includeRenders: Boolean, progress: (String) -> Unit): Pair<String, Summary> {
        val tmp = "$documents/$TMP"
        removeItem(tmp)
        createDirectory(tmp)
        try {
            progress("Menyalin database…")
            // Salinan utuh dan konsisten (termasuk isi WAL) tanpa menutup database.
            val copy = "$tmp/${AppDatabase.NAME}"
            db.useWriterConnection { connection ->
                connection.usePrepared("VACUUM INTO '${copy.replace("'", "''")}'") { it.step() }
            }
            val counts = counts()
            val version = dbVersion()
            val files = backupFiles()
            val renders = if (includeRenders) renderFiles() else emptyList()
            val target = "$documents/${fileName()}"
            removeItem(target)
            val summary = Summary(
                NSISO8601DateFormatter().stringFromDate(NSDate()), "ios-" + appVersion(), version, counts, files.size, renders.size,
            )
            withContext(Dispatchers.Default) {
                val zip = ZipWriter(target)
                try {
                    zip.add("database/${AppDatabase.NAME}", copy, compress = true)
                    files.forEachIndexed { i, rel ->
                        if (i % 20 == 0) withContext(Dispatchers.Main) { progress("Menyalin berkas ${i + 1} dari ${files.size}…") }
                        zip.add("workspace/$rel", "$documents/$rel", compress = compressible(rel))
                    }
                    renders.forEachIndexed { i, (id, rel) ->
                        withContext(Dispatchers.Main) { progress("Menyalin video hasil ${i + 1} dari ${renders.size}…") }
                        zip.add("renders/$id.mp4", "$documents/$rel", compress = false)
                    }
                    writeText("$tmp/$MANIFEST", manifest(summary).toString())
                    zip.add(MANIFEST, "$tmp/$MANIFEST", compress = true)
                } finally {
                    zip.close()
                }
            }
            return target to summary
        } catch (e: Exception) {
            removeItem("$documents/${fileName()}")
            throw e
        } finally {
            removeItem(tmp)
        }
    }

    /** Berkas Documents yang dicadangkan: semua kecuali database, video sumber, hasil render, dan berkas sementara. */
    private suspend fun backupFiles(): List<String> {
        val sources = db.projects().all().flatMap { listOfNotNull(it.videoPath, it.previewPath) }.toSet()
        val youtube = Regex("^projects/\\d+/youtube/")
        return allFiles(documents).filter { rel ->
            rel !in sources &&
                !rel.startsWith(AppDatabase.NAME) && rel != "crash.txt" && rel != RESTORED_FLAG &&
                !(rel.endsWith(".zip") && !rel.contains('/')) &&
                SKIPPED.none { rel.startsWith(it) } &&
                !youtube.containsMatchIn(rel) && !rel.endsWith(".part") && !rel.endsWith(".ytdl") && !rel.contains("/work/")
        }
    }

    /** Video hasil render yang masih ada di iPhone: (id klip, path relatif). */
    private suspend fun renderFiles(): List<Pair<Long, String>> =
        db.projects().all().flatMap { db.clips().forProject(it.id) }.mapNotNull { clip ->
            val rel = clip.outputPath?.takeIf { !it.contains("://") } ?: return@mapNotNull null
            if (fileSize("$documents/$rel") > 0) clip.id to rel else null
        }

    private fun compressible(rel: String): Boolean = rel.substringAfterLast('.', "").lowercase() !in STORED

    /** Membaca ringkasan cadangan di [path] tanpa mengubah apa pun. */
    suspend fun inspect(path: String): Summary = withContext(Dispatchers.Default) {
        val reader = runCatching { ZipReader(path) }.getOrElse { throw BackupException("File ini bukan cadangan ClipMI.") }
        val entry = reader.entries.firstOrNull { it.name == MANIFEST } ?: throw BackupException("File ini bukan cadangan ClipMI.")
        parseManifest(reader.readText(entry, "$documents/$MANIFEST.tmp"))
    }

    /**
     * Menyiapkan pemulihan dari [path]: isi cadangan diekstrak ke folder sementara. Data baru dipakai setelah aplikasi
     * ditutup dan dibuka lagi ([applyPending]).
     */
    suspend fun stage(path: String, progress: (String) -> Unit): Summary {
        val summary = inspect(path)
        if (summary.dbVersion > dbVersion()) throw BackupException("Cadangan ini dibuat oleh ClipMI versi yang lebih baru. Perbarui aplikasi dulu.")
        val staging = "$documents/$STAGING"
        removeItem(staging)
        createDirectory(staging)
        try {
            withContext(Dispatchers.Default) {
                val reader = ZipReader(path)
                val files = reader.entries.filter { !it.isDirectory }
                files.forEachIndexed { i, entry ->
                    if (i % 20 == 0) withContext(Dispatchers.Main) { progress("Membaca berkas ${i + 1} dari ${files.size}…") }
                    val name = entry.name.trimStart('/')
                    // Nama berkas di ZIP tidak boleh keluar dari folder sementara.
                    if (name.isEmpty() || name.split('/').any { it == ".." } || name.contains('\\')) throw BackupException("File cadangan rusak.")
                    val target = "$staging/$name"
                    createDirectory(target.substringBeforeLast('/'))
                    reader.extract(entry, target)
                }
            }
            if (fileSize("$staging/database/${AppDatabase.NAME}") <= 0) throw BackupException("Database di cadangan tidak ditemukan.")
            writeText("$staging/$READY", summary.createdAt.ifEmpty { "?" })
            return summary
        } catch (e: Exception) {
            removeItem(staging)
            throw e
        }
    }

    private fun appVersion(): String = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"

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
            dbVersion = runCatching { o["db_version"]?.jsonPrimitive?.int }.getOrNull() ?: 0,
            counts = runCatching { o["counts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int } }.getOrNull().orEmpty(),
            files = runCatching { o["files"]?.jsonPrimitive?.int }.getOrNull() ?: 0,
            renders = runCatching { o["renders"]?.jsonPrimitive?.int }.getOrNull() ?: 0,
        )
    }

    /**
     * Setelah pulih (database sudah terbuka): video sumber yang tidak ikut ditandai dihapus (bisa diunduh ulang), dan
     * hasil render dari cadangan dipasang ke klipnya; hasil render yang filenya tidak ada dikosongkan.
     */
    suspend fun finishRestore(): String? {
        val flag = "$documents/$RESTORED_FLAG"
        if (fileSize(flag) <= 0) return null
        removeItem(flag)
        val now = nowMillis()
        var sources = 0
        var renders = 0
        for (project in db.projects().all()) {
            val missing = project.videoPath != null && fileSize("$documents/${project.videoPath}") <= 0
            if (missing || project.video == VideoStatus.Ready && project.videoPath == null) {
                db.projects().update(project.copy(videoStatus = VideoStatus.Deleted.value, videoPath = null, previewPath = null, sourceDeletedAt = now, updatedAt = now))
                sources++
            }
            for (clip in db.clips().forProject(project.id)) {
                val output = clip.outputPath ?: continue
                val restored = "renders/${clip.id}.mp4"
                val next = when {
                    fileSize("$documents/$restored") > 0 -> restored.also { if (output != restored) renders++ }
                    !output.contains("://") && fileSize("$documents/$output") > 0 -> output
                    else -> null
                }
                if (next != output) db.clips().update(clip.copy(outputPath = next, updatedAt = now))
            }
        }
        return "Cadangan dipulihkan." +
            (if (renders > 0) " $renders video hasil dikembalikan." else "") +
            (if (sources > 0) " $sources project perlu video sumbernya diunduh ulang." else "")
    }

    companion object {
        const val FORMAT = "clipmi-android-backup"
        const val MANIFEST = "clipmi-backup.json"
        private const val TMP = "backup-tmp"
        private const val STAGING = "restore-staging"
        private const val READY = "ready"
        private const val RESTORED_FLAG = "restored.flag"

        /** Folder kerja sementara yang tidak dicadangkan. */
        private val SKIPPED = listOf("$TMP/", "$STAGING/", "render-", "cover-", "speaker/", "silence/", "renders/", "tmp/", "yt-dlp-cache/", "stats.log")

        /** Ekstensi yang sudah terkompresi: disimpan apa adanya di ZIP. */
        private val STORED = setOf("mp4", "mov", "m4a", "mp3", "jpg", "jpeg", "png", "webp", "heic", "zip", "gif")

        /** Tabel yang dihitung di ringkasan cadangan, dengan labelnya (sama dengan Android). */
        private val TABLES = linkedMapOf(
            "projects" to "Project", "clips" to "Klip", "schedules" to "Jadwal", "social_accounts" to "Akun sosmed",
            "post_stats" to "Catatan statistik", "caption_styles" to "Gaya subtitle", "frame_styles" to "Gaya bingkai",
            "correction_entries" to "Kamus koreksi", "performance_reports" to "Laporan analisis",
        )

        /**
         * Dipanggil sebelum database dibuka: bila ada pemulihan yang sudah disiapkan, isi Documents diganti isi cadangan
         * (database dan berkas); file ZIP di folder utama dan crash.txt tidak disentuh.
         */
        fun applyPending(documents: String) {
            val staging = "$documents/$STAGING"
            if (listDirectory(documents).none { it == STAGING }) return
            val ready = readText("$staging/$READY")
            if (ready == null) {
                // Persiapan pemulihan terputus: dibuang, data lama tetap.
                removeItem(staging)
                return
            }
            for (name in listDirectory(documents)) {
                if (name == STAGING || name == "crash.txt" || name.endsWith(".zip")) continue
                removeItem("$documents/$name")
            }
            for (name in listDirectory("$staging/workspace")) moveItem("$staging/workspace/$name", "$documents/$name")
            moveItem("$staging/database/${AppDatabase.NAME}", "$documents/${AppDatabase.NAME}")
            if (listDirectory(staging).any { it == "renders" }) moveItem("$staging/renders", "$documents/renders")
            writeText("$documents/$RESTORED_FLAG", ready)
            removeItem(staging)
        }
    }
}

actual suspend fun finishRestore(repo: Repository): String? = runCatching { IosBackup(repo).finishRestore() }.getOrNull()

private val BackupDanger = Color(0xFFE5484D)

@Composable
actual fun BackupScreen(repo: Repository, engine: MediaEngine, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val backup = remember { IosBackup(repo) }
    var includeRenders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<Pair<String, IosBackup.Summary>?>(null) }
    var picked by remember { mutableStateOf<Pair<String, IosBackup.Summary>?>(null) }
    var staged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "‹ Pengaturan", color = c.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = busy == null, onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
        item { Text("Cadangkan & pulihkan", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, color = c.onBackground) }
        item {
            Text(
                "Satu file ZIP berisi project, transkrip, klip, gaya, kamus, jadwal, dan pengaturan. Formatnya sama dengan " +
                    "ClipMI Android, jadi bisa dipindah antara HP Android dan iPhone. API key dan video sumber tidak ikut.",
                fontSize = 13.sp, color = c.onBackground.copy(alpha = 0.65f),
            )
        }
        busy?.let { item { Text(it, fontSize = 13.sp, color = c.primary) } }
        error?.let { item { Text(it, fontSize = 13.sp, color = BackupDanger) } }
        item {
            BackupCard("Cadangkan") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sertakan video hasil render", color = c.onSurface)
                        Text("File jadi jauh lebih besar.", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f))
                    }
                    Switch(includeRenders, { includeRenders = it }, enabled = busy == null)
                }
                Button(
                    onClick = {
                        error = null
                        made = null
                        busy = "Menyiapkan cadangan…"
                        scope.launch {
                            runCatching { backup.export(includeRenders) { busy = it } }
                                .onSuccess { made = it }
                                .onFailure { error = "Cadangan gagal: ${it.message}" }
                            busy = null
                        }
                    },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary),
                ) { Text("Buat cadangan") }
                made?.let { (path, summary) ->
                    Text("Cadangan siap: ${path.substringAfterLast('/')}", fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    SummaryText(backup, summary)
                    OutlinedButton(onClick = { engine.shareFile(path) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Simpan atau bagikan…")
                    }
                    Text("File ini juga ada di aplikasi Files › Di iPhone Saya › ClipMI.", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.55f))
                }
            }
        }
        item {
            BackupCard("Pulihkan") {
                if (staged) {
                    Text("Pemulihan siap. Tutup ClipMI lalu buka lagi untuk memakai data dari cadangan.", fontWeight = FontWeight.SemiBold, color = c.primary)
                    Button(
                        onClick = { platform.posix.exit(0) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary),
                    ) { Text("Tutup aplikasi sekarang") }
                    return@BackupCard
                }
                Text("Data di iPhone ini akan diganti seluruhnya dengan isi cadangan.", fontSize = 13.sp, color = c.onSurface.copy(alpha = 0.7f))
                OutlinedButton(
                    onClick = {
                        error = null
                        picked = null
                        val target = repo.absolute("restore-source.zip")
                        engine.pickFile(
                            target,
                            object : PickListener {
                                override fun finished(path: String?) {
                                    if (path == null) return
                                    busy = "Membaca cadangan…"
                                    scope.launch {
                                        runCatching { backup.inspect(path) }
                                            .onSuccess { picked = path to it }
                                            .onFailure { error = it.message ?: "File cadangan tidak bisa dibaca."; removeItem(path) }
                                        busy = null
                                    }
                                }
                            },
                        )
                    },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                ) { Text("Pilih file cadangan…") }
                picked?.let { (path, summary) ->
                    SummaryText(backup, summary)
                    Button(
                        onClick = {
                            error = null
                            busy = "Menyiapkan pemulihan…"
                            scope.launch {
                                runCatching { backup.stage(path) { busy = it } }
                                    .onSuccess { staged = true; removeItem(path) }
                                    .onFailure { error = "Pemulihan gagal: ${it.message}" }
                                busy = null
                            }
                        },
                        enabled = busy == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BackupDanger),
                    ) { Text("Ganti data dengan cadangan ini") }
                }
            }
        }
    }
}

@Composable
private fun BackupCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun SummaryText(backup: IosBackup, summary: IosBackup.Summary) {
    val c = MaterialTheme.colorScheme
    val counts = summary.counts.filterValues { it > 0 }.entries.joinToString(" · ") { (table, count) -> "$count ${backup.tableLabel(table).lowercase()}" }
    Text(
        listOfNotNull(
            "Dibuat ${summary.createdAt.take(16).replace('T', ' ')} (ClipMI ${summary.appVersion})",
            counts.ifEmpty { null },
            "${summary.files} berkas" + if (summary.renders > 0) " · ${summary.renders} video hasil" else "",
        ).joinToString("\n"),
        fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.7f),
    )
}
