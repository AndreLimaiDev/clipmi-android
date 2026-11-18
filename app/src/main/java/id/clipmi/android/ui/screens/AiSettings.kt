package id.clipmi.android.ui.screens

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
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Opsional: memilih momen otomatis lewat API. Tanpa ini, analisis lewat chat AI (salin prompt) tetap bisa dipakai gratis.",
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
                supporting = "Disimpan terenkripsi di Android Keystore; tidak ikut cadangan.",
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
                    "Untuk perkiraan biaya. Kosongkan untuk model Claude (harga bawaan: Haiku 5.5 \$0,10/\$0,50).",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClipMi.colors.textMuted,
                )
            }
            ClipMiCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Analisis otomatis", "Langsung setelah transkrip siap.", auto, onCheckedChange = { auto = it })
            }

            SectionLabel("Prompt")
            Field(
                maxTokens, { maxTokens = it.filter(Char::isDigit) },
                label = "Batas panjang prompt per bagian (token)",
                placeholder = PromptBuilder.DEFAULT_MAX_TOKENS.toString(),
                supporting = "Transkrip lebih panjang dari ini dibagi beberapa bagian. Kecilkan untuk aplikasi chat yang membatasi panjang pesan.",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Field(template, { template = it }, label = "Template prompt ({{judul}}, {{durasi}})", minLines = 4, maxLines = 10)
            GhostButton("Kembalikan template bawaan", onClick = { template = PromptBuilder.DEFAULT_TEMPLATE })

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
            if (hasKey) {
                GhostButton("Hapus API key ${provider.label.substringBefore(" (")}", danger = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    container.settings.removeAnalysisKey(provider)
                    hasKey = false
                    notifier.show("API key dihapus dari HP ini.")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val total = list.mapNotNull { it.cost }.sum()
                val tokens = list.sumOf { (it.inputTokens + it.outputTokens).toLong() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(list.size.toString(), "pemakaian", Modifier.weight(1f))
                        StatTile(number.format(tokens), "token", Modifier.weight(1f))
                        StatTile("$" + "%.2f".format(Locale.ROOT, total), "perkiraan", Modifier.weight(1f))
                    }
                    Text("Biaya sebenarnya mengikuti tagihan penyedia.", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
                }
            }
            items(list, key = { it.id }) { usage ->
                ClipMiCard(spacing = 6.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(usage.projectTitle.ifEmpty { "(project dihapus)" }, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                        StatusBadge(if (usage.purpose == "transcription") "Transkripsi" else "Analisis", if (usage.purpose == "transcription") Tone.Busy else Tone.Good)
                    }
                    Text(
                        listOfNotNull(
                            "${AiProvider.from(usage.provider)?.label?.substringBefore(" (") ?: usage.provider} · ${usage.model}",
                            if (usage.audioSeconds > 0) "${number.format((usage.audioSeconds / 60).toLong())} menit audio" else null,
                            if (usage.inputTokens + usage.outputTokens > 0) "${number.format(usage.inputTokens)} masuk / ${number.format(usage.outputTokens)} keluar" else null,
                            usage.cost?.let { "$" + "%.4f".format(Locale.ROOT, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textBody,
                    )
                    Text(date.format(Date(usage.createdAt)), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
        }
    }
}
