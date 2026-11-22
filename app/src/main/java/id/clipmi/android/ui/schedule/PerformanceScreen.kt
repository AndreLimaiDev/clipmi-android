package id.clipmi.android.ui.schedule

import id.clipmi.core.schedule.dayMonthTime
import id.clipmi.core.schedule.full

import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.PerformanceReportEntity
import id.clipmi.android.domain.PerformanceReports
import id.clipmi.android.domain.Wall
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
import id.clipmi.android.ui.screens.copy
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.PerformanceDigest
import id.clipmi.core.schedule.PerformancePrompt
import id.clipmi.core.schedule.PostStats
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Analisis Performa AI: kekuatan, kelemahan, rekomendasi, dan eksperimen dari postingan sendiri. Angka diolah
 * aplikasi; AI hanya menafsirkan, lewat Mode API atau chat AI (prompt disalin, balasan ditempel). Tiap poin menyebut
 * postingan buktinya.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceScreen(container: AppContainer, onBack: () -> Unit, onOpenSchedule: (Long) -> Unit) {
    val c = ClipMi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val reports by container.database.reports().observeRecent().collectAsState(initial = null)
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val digest by produceState<JsonObject?>(null, refresh) { value = container.performance.digest() }
    val apiReady by produceState<String?>(null) { value = container.settings.analysis()?.let { "${it.provider.label.substringBefore(" (")} · ${it.model}" } }
    var reply by rememberSaveable { mutableStateOf("") }
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
                            "$count postingan terukur dari ${d.getValue("accounts").jsonArray.size} akun. Tiap postingan dibandingkan dengan median akunnya sendiri, " +
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
                            apiReady?.let { "$it · ±${PerformancePrompt.estimateTokens(prompt)} token prompt + ±${PerformancePrompt.EXPECTED_OUTPUT_TOKENS} token balasan." }
                                ?: "Belum diatur. Atur penyedia di Pengaturan › Analisis AI, atau pakai chat AI di bawah.",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                        PrimaryButton("Jalankan analisis", enabled = apiReady != null, icon = ClipMiIcons.Sparkle, modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                container.performance.runApi()
                                    .onSuccess { selected = it; creating = false }
                                    .onFailure { notifier.show(it.message ?: "Analisis tidak bisa dijalankan.") }
                            }
                        })
                    }
                    ClipMiCard {
                        Text("Lewat chat AI (gratis)", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text("Salin prompt ke ChatGPT, Gemini, atau Claude, lalu tempel seluruh balasannya di sini.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Salin prompt", { notifier.show(if (copy(context, prompt)) "Prompt tersalin." else "Prompt terlalu besar untuk disalin.") }, icon = ClipMiIcons.Copy, height = 40.dp)
                            SecondaryButton("Bagikan", {
                                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt), "Bagikan prompt ke aplikasi chat AI")) }
                            }, icon = ClipMiIcons.Share, height = 40.dp)
                        }
                        Field(reply, { reply = it; replyError = null }, label = "Balasan AI", placeholder = "Tempel balasan JSON di sini", minLines = 4, maxLines = 10, error = replyError)
                        PrimaryButton("Simpan laporan", enabled = reply.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                container.performance.saveManual(reply)
                                    .onSuccess { selected = it; creating = false; reply = "" }
                                    .onFailure { replyError = it.message }
                            }
                        })
                    }
                }
            } else {
                ReportView(current, onRetry = {
                    scope.launch {
                        container.performance.runApi().onSuccess { selected = it }.onFailure { notifier.show(it.message ?: "Analisis tidak bisa dijalankan.") }
                    }
                }, onDelete = { confirmDelete = current.id }, onEvidence = { evidence = it })
            }
        }
    }

    evidence?.let { post ->
        ClipMiSheet({ evidence = null }, title = post.str("title"), subtitle = "${post.str("id")} · ${post.str("account")} (${post.str("platform")}) · ${post.str("published_at")}") {
            val f = post["factors"]?.jsonObject
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
            scope.launch { container.performance.delete(id); selected = null }
        }, onDismiss = { confirmDelete = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportView(report: PerformanceReportEntity, onRetry: () -> Unit, onDelete: () -> Unit, onEvidence: (JsonObject) -> Unit) {
    val c = ClipMi.colors
    val digest = remember(report.digest) { PerformanceReports.parseJson(report.digest) }
    val posts = remember(digest) { digest?.let(PerformanceDigest::postsById).orEmpty() }
    val result = remember(report.result) { PerformanceReports.parseJson(report.result) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${IdDate.full(Wall.of(report.createdAt))} · ${report.postsCount} postingan", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f))
        when {
            report.isBusy -> StatusBadge(if (report.status == PerformanceReports.QUEUED) "Antre" else "Berjalan", Tone.Busy)
            report.status == PerformanceReports.FAILED -> StatusBadge("Gagal", Tone.Bad)
            report.status == PerformanceReports.DONE -> StatusBadge(if (report.source == "manual") "Chat AI" else "Mode API", Tone.Good)
            else -> StatusBadge("Terhenti", Tone.Warn)
        }
    }
    when {
        report.isBusy -> ClipMiCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text(report.error ?: "Menunggu balasan AI… Boleh ditinggal; hasilnya tersimpan di sini.", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        report.status != PerformanceReports.DONE || result == null -> ClipMiCard {
            Text(report.error ?: "Analisis terhenti sebelum selesai.", style = MaterialTheme.typography.bodyMedium, color = c.bad)
            if (report.source == "api") PrimaryButton("Jalankan lagi", onRetry, icon = ClipMiIcons.Refresh, height = 44.dp)
        }
        else -> {
            result.str("summary").takeIf { it.isNotEmpty() }?.let { summary ->
                ClipMiCard { Text(summary, style = MaterialTheme.typography.bodyLarge, color = c.text) }
            }
            for ((key, label) in PerformancePrompt.SECTIONS) {
                val items = (result[key] as? JsonArray).orEmpty().map { it.jsonObject }
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
                        report.cost?.let { "± US$" + "%.4f".format(java.util.Locale.ROOT, it) },
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
