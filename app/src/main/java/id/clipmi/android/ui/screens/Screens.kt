package id.clipmi.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.BuildConfig
import id.clipmi.android.domain.TranscriptionProvider
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.GroupDivider
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.OptionList
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.ScreenHeader
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SettingsGroup
import id.clipmi.android.ui.components.SettingsRow
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.SubScreenHeader
import id.clipmi.android.ui.components.SwitchRow
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenTranscription: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenCorrections: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenCaptionStyles: () -> Unit = {},
    onOpenFrameStyles: () -> Unit = {},
    onOpenBumpers: () -> Unit = {},
    onOpenResults: () -> Unit = {},
    onOpenData: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val transcription by produceState<Pair<String, Boolean>?>(null) {
        val cfg = container.settings.transcription()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    val analysis by produceState<Pair<String, Boolean>?>(null) {
        val cfg = container.settings.analysis()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur · lewat chat AI tetap gratis" to false
    }
    val usages by container.database.aiUsages().observeAll().collectAsState(initial = emptyList())
    val corrections by container.database.corrections().observeAll().collectAsState(initial = emptyList())
    var ytdlp by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var enhanceAudio by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { enhanceAudio = container.settings.enhanceAudio() }
    LaunchedEffect(Unit) { ytdlp = runCatching { container.ytDlp.version() }.getOrNull() }
    var hasYoutubeKey by remember { mutableStateOf(container.settings.hasYoutubeKey()) }
    var editingYoutubeKey by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    if (checkingUpdate) UpdateSheet(onDone = { checkingUpdate = false })
    if (editingYoutubeKey) YoutubeKeySheet(container, hasYoutubeKey, onDone = { editingYoutubeKey = false; hasYoutubeKey = container.settings.hasYoutubeKey() })

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = FloatingNavSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Pengaturan")

        SettingsGroup("AI & transkrip") {
            SettingsRow("Analisis AI", analysis?.first, onClick = onOpenAnalysis, trailing = { analysis?.let { ReadyBadge(it.second) } })
            GroupDivider()
            SettingsRow("Transkripsi API", transcription?.first, onClick = onOpenTranscription, trailing = { transcription?.let { ReadyBadge(it.second) } })
            GroupDivider()
            val active = corrections.count { it.isActive }
            SettingsRow("Kamus koreksi", if (corrections.isEmpty()) "Kosong · tambahkan istilah yang sering salah" else "$active kata aktif · diterapkan otomatis", onClick = onOpenCorrections)
            GroupDivider()
            SettingsRow("Pemakaian AI", if (usages.isEmpty()) "Belum ada pemakaian" else "${usages.size} pemakaian tercatat", onClick = onOpenUsage)
        }

        SettingsGroup("Tampilan klip") {
            val captionStyles by container.database.styles().observeCaptionStyles().collectAsState(initial = emptyList())
            val frameStyles by container.database.styles().observeFrameStyles().collectAsState(initial = emptyList())
            val bumpers by container.database.bumpers().observeAll().collectAsState(initial = emptyList())
            SettingsRow("Gaya subtitle", "${captionStyles.size} gaya" + (captionStyles.firstOrNull { it.isDefault }?.let { " · bawaan: ${it.name}" } ?: ""), onClick = onOpenCaptionStyles)
            GroupDivider()
            SettingsRow("Gaya bingkai", "${frameStyles.size} gaya" + (frameStyles.firstOrNull { it.isDefault }?.let { " · bawaan: ${it.name}" } ?: ""), onClick = onOpenFrameStyles)
            GroupDivider()
            SettingsRow(
                "Intro & outro",
                if (bumpers.isEmpty()) "Belum ada video pembuka/penutup" else "${bumpers.count { it.kind == "intro" }} intro · ${bumpers.count { it.kind == "outro" }} outro",
                onClick = onOpenBumpers,
            )
            GroupDivider()
            SwitchRow("Rapikan audio saat render", "Buang dengung, kurangi noise ringan, samakan kekerasan ke -14 LUFS.", enhanceAudio, onCheckedChange = { on ->
                enhanceAudio = on
                scope.launch { container.settings.setEnhanceAudio(on) }
            })
        }

        SettingsGroup("Jadwal & statistik") {
            SettingsRow(
                "YouTube Data API",
                if (hasYoutubeKey) "Key tersimpan · statistik YouTube lewat API resmi" else "Opsional · tanpa key, YouTube dibaca lewat yt-dlp",
                onClick = { editingYoutubeKey = true },
                trailing = { ReadyBadge(hasYoutubeKey) },
            )
        }

        SettingsGroup("Data") {
            SettingsRow("Hasil render", "Galeri › Movies/ClipMI/<judul project>", onClick = onOpenResults)
            GroupDivider()
            SettingsRow("Data & penyimpanan", "Ruang HP, video sumber, cadangan, dan Paket Standar Tim", onClick = onOpenData)
        }

        SettingsGroup("Aplikasi") {
            SettingsRow(
                "Pengunduh YouTube",
                ytdlp?.let { "yt-dlp $it" } ?: "yt-dlp",
                trailing = {
                    SecondaryButton(if (updating) "Memperbarui…" else "Perbarui", enabled = !updating, height = 36.dp, onClick = {
                        updating = true
                        scope.launch {
                            notifier.show(
                                runCatching {
                                    val status = container.ytDlp.update()
                                    ytdlp = container.ytDlp.version()
                                    "yt-dlp $ytdlp " + if (status == "ALREADY_UP_TO_DATE") "sudah terbaru." else "diperbarui."
                                }.getOrElse { "Gagal memperbarui yt-dlp: ${it.message}" },
                            )
                            updating = false
                        }
                    })
                },
            )
            // Pembaruan dalam aplikasi hanya tampil di build yang punya repo rilis; alat uji hanya di build debug.
            if (BuildConfig.UPDATE_REPO.isNotBlank()) {
                GroupDivider()
                SettingsRow("Pembaruan aplikasi", "Versi ${BuildConfig.VERSION_NAME} · periksa rilis baru", onClick = { checkingUpdate = true })
            }
            if (BuildConfig.DEBUG) {
                GroupDivider()
                SettingsRow("Uji Fase 0", "Alat pengembang: uji yt-dlp, libass, dan render", onClick = onOpenLab)
            }
            GroupDivider()
            SettingsRow("Tentang ClipMI", "Versi ${BuildConfig.VERSION_NAME} · data hanya di HP ini · huruf Plus Jakarta Sans (OFL)")
        }
    }
}

