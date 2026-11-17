package id.clipmi.android.lab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.domain.AiProvider
import id.clipmi.android.work.WorkQueue
import id.clipmi.android.work.foregroundInfo

/**
 * Uji coba teknis Fase 0. Dijalankan sebagai pekerjaan foreground di antrean LAB agar tetap jalan saat layar mati,
 * sama seperti pekerjaan berat sungguhan. Semua hasil dicatat di [LabLog].
 *
 * Contoh dari PC:
 *   adb shell am start -n id.clipmi.android/.MainActivity --es lab ytdlp --es url "https://youtu.be/..."
 *   adb shell am start -n id.clipmi.android/.MainActivity --es lab ass
 *   adb shell am start -n id.clipmi.android/.MainActivity --es lab render_media3 --es mode blur --es seconds 60
 */
object LabTests {
    val ALL = listOf("device", "ytdlp_update", "ytdlp", "ass", "render_media3", "render_ffmpeg", "audio_ffmpeg")

    fun enqueue(context: Context, test: String, params: Map<String, String> = emptyMap()) {
        val data = workDataOf("test" to test, *params.map { (k, v) -> "p.$k" to v }.toTypedArray())
        WorkQueue.LAB.enqueue(context, OneTimeWorkRequestBuilder<LabWorker>().setInputData(data).build())
        LabLog(context).line("Diantrekan: $test $params")
    }
}

@androidx.media3.common.util.UnstableApi
class LabWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val log = LabLog(context)

    override suspend fun getForegroundInfo() =
        foregroundInfo(applicationContext, WorkQueue.LAB, "Uji Fase 0", inputData.getString("test").orEmpty(), null)

    override suspend fun doWork(): Result {
        val test = inputData.getString("test") ?: return Result.failure()
        val params = inputData.keyValueMap.filterKeys { it.startsWith("p.") }
            .map { (k, v) -> k.removePrefix("p.") to v.toString() }.toMap()
        val queue = if (test.startsWith("render") || test.startsWith("audio")) WorkQueue.RENDER else WorkQueue.LAB
        setForeground(foregroundInfo(applicationContext, queue, "Uji Fase 0", test, null))

        log.line("=== Mulai $test $params")
        val started = System.nanoTime()
        return try {
            val ctx = applicationContext
            when (test) {
                "device" -> log.result("device", DeviceProbe.describe(ctx))
                "ytdlp_update" -> YtDlpLab(ctx, log).update(params["channel"] ?: "stable")
                "ytdlp" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YtDlpLab(ctx, log).run(params.getValue("url"), params["video"] != "0", params["reuse"] != "0") { pct ->
                    setForegroundAsync(foregroundInfo(ctx, WorkQueue.LAB, "Uji yt-dlp", "Mengunduh ${pct.toInt()}%", pct.toInt()))
                } }
                "ass" -> AssLab(ctx, log).run(params["fps"]?.toInt() ?: 30)
                "render_media3" -> RenderLab(ctx, log).media3(params)
                "render_ffmpeg" -> RenderLab(ctx, log).ffmpeg(params)
                "audio_ffmpeg" -> RenderLab(ctx, log).audioOnly(params)
                "prompt" -> {
                    // Prompt project ditulis ke lab/prompts untuk dibandingkan dengan PromptBuilder desktop.
                    val id = params.getValue("project").toLong()
                    val prompts = (ctx as id.clipmi.android.ClipMiApp).container.analysis.prompts(id)
                    val dir = java.io.File(log.dir, "prompts").apply { deleteRecursively(); mkdirs() }
                    prompts.forEachIndexed { i, p -> java.io.File(dir, "$id-$i.txt").writeText(p) }
                    log.result("prompt", mapOf("ok" to prompts.isNotEmpty(), "project" to id, "parts" to prompts.size, "chars" to prompts.sumOf { it.length }))
                }
                "analyze" -> {
                    // Sama dengan tombol "Analisis dengan …"; provider/model opsional mengisi pengaturan Mode API (key tidak diubah).
                    val container = (ctx as id.clipmi.android.ClipMiApp).container
                    val projectId = params.getValue("project").toLong()
                    params["provider"]?.let { p ->
                        container.settings.saveAnalysis(AiProvider.from(p)!!, params["model"], null, null, null, null)
                    }
                    val config = container.settings.analysis()
                    log.result("analyze", mapOf("ok" to (config != null), "provider" to config?.provider?.value, "model" to config?.model, "has_key" to (config?.apiKey != null)))
                    if (config != null) container.analysis.start(projectId)
                }
                "models" -> {
                    // Uji koneksi Mode API (tanpa token): daftar model yang bisa dipakai key ini.
                    val container = (ctx as id.clipmi.android.ClipMiApp).container
                    params["provider"]?.let { p -> container.settings.saveAnalysis(AiProvider.from(p)!!, params["model"], null, null, null, null) }
                    val config = container.settings.analysis() ?: error("Mode API belum lengkap")
                    val models = container.aiClient.models(config)
                    log.result("models", mapOf("ok" to true, "provider" to config.provider.value, "count" to models.size, "models" to models.sorted().joinToString(" ")))
                }
                "import_reply" -> {
                    // Balasan AI dari file (adb push ke lab/), disimpan seperti tombol "Simpan kandidat".
                    val id = params.getValue("project").toLong()
                    val reply = java.io.File(log.dir, params.getValue("file")).readText()
                    val result = (ctx as id.clipmi.android.ClipMiApp).container.analysis.importReply(id, reply)
                    log.result("import_reply", mapOf("ok" to true, "created" to result.created, "skipped" to result.skipped, "errors" to result.errors.joinToString(" | ")))
                }
                else -> error("Uji tidak dikenal: $test (pilihan: ${LabTests.ALL})")
            }
            log.line("=== Selesai $test dalam ${"%.1f".format(java.util.Locale.ROOT, (System.nanoTime() - started) / 1e9)} s")
            Result.success()
        } catch (t: Throwable) {
            log.line("=== GAGAL $test: ${t.javaClass.simpleName}: ${t.message}")
            log.line(t.stackTraceToString().lines().take(12).joinToString("\n"))
            log.result(test, mapOf("ok" to false, "error" to "${t.javaClass.simpleName}: ${t.message}"))
            Result.failure()
        }
    }
}
