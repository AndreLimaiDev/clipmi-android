package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.AiUsageEntity
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.PerformanceReportEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.render.ClipSpec
import id.clipmi.core.schedule.DigestPost
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.PerformanceDigest
import id.clipmi.core.schedule.PerformancePrompt
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatPoint
import id.clipmi.core.schedule.TargetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Analisis Performa AI di iPhone (padanan PerformanceReports + PerformanceWorker Android): angka diolah aplikasi
 * (`PerformanceDigest` bersama), lalu ditafsirkan AI lewat Mode API atau chat AI. Mode API berjalan selama aplikasi
 * terbuka; server yang sibuk sesaat dicoba lagi setelah 30 lalu 90 detik.
 */
class IosPerformance(
    private val repo: Repository,
    private val renderer: IosRenderer,
    private val scheduler: IosScheduler,
    private val settings: IosSettings,
    private val client: IosAiClient,
    private val scope: CoroutineScope,
) {
    private val db get() = repo.db

    /** Angka untuk analisis berikutnya, dari postingan terbit yang punya statistik tayangan. */
    suspend fun digest(): JsonObject {
        val dao = db.schedules()
        val accounts = dao.accounts().associateBy { it.id }
        val schedules = dao.schedules().associateBy { it.id }
        val stats = dao.allStats().groupBy { it.scheduleAccountId }
        val clips = HashMap<Long, Pair<ClipEntity, ClipSpec?>>()
        val rows = dao.allTargets().filter { it.status == TargetStatus.PUBLISHED && it.publishedAt != null }.mapNotNull { row ->
            val schedule = schedules[row.scheduleId] ?: return@mapNotNull null
            val account = accounts[row.socialAccountId] ?: return@mapNotNull null
            val cached = clips[schedule.clipId] ?: run {
                val clip = db.clips().find(schedule.clipId) ?: return@mapNotNull null
                val spec = db.projects().find(clip.projectId)?.let { runCatching { renderer.spec(clip, it.cropPosition) }.getOrNull() }
                (clip to spec).also { clips[schedule.clipId] = it }
            }
            val (clip, spec) = cached
            val project = db.projects().find(clip.projectId)
            val hookModel = clip.hookLayout?.let { runCatching { (Json.parseToJsonElement(it).jsonObject["model"] as? JsonPrimitive)?.contentOrNull }.getOrNull() }
            val publishedAt = row.publishedAt ?: return@mapNotNull null
            DigestPost(
                rowId = row.id,
                scheduleId = schedule.id,
                accountId = account.id,
                account = account.name,
                platform = Platforms.label(account.platform),
                publishedAt = Wall.of(publishedAt),
                stats = stats[row.id].orEmpty().sortedBy { it.recordedAt }.map {
                    StatPoint(Wall.of(it.recordedAt), StatNumbers(it.views, it.likes, it.comments, it.shares))
                },
                title = ClipText.displayTitle(clip.title, clip.partNumber),
                hook = spec?.shownHook(),
                hookModel = hookModel,
                finalDuration = spec?.finalDuration() ?: clip.duration,
                lead = spec?.leadKind(),
                series = clip.clipSeriesId != null,
                keywords = scheduler.decodeList(clip.keywords),
                project = project?.title.orEmpty(),
                caption = Schedules.effectiveCaption(schedule.caption, clip.caption),
                hashtags = Schedules.effectiveHashtags(schedule.hashtags?.let(scheduler::decodeList), scheduler.decodeList(clip.hashtags)).size,
            )
        }
        return PerformanceDigest.build(rows, Wall.now())
    }

    /** Mode API: angka diolah sekarang dan disimpan bersama laporannya, lalu dikirim. Mengembalikan id laporan. */
    suspend fun runApi(): Result<Long> {
        if (settings.analysis() == null) return Result.failure(IllegalStateException("Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan, atau pakai mode manual."))
        if (db.reports().all().any { it.isBusy }) return Result.failure(IllegalStateException("Analisis sebelumnya masih berjalan."))
        val digest = digest()
        val count = PerformanceDigest.postCount(digest)
        if (count < PerformanceDigest.MIN_POSTS) return Result.failure(IllegalStateException("Butuh minimal ${PerformanceDigest.MIN_POSTS} postingan yang sudah punya statistik."))
        val id = db.reports().insert(PerformanceReportEntity(status = QUEUED, source = "api", postsCount = count, digest = digest.toString()))
        scope.launch { analyze(id) }
        return Result.success(id)
    }

    private suspend fun analyze(id: Long) {
        val dao = db.reports()
        var attempt = 1
        while (true) {
            val report = dao.find(id) ?: return
            if (report.status != QUEUED) return
            val config = settings.analysis() ?: return dao.update(
                report.copy(status = FAILED, error = "Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan, atau pakai mode manual.", updatedAt = nowMillis()),
            )
            val digest = parseJson(report.digest) ?: return dao.update(report.copy(status = FAILED, error = "Data laporan rusak.", updatedAt = nowMillis()))
            val running = report.copy(status = RUNNING, provider = config.provider.value, model = config.model, updatedAt = nowMillis())
            dao.update(running)
            val reply = try {
                client.complete(config, PerformancePrompt.build(digest))
            } catch (e: AiException) {
                val wait = RETRY_DELAYS.getOrNull(attempt - 1)
                if (e.transient && wait != null) {
                    val total = RETRY_DELAYS.size + 1
                    dao.update(running.copy(status = QUEUED, error = "${e.message} Dicoba lagi otomatis dalam $wait detik (percobaan ${attempt + 1} dari $total).", updatedAt = nowMillis()))
                    delay(wait * 1000L)
                    attempt++
                    continue
                }
                dao.update(
                    running.copy(
                        status = FAILED,
                        error = e.message + if (e.transient) " Sudah dicoba $attempt kali; jalankan lagi nanti, atau ganti model di Pengaturan." else "",
                        updatedAt = nowMillis(),
                    ),
                )
                return
            }
            val cost = config.cost(reply.inputTokens, reply.outputTokens)
            recordUsage(config.provider.value, config.model, reply.inputTokens, reply.outputTokens, cost)
            val usage = running.copy(inputTokens = reply.inputTokens, outputTokens = reply.outputTokens, cost = cost, updatedAt = nowMillis())
            try {
                dao.update(usage.copy(status = DONE, result = PerformancePrompt.parse(reply.text, digest).toString(), error = null))
            } catch (e: AnalysisResultException) {
                dao.update(usage.copy(status = FAILED, error = e.message))
            }
            return
        }
    }

    /** Mode manual: balasan chat AI untuk prompt yang disalin dibaca dan disimpan sebagai laporan. */
    suspend fun saveManual(reply: String): Result<Long> {
        if (reply.isBlank()) return Result.failure(AnalysisResultException("Tempel balasan dari chat AI dulu."))
        val digest = digest()
        val result = try {
            PerformancePrompt.parse(reply, digest)
        } catch (e: AnalysisResultException) {
            return Result.failure(e)
        }
        return Result.success(
            db.reports().insert(
                PerformanceReportEntity(status = DONE, source = "manual", postsCount = PerformanceDigest.postCount(digest), digest = digest.toString(), result = result.toString()),
            ),
        )
    }

    suspend fun delete(id: Long) = db.reports().delete(id)

    /** Laporan Mode API yang terputus karena aplikasi ditutup ditandai terhenti (bisa dijalankan lagi). */
    suspend fun recoverInterrupted() {
        for (report in db.reports().all()) {
            if (report.status != QUEUED && report.status != RUNNING) continue
            db.reports().update(report.copy(status = FAILED, error = "Analisis terhenti karena aplikasi ditutup. Jalankan lagi.", updatedAt = nowMillis()))
        }
    }

    private suspend fun recordUsage(provider: String, model: String, inputTokens: Int, outputTokens: Int, cost: Double?) {
        if (inputTokens + outputTokens == 0) return
        db.aiUsages().insert(
            AiUsageEntity(purpose = "analysis", projectId = null, projectTitle = USAGE_TITLE, provider = provider, model = model, inputTokens = inputTokens, outputTokens = outputTokens, cost = cost),
        )
    }

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val USAGE_TITLE = "Analisis performa postingan"
        private val RETRY_DELAYS = listOf(30L, 90L)

        fun parseJson(text: String?): JsonObject? = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    }
}