@Composable
internal fun ReadyBadge(ready: Boolean) = StatusBadge(if (ready) "Siap" else "Belum", if (ready) Tone.Good else Tone.Idle)

/** Transkripsi API untuk video tanpa subtitle Indonesia (Groq/OpenAI Whisper atau server kompatibel). */
@Composable
fun TranscriptionSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var provider by remember { mutableStateOf(TranscriptionProvider.Groq) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (p, url, m) = container.settings.rawTranscription()
        provider = p ?: TranscriptionProvider.Groq
        baseUrl = url.orEmpty()
        model = m.orEmpty()
        hasKey = container.settings.hasKey(provider)
        auto = container.settings.autoTranscribe()
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Transkripsi API", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Untuk video tanpa subtitle Indonesia. Audio dikirim per 20 menit ke penyedia (Groq Whisper paling murah, ada paket gratis).",
                style = MaterialTheme.typography.bodyMedium,
                color = ClipMi.colors.textMuted,
            )
            OptionList(
                "Penyedia",
                TranscriptionProvider.entries,
                selected = provider,
                label = { it.label },
                onSelect = { provider = it; hasKey = container.settings.hasKey(it) },
            )
            Field(baseUrl, { baseUrl = it }, label = "Alamat API", placeholder = provider.baseUrl ?: "https://…/v1", singleLine = true, supporting = "Kosongkan untuk alamat bawaan penyedia.")
            Field(model, { model = it }, label = "Model", placeholder = provider.defaultModel ?: "nama model Whisper", singleLine = true)
            Field(
                apiKey, { apiKey = it },
                label = "API key",
                placeholder = if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder,
                supporting = "Disimpan terenkripsi di Android Keystore; tidak ikut cadangan.",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            ClipMiCard(padding = androidx.compose.foundation.layout.PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Transkripsi otomatis", "Untuk video tanpa subtitle, langsung setelah video selesai diunduh.", auto, onCheckedChange = { auto = it })
            }
            PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    container.settings.saveTranscription(provider, baseUrl, model, apiKey)
                    container.settings.setAutoTranscribe(auto)
                    apiKey = ""
                    hasKey = container.settings.hasKey(provider)
                    notifier.show(if (container.settings.transcription() != null) "Tersimpan. Transkripsi API siap dipakai." else "Tersimpan, tetapi belum lengkap (alamat, model, atau API key).")
                }
            })
            if (hasKey) {
                GhostButton("Hapus API key ${provider.label}", danger = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    container.settings.removeKey(provider)
                    hasKey = false
                    notifier.show("API key ${provider.label} dihapus dari HP ini.")
                })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * API key YouTube Data API v3 untuk statistik YouTube resmi (opsional). Disimpan di Keystore dan tidak pernah
 * ditampilkan lagi; tanpa key, statistik YouTube dibaca lewat yt-dlp.
 */
