package id.clipmi.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.domain.AiConfig
import id.clipmi.android.domain.AiProvider
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.core.analysis.PromptBuilder
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Bagian "Analisis AI" di Pengaturan: Mode API (penyedia, model, key, harga) dan prompt. */
@Composable
fun AnalysisSettingsSection(container: AppContainer, onOpenUsage: () -> Unit) {
    val scope = rememberCoroutineScope()
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
    var message by remember { mutableStateOf<String?>(null) }
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analisis AI (Mode API)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Opsional: memilih momen otomatis lewat API. Tanpa ini, mode manual (salin prompt ke aplikasi chat AI) tetap bisa dipakai gratis.",
            style = MaterialTheme.typography.bodySmall,
        )
        AiProvider.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = provider == option, onClick = {
                    provider = option
                    hasKey = container.settings.hasAnalysisKey(option)
                    if (model.isBlank()) model = option.defaultModel.orEmpty()
                    message = null
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = provider == option, onClick = null)
                Text(option.label, Modifier.padding(start = 8.dp))
            }
        }
        OutlinedTextField(
            model, { model = it; message = null }, label = { Text("Model") },
            placeholder = { Text(provider.defaultModel ?: "nama model dari dokumentasi penyedia") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (provider == AiProvider.Custom) {
            OutlinedTextField(baseUrl, { baseUrl = it; message = null }, label = { Text("Alamat API") }, placeholder = { Text("https://…/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            apiKey, { apiKey = it; message = null }, label = { Text("API key") },
            placeholder = { Text(if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(priceIn, { priceIn = it }, label = { Text("USD / 1 jt token masuk") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(priceOut, { priceOut = it }, label = { Text("USD / 1 jt token keluar") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Text(
            "Harga untuk perkiraan biaya. Kosongkan untuk model Claude (harga bawaan: Haiku 5.5 \$0,10/\$0,50).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Analisis otomatis setelah transkrip siap", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it })
        }

        Text("Prompt", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            maxTokens, { maxTokens = it.filter(Char::isDigit) }, label = { Text("Batas panjang prompt per bagian (token)") },
            placeholder = { Text(PromptBuilder.DEFAULT_MAX_TOKENS.toString()) },
            supportingText = { Text("Transkrip lebih panjang dari ini dibagi beberapa bagian. Kecilkan untuk aplikasi chat yang membatasi panjang pesan.") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            template, { template = it }, label = { Text("Template prompt ({{judul}}, {{durasi}})") },
            minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { template = PromptBuilder.DEFAULT_TEMPLATE }) { Text("Kembalikan template bawaan") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    save()
                    message = if (container.settings.analysis() != null) "Tersimpan. Mode API siap dipakai." else "Tersimpan, tetapi Mode API belum lengkap (model atau API key)."
                }
            }) { Text("Simpan") }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    save()
                    val config: AiConfig? = container.settings.analysis()
                    message = if (config == null) "Isi model dan API key dulu." else runCatching {
                        val models = container.aiClient.models(config)
                        "Koneksi berhasil (tanpa memakai token). " +
                            if (models.contains(config.model)) "Model ${config.model} tersedia." else "Model ${config.model} tidak ada di daftar ${models.size} model; periksa namanya."
                    }.getOrElse { it.message ?: "Uji koneksi gagal." }
                    testing = false
                }
            }) { Text(if (testing) "Menguji…" else "Uji koneksi") }
            if (hasKey) TextButton(onClick = { container.settings.removeAnalysisKey(provider); hasKey = false }) { Text("Hapus key") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onOpenUsage, modifier = Modifier.fillMaxWidth()) { Text("Pemakaian AI") }
    }
}

/** Halaman Pemakaian AI: catatan token/audio dan perkiraan biaya per analisis dan transkripsi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiUsageScreen(container: AppContainer, onBack: () -> Unit) {
    val usages by container.database.aiUsages().observeAll().collectAsState(initial = emptyList())
    val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id"))
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("id"))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pemakaian AI") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") } },
        )
        if (usages.isEmpty()) {
            EmptyState("Belum ada pemakaian", "Setiap analisis dan transkripsi lewat API dicatat di sini beserta perkiraan biayanya.")
            return
        }
        val total = usages.mapNotNull { it.cost }.sum()
        Text(
            "Total ${usages.size} pemakaian" + (if (total > 0) " · sekitar $" + "%.2f".format(Locale.ROOT, total) else "") +
                " · biaya sebenarnya mengikuti tagihan penyedia.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn {
            items(usages, key = { it.id }) { usage ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(usage.projectTitle.ifEmpty { "(project dihapus)" }, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            if (usage.purpose == "transcription") "Transkripsi" else "Analisis",
                            "${AiProvider.from(usage.provider)?.label ?: usage.provider} ${usage.model}",
                            if (usage.audioSeconds > 0) "${number.format((usage.audioSeconds / 60).toLong())} menit audio" else null,
                            if (usage.inputTokens + usage.outputTokens > 0) "${number.format(usage.inputTokens)} masuk / ${number.format(usage.outputTokens)} keluar" else null,
                            usage.cost?.let { "$" + "%.4f".format(Locale.ROOT, it) },
                            date.format(Date(usage.createdAt)),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
