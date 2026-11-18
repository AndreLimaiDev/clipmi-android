package id.clipmi.android.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.domain.AiConfig
import id.clipmi.android.domain.AiProvider
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.OptionList
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SectionLabel
import id.clipmi.android.ui.components.StatTile
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.SwitchRow
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.PromptBuilder
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Halaman "Analisis AI": Mode API (penyedia, model, key, harga), analisis otomatis, dan prompt. */
@Composable
fun AnalysisSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var provider by remember { mutableStateOf(AiProvider.Anthropic) }
    var model by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var priceIn by remember { mutableStateOf("") }
    var priceOut by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var maxTokens by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val raw = container.settings.rawAnalysis()
        provider = raw.provider ?: AiProvider.Anthropic
        model = raw.model.orEmpty()
        baseUrl = raw.baseUrl.orEmpty()
        priceIn = raw.priceInput.orEmpty()
        priceOut = raw.priceOutput.orEmpty()
        hasKey = container.settings.hasAnalysisKey(provider)
        auto = container.settings.autoAnalyze()
        maxTokens = container.settings.promptMaxTokens()?.toString().orEmpty()
        template = container.settings.promptTemplate() ?: PromptBuilder.DEFAULT_TEMPLATE
    }

    suspend fun save() {
        container.settings.saveAnalysis(provider, model, baseUrl, apiKey, priceIn, priceOut)
        container.settings.setAutoAnalyze(auto)
        container.settings.savePrompt(template.takeIf { it.trim() != PromptBuilder.DEFAULT_TEMPLATE.trim() }, maxTokens.trim().toIntOrNull())
        apiKey = ""
        hasKey = container.settings.hasAnalysisKey(provider)
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Analisis AI", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Opsional · tanpa API, lewat chat AI tetap gratis",
                style = MaterialTheme.typography.bodyMedium,
                color = ClipMi.colors.textMuted,
            )
            OptionList("Penyedia", AiProvider.entries, provider, label = { it.label }, onSelect = {
                provider = it
                hasKey = container.settings.hasAnalysisKey(it)
                if (model.isBlank()) model = it.defaultModel.orEmpty()
            })
            Field(model, { model = it }, label = "Model", placeholder = provider.defaultModel ?: "nama model dari dokumentasi penyedia", singleLine = true)
            if (provider == AiProvider.Custom) {
                Field(baseUrl, { baseUrl = it }, label = "Alamat API", placeholder = "https://…/v1", singleLine = true)
            }
            Field(
                apiKey, { apiKey = it },
                label = "API key",
                placeholder = if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder,
                supporting = "Terenkripsi di HP · tidak ikut cadangan",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(priceIn, { priceIn = it }, label = "USD / 1 jt token masuk", singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Field(priceOut, { priceOut = it }, label = "USD / 1 jt token keluar", singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text(
                    "Untuk perkiraan biaya · kosong = harga bawaan Claude",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClipMi.colors.textMuted,
                )
            }
            ClipMiCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Analisis otomatis", "Setelah transkrip siap", auto, onCheckedChange = { auto = it })
            }

            SectionLabel("Prompt")
            Field(
                maxTokens, { maxTokens = it.filter(Char::isDigit) },
                label = "Batas panjang prompt per bagian (token)",
                placeholder = PromptBuilder.DEFAULT_MAX_TOKENS.toString(),
                supporting = "Transkrip yang lebih panjang dibagi beberapa bagian",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Field(template, { template = it }, label = "Template prompt ({{judul}}, {{durasi}})", minLines = 4, maxLines = 10)
            GhostButton("Kembalikan template bawaan", onClick = { template = PromptBuilder.DEFAULT_TEMPLATE })

            if (hasKey) {
                GhostButton("Hapus API key ${provider.label.substringBefore(" (")}", danger = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    container.settings.removeAnalysisKey(provider)
                    hasKey = false
                    notifier.show("API key dihapus dari HP ini.")
                })
            }
        }
        // Uji koneksi dan Simpan tetap di bawah layar.
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (testing) "Menguji…" else "Uji koneksi", enabled = !testing, modifier = Modifier.weight(1f), onClick = {
                testing = true
                scope.launch {
                    save()
                    val config: AiConfig? = container.settings.analysis()
                    notifier.show(
                        if (config == null) "Isi model dan API key dulu." else runCatching {
                            val models = container.aiClient.models(config)
                            "Koneksi berhasil (tanpa memakai token). " +
                                if (models.contains(config.model)) "Model ${config.model} tersedia." else "Model ${config.model} tidak ada di daftar ${models.size} model; periksa namanya."
                        }.getOrElse { it.message ?: "Uji koneksi gagal." },
                    )
                    testing = false
                }
            })
            PrimaryButton("Simpan", modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    save()
                    notifier.show(if (container.settings.analysis() != null) "Tersimpan. Mode API siap dipakai." else "Tersimpan, tetapi Mode API belum lengkap (model atau API key).")
                }
            })
        }
        }
    }
}

