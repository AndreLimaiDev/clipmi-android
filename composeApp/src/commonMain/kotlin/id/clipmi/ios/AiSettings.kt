package id.clipmi.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.OptionList
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SectionLabel
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.SwitchRow
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.analysis.PromptBuilder
import kotlinx.coroutines.launch

/** Halaman "Analisis AI" (padanan AnalysisSettingsScreen Android): Mode API, analisis otomatis, dan prompt. */
@Composable
fun AnalysisSettingsScreen(settings: IosSettings, client: IosAiClient, onBack: () -> Unit) {
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
        val raw = settings.rawAnalysis()
        provider = raw.provider ?: AiProvider.Anthropic
        model = raw.model.orEmpty()
        baseUrl = raw.baseUrl.orEmpty()
        priceIn = raw.priceInput.orEmpty()
        priceOut = raw.priceOutput.orEmpty()
        hasKey = settings.hasAnalysisKey(provider)
        auto = settings.autoAnalyze()
        maxTokens = settings.promptMaxTokens()?.toString().orEmpty()
        template = settings.promptTemplate() ?: PromptBuilder.DEFAULT_TEMPLATE
    }

    suspend fun save() {
        settings.saveAnalysis(provider, model, baseUrl, apiKey, priceIn, priceOut)
        settings.setAutoAnalyze(auto)
        settings.savePrompt(template.takeIf { it.trim() != PromptBuilder.DEFAULT_TEMPLATE.trim() }, maxTokens.trim().toIntOrNull())
        apiKey = ""
        hasKey = settings.hasAnalysisKey(provider)
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Analisis AI", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Opsional · tanpa API, lewat chat AI tetap gratis", style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.textMuted)
            OptionList("Penyedia", AiProvider.entries, provider, label = { it.label }, onSelect = {
                provider = it
                hasKey = settings.hasAnalysisKey(it)
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
                supporting = "Tersimpan di Keychain iPhone · tidak ikut cadangan",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(priceIn, { priceIn = it }, label = "USD / 1 jt token masuk", singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Field(priceOut, { priceOut = it }, label = "USD / 1 jt token keluar", singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("Untuk perkiraan biaya · kosong = harga bawaan Claude", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
            }
            ClipMiCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Analisis otomatis", "Setelah transkrip siap · selama aplikasi terbuka", auto, onCheckedChange = { auto = it })
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
                    settings.removeAnalysisKey(provider)
                    hasKey = false
                    notifier.show("API key dihapus dari iPhone ini.")
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
                        val config = settings.analysis()
                        notifier.show(
                            if (config == null) "Isi model dan API key dulu." else runCatching {
                                val models = client.models(config)
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
                        notifier.show(if (settings.analysis() != null) "Tersimpan. Mode API siap dipakai." else "Tersimpan, tetapi Mode API belum lengkap (model atau API key).")
                    }
                })
            }
        }
    }
}

/**
 * API key YouTube Data API v3 untuk statistik YouTube resmi (opsional, sama dengan Android). Disimpan di Keychain dan
 * tidak pernah ditampilkan lagi; tanpa key, statistik YouTube dibaca lewat yt-dlp.
 */
@Composable
fun YoutubeKeySheet(settings: IosSettings, hasKey: Boolean, onDone: () -> Unit) {
    val notifier = LocalNotifier.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ClipMiSheet(onDone, title = "YouTube Data API", subtitle = "Tayangan, suka, dan komentar YouTube lewat jalur resmi, tanpa login.") {
        Text(
            "Buat key di Google Cloud Console: aktifkan \"YouTube Data API v3\", lalu Credentials › Create credentials › API key. Kuota gratisnya cukup untuk ribuan pembaruan per hari.",
            style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted,
        )
        Field(
            key, { key = it.trim(); error = null },
            label = "API key",
            placeholder = if (hasKey) "Tersimpan (tidak ditampilkan)" else "AIza…",
            singleLine = true,
            error = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        PrimaryButton("Simpan key", modifier = Modifier.fillMaxWidth(), onClick = {
            error = when {
                key.isEmpty() -> "Tempel API key dulu."
                key.length < 20 -> "API key terlalu pendek."
                key.length > 200 -> "API key terlalu panjang."
                else -> null
            }
            if (error == null) {
                settings.saveYoutubeKey(key)
                notifier.show("API key YouTube tersimpan.")
                onDone()
            }
        })
        if (hasKey) GhostButton("Hapus key", danger = true, onClick = {
            settings.removeYoutubeKey()
            notifier.show("API key YouTube dihapus. Statistik YouTube kembali lewat yt-dlp.")
            onDone()
        })
    }
}

/** Tanda centang bila sudah diatur, selain itu "Belum" (sama dengan Android). */
@Composable
fun ReadyMark(ready: Boolean) {
    if (ready) Icon(ClipMiIcons.CircleCheck, contentDescription = "Siap", tint = ClipMi.colors.accent, modifier = Modifier.size(24.dp))
    else Text("Belum", style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted)
}

/** Transkripsi API untuk video tanpa subtitle Indonesia (padanan TranscriptionSettingsScreen Android). */
@Composable
fun TranscriptionSettingsScreen(settings: IosSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var provider by remember { mutableStateOf(TranscriptionProvider.Groq) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (p, url, m) = settings.rawTranscription()
        provider = p ?: TranscriptionProvider.Groq
        baseUrl = url.orEmpty()
        model = m.orEmpty()
        hasKey = settings.hasTranscriptionKey(provider)
        auto = settings.autoTranscribe()
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Transkripsi API", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Untuk video tanpa subtitle Indonesia · Groq paling murah", style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.textMuted)
            OptionList("Penyedia", TranscriptionProvider.entries, provider, label = { it.label }, onSelect = {
                provider = it
                hasKey = settings.hasTranscriptionKey(it)
            })
            Field(baseUrl, { baseUrl = it }, label = "Alamat API", placeholder = provider.baseUrl ?: "https://…/v1", singleLine = true, supporting = "Kosong = alamat bawaan")
            Field(model, { model = it }, label = "Model", placeholder = provider.defaultModel ?: "nama model Whisper", singleLine = true)
            Field(
                apiKey, { apiKey = it },
                label = "API key",
                placeholder = if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder,
                supporting = "Tersimpan di Keychain iPhone · tidak ikut cadangan",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            ClipMiCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Transkripsi otomatis", "Setelah video tanpa subtitle selesai diunduh", auto, onCheckedChange = { auto = it })
            }
            Text(
                "Transkripsi berjalan selama aplikasi terbuka. Bila terhenti, potongan 20 menit yang sudah selesai tidak dikirim ulang.",
                style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted,
            )
            if (hasKey) {
                GhostButton("Hapus API key ${provider.label}", danger = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    settings.removeTranscriptionKey(provider)
                    hasKey = false
                    notifier.show("API key ${provider.label} dihapus dari iPhone ini.")
                })
            }
        }
        // Simpan tetap di bawah layar.
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)) {
            PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    settings.saveTranscription(provider, baseUrl, model, apiKey)
                    settings.setAutoTranscribe(auto)
                    apiKey = ""
                    hasKey = settings.hasTranscriptionKey(provider)
                    notifier.show(if (settings.transcription() != null) "Tersimpan. Transkripsi API siap dipakai." else "Tersimpan, tetapi belum lengkap (alamat, model, atau API key).")
                }
            })
        }
    }
}