@Composable
private fun YoutubeKeySheet(container: AppContainer, hasKey: Boolean, onDone: () -> Unit) {
    val notifier = LocalNotifier.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    id.clipmi.android.ui.components.ClipMiSheet(onDone, title = "YouTube Data API", subtitle = "Tayangan, suka, dan komentar YouTube lewat jalur resmi, tanpa login.") {
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
                container.settings.saveYoutubeKey(key)
                notifier.show("API key YouTube tersimpan.")
                onDone()
            }
        })
        if (hasKey) GhostButton("Hapus key", danger = true, onClick = {
            container.settings.removeYoutubeKey()
            notifier.show("API key YouTube dihapus. Statistik YouTube kembali lewat yt-dlp.")
            onDone()
        })
    }
}

/**
 * Pembaruan aplikasi: rilis terbaru di GitHub diperiksa, APK-nya diunduh dan diperiksa, lalu pemasang sistem dibuka.
 * Izin "Pasang aplikasi tidak dikenal" dinyalakan pengguna sendiri di halaman sistem yang dibuka.
 */
@Composable
private fun UpdateSheet(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { id.clipmi.android.domain.AppUpdater(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>("Memeriksa rilis terbaru…") }
    var release by remember { mutableStateOf<id.clipmi.android.domain.AppUpdater.Release?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var apk by remember { mutableStateOf<java.io.File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { updater.check() }
            .onSuccess { release = it; state = if (it == null) "ClipMI ${updater.currentVersion} sudah versi terbaru." else null }
            .onFailure { error = it.message; state = null }
    }
    id.clipmi.android.ui.components.ClipMiSheet(onDone, title = "Pembaruan aplikasi", subtitle = "Terpasang: versi ${updater.currentVersion}") {
        state?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.text) }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.bad) }
        release?.let { r ->
            Text("Versi ${r.version} tersedia" + (r.size?.let { " (${id.clipmi.android.ui.components.formatBytes(it)})" } ?: ""), style = MaterialTheme.typography.titleMedium, color = ClipMi.colors.text)
            if (r.notes.isNotBlank()) Text(r.notes.take(1200), style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textMuted)
            progress?.let { id.clipmi.android.ui.components.ProgressBar(it / 100f) }
            val file = apk
            when {
                file != null && !updater.canInstall() -> {
                    Text("Izinkan ClipMI memasang aplikasi (\"Pasang aplikasi tidak dikenal\") di halaman sistem, lalu kembali dan ketuk Pasang.", style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.warn)
                    SecondaryButton("Buka izin pemasangan", onClick = { context.startActivity(updater.permissionIntent()) })
                    PrimaryButton("Pasang", onClick = { if (updater.canInstall()) context.startActivity(updater.installIntent(file)) }, modifier = Modifier.fillMaxWidth())
                }
                file != null -> PrimaryButton("Pasang versi ${r.version}", onClick = { context.startActivity(updater.installIntent(file)) }, modifier = Modifier.fillMaxWidth())
                else -> PrimaryButton(if (progress != null) "Mengunduh…" else "Unduh pembaruan", enabled = progress == null, modifier = Modifier.fillMaxWidth(), onClick = {
                    progress = 0
                    error = null
                    scope.launch {
                        runCatching { updater.download(r) { p -> progress = p } }
                            .onSuccess { apk = it }
                            .onFailure { error = it.message }
                        progress = null
                    }
                })
            }
        }
    }
}