/** Halaman Pemakaian AI: catatan token/audio dan perkiraan biaya per analisis dan transkripsi. */
@Composable
fun AiUsageScreen(container: AppContainer, onBack: () -> Unit) {
    val usages by container.database.aiUsages().observeAll().collectAsState(initial = null)
    val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id"))
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("id"))
    val c = ClipMi.colors

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Pemakaian AI", onBack)
        val list = usages ?: return@Column
        if (list.isEmpty()) {
            EmptyState("Belum ada pemakaian", "Setiap analisis dan transkripsi lewat API dicatat di sini beserta perkiraan biayanya.", icon = ClipMiIcons.Chart)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                // Ringkasan seperti Statistik: total token besar, perkiraan biaya di kanan, jumlah pemakaian.
                val total = list.mapNotNull { it.cost }.sum()
                val tokens = list.sumOf { (it.inputTokens + it.outputTokens).toLong() }
                val audio = list.sumOf { it.audioSeconds }
                ClipMiCard(spacing = 10.dp, padding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("Total token", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            Text(number.format(tokens), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 36.sp), color = c.text)
                        }
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
                            Text("Perkiraan", style = MaterialTheme.typography.labelSmall, color = c.accent)
                            Text("$" + "%.2f".format(Locale.ROOT, total), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.accent)
                        }
                    }
                    Text(
                        listOfNotNull("${list.size} pemakaian", audio.takeIf { it > 0 }?.let { "${number.format((it / 60).toLong())} menit audio" }, "biaya sebenarnya mengikuti tagihan penyedia").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                }
            }
            // Per tanggal, tiap pemakaian satu baris.
            val byDay = list.groupBy { java.time.Instant.ofEpochMilli(it.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            byDay.forEach { (day, rows) ->
                item(key = "day-$day") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(id.clipmi.core.schedule.IdDate.dayMonth(day) + " ${day.year}", style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
                        ClipMiCard(spacing = 0.dp, padding = PaddingValues(vertical = 4.dp)) {
                            rows.forEachIndexed { i, usage ->
                                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
                                val transcription = usage.purpose == "transcription"
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (transcription) c.busy.copy(alpha = 0.12f) else c.accentSoft), contentAlignment = Alignment.Center) {
                                        Icon(if (transcription) ClipMiIcons.Mic else ClipMiIcons.Sparkle, null, tint = if (transcription) c.busy else c.accent, modifier = Modifier.size(16.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(usage.projectTitle.ifEmpty { "(project dihapus)" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                if (transcription) "Transkripsi" else "Analisis",
                                                usage.model,
                                                if (usage.audioSeconds > 0) "${number.format((usage.audioSeconds / 60).toLong().coerceAtLeast(1))} menit" else null,
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val t = usage.inputTokens + usage.outputTokens
                                        if (t > 0) Text(compact(t.toLong()) + " token", style = MaterialTheme.typography.labelMedium, color = c.textBody)
                                        usage.cost?.let { Text("$" + "%.3f".format(Locale.ROOT, it), style = MaterialTheme.typography.labelSmall, color = c.textMuted) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Angka ringkas: 12.806 → "12,8 rb". */
private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.forLanguageTag("id"), "%.1f jt", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.forLanguageTag("id"), "%.1f rb", value / 1_000.0)
    else -> value.toString()
}