/**
 * Analisis Performa AI (padanan PerformanceScreen Android): kekuatan, kelemahan, rekomendasi, dan eksperimen dari
 * postingan sendiri. Tiap poin menyebut postingan buktinya.
 */
@Composable
fun PerformanceScreen(repo: Repository, performance: IosPerformance, settings: IosSettings, onBack: () -> Unit, onOpenSchedule: (Long) -> Unit) {
    val c = ClipMi.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val reports by remember { repo.db.reports().observeRecent() }.collectAsState(initial = null)
    var selected by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val digest by produceState<JsonObject?>(null, refresh) { value = performance.digest() }
    val apiReady by produceState<String?>(null) { value = settings.analysis()?.let { "${it.provider.label.substringBefore(" (")} · ${it.model}" } }
    var reply by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var evidence by remember { mutableStateOf<JsonObject?>(null) }

    val list = reports
    val current = list?.firstOrNull { it.id == (selected ?: list.firstOrNull()?.id) }?.takeIf { !creating }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        SubScreenHeader("Analisis performa", onBack)
        if (list == null) return@Column
        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (list.isNotEmpty()) {
                ChipRow(gutter = 0.dp) {
                    FilterPill("Analisis baru", selected = current == null, onClick = { creating = true; refresh++ })
                    list.forEach { r ->
                        FilterPill(IdDate.dayMonthTime(Wall.of(r.createdAt)), selected = current?.id == r.id, onClick = { selected = r.id; creating = false })
                    }
                }
            }

            if (current == null) {
                val d = digest
                val count = d?.let(PerformanceDigest::postCount) ?: 0
                ClipMiCard {
                    Text("Data yang dianalisis", style = MaterialTheme.typography.titleMedium, color = c.text)
                    if (d == null) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    } else {
                        Text(
                            "$count postingan terukur dari ${(d["accounts"] as? JsonArray)?.size ?: 0} akun. Tiap postingan dibandingkan dengan median akunnya sendiri, " +
                                "lalu dikelompokkan per durasi, jam posting, model hook, dan faktor lain.",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                        PerformanceDigest.thinAccounts(d).takeIf { it.isNotEmpty() }?.let { thin ->
                            Text(
                                "Data masih sedikit (di bawah ${PerformanceDigest.ENOUGH_PER_ACCOUNT} postingan) untuk: " + thin.joinToString(", ") { it.str("name") } + ". Hasilnya baru dugaan awal.",
                                style = MaterialTheme.typography.bodySmall, color = c.warn,
                            )
                        }
                    }
                }
                if (d != null && count < PerformanceDigest.MIN_POSTS) {
                    Text(
                        "Butuh minimal ${PerformanceDigest.MIN_POSTS} postingan yang sudah punya statistik. Tandai postingan terbit dan tempel link-nya di Jadwal.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                    )
                } else if (d != null) {
                    val prompt = remember(d) { PerformancePrompt.build(d) }
                    ClipMiCard {
                        Text("Mode API", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(
                            apiReady?.let { "$it · ±${PerformancePrompt.estimateTokens(prompt)} token prompt + ±${PerformancePrompt.EXPECTED_OUTPUT_TOKENS} token balasan. Biarkan aplikasi terbuka sampai selesai." }
                                ?: "Belum diatur. Atur penyedia di Pengaturan › Analisis AI, atau pakai chat AI di bawah.",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                        PrimaryButton("Jalankan analisis", enabled = apiReady != null, icon = ClipMiIcons.Sparkle, modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                performance.runApi()
                                    .onSuccess { selected = it; creating = false }
                                    .onFailure { notifier.show(it.message ?: "Analisis tidak bisa dijalankan.") }
                            }
                        })
                    }
                    ClipMiCard {
                        Text("Lewat chat AI (gratis)", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text("Salin prompt ke ChatGPT, Gemini, atau Claude, lalu tempel seluruh balasannya di sini.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Salin prompt", {
                                clipboard.setText(AnnotatedString(prompt))
                                notifier.show("Prompt tersalin.")
                            }, icon = ClipMiIcons.Copy, height = 40.dp)
                            SecondaryButton("Tempel balasan", {
                                clipboard.getText()?.text?.let { reply = it; replyError = null }
                            }, height = 40.dp)
                        }
                        Field(reply, { reply = it; replyError = null }, label = "Balasan AI", placeholder = "Tempel balasan JSON di sini", minLines = 4, maxLines = 10, error = replyError)
                        PrimaryButton("Simpan laporan", enabled = reply.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                performance.saveManual(reply)
                                    .onSuccess { selected = it; creating = false; reply = "" }
                                    .onFailure { replyError = it.message }
                            }
                        })
                    }
                }
            } else {
                ReportView(current, onRetry = {
                    scope.launch {
                        performance.runApi().onSuccess { selected = it }.onFailure { notifier.show(it.message ?: "Analisis tidak bisa dijalankan.") }
                    }
                }, onDelete = { confirmDelete = current.id }, onEvidence = { evidence = it })
            }
        }
    }

    evidence?.let { post ->
        ClipMiSheet({ evidence = null }, title = post.str("title"), subtitle = "${post.str("id")} · ${post.str("account")} (${post.str("platform")}) · ${post.str("published_at")}") {
            val f = post["factors"] as? JsonObject
            Text(
                "Tayangan pembanding ${PerformancePrompt.number(post.num("metric"))} (${post.str("metric_basis")}) · rasio ${PerformancePrompt.decimal(post.num("ratio"))} · ER ${PerformancePrompt.percent(post.num("er"))}",
                style = MaterialTheme.typography.bodyMedium, color = c.text,
            )
            Text(
                listOfNotNull(f?.str("duration"), f?.str("time"), f?.str("hook_model"), f?.str("opener")).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
            post.str("hook").takeIf { it.isNotEmpty() }?.let { Text("Hook: $it", style = MaterialTheme.typography.bodySmall, color = c.textBody) }
            SecondaryButton("Buka jadwal", { evidence = null; onOpenSchedule(post.num("schedule_id")?.toLong() ?: 0) }, height = 40.dp)
        }
    }
    confirmDelete?.let { id ->
        ConfirmDialog("Hapus laporan ini?", "Laporan analisis ini dihapus permanen. Statistik postingan tidak ikut terhapus.", "Hapus", danger = true, onConfirm = {
            confirmDelete = null
            scope.launch { performance.delete(id); selected = null }
        }, onDismiss = { confirmDelete = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportView(report: PerformanceReportEntity, onRetry: () -> Unit, onDelete: () -> Unit, onEvidence: (JsonObject) -> Unit) {
    val c = ClipMi.colors
    val digest = remember(report.digest) { IosPerformance.parseJson(report.digest) }
    val posts = remember(digest) { digest?.let(PerformanceDigest::postsById).orEmpty() }
    val result = remember(report.result) { IosPerformance.parseJson(report.result) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${IdDate.full(Wall.of(report.createdAt))} · ${report.postsCount} postingan", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f))
        when {
            report.isBusy -> StatusBadge(if (report.status == IosPerformance.QUEUED) "Antre" else "Berjalan", Tone.Busy)
            report.status == IosPerformance.FAILED -> StatusBadge("Gagal", Tone.Bad)
            report.status == IosPerformance.DONE -> StatusBadge(if (report.source == "manual") "Chat AI" else "Mode API", Tone.Good)
            else -> StatusBadge("Terhenti", Tone.Warn)
        }
    }
    when {
        report.isBusy -> ClipMiCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text(report.error ?: "Menunggu balasan AI… Biarkan aplikasi tetap terbuka; hasilnya tersimpan di sini.", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        report.status != IosPerformance.DONE || result == null -> ClipMiCard {
            Text(report.error ?: "Analisis terhenti sebelum selesai.", style = MaterialTheme.typography.bodyMedium, color = c.bad)
            if (report.source == "api") PrimaryButton("Jalankan lagi", onRetry, icon = ClipMiIcons.Refresh, height = 44.dp)
        }
        else -> {
            result.str("summary").takeIf { it.isNotEmpty() }?.let { summary ->
                ClipMiCard { Text(summary, style = MaterialTheme.typography.bodyLarge, color = c.text) }
            }
            for ((key, label) in PerformancePrompt.SECTIONS) {
                val items = (result[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                if (items.isEmpty()) continue
                ClipMiCard {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = c.text)
                    items.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• " + item.str("point"), style = MaterialTheme.typography.bodyMedium, color = c.textBody)
                            item.str("how").takeIf { it.isNotEmpty() }?.let { Text("Cara: $it", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(start = 12.dp)) }
                            val ids = (item["evidence"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            if (ids.isNotEmpty()) {
                                FlowRow(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ids.forEach { id ->
                                        val post = posts[id]
                                        ActionChip(
                                            if (post != null) "$id · ${PerformancePrompt.number(post.num("metric"))}" else id,
                                            height = 30.dp, enabled = post != null,
                                            onClick = { post?.let(onEvidence) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            result.str("data_notes").takeIf { it.isNotEmpty() }?.let {
                Text("Catatan data: $it", style = MaterialTheme.typography.bodySmall, color = c.warn)
            }
            if (report.source == "api" && report.model != null) {
                Text(
                    listOfNotNull(
                        report.model,
                        report.inputTokens?.let { "${PostStats.number(it.toLong())} token masuk" },
                        report.outputTokens?.let { "${PostStats.number(it.toLong())} token keluar" },
                        report.cost?.let { "± US$" + IosAnalysis.dollars(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
        }
    }
    GhostButton("Hapus laporan", onDelete, danger = true)
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
