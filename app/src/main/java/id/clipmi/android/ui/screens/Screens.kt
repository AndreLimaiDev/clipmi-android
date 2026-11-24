package id.clipmi.android.ui.screens

import id.clipmi.core.schedule.dayMonthTime

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import id.clipmi.core.schedule.IdDate
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.ui.components.formatBytes
import id.clipmi.android.ui.components.ActionChip
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
import kotlinx.coroutines.flow.first
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
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    val usages by container.database.aiUsages().observeAll().collectAsState(initial = emptyList())
    val corrections by container.database.corrections().observeAll().collectAsState(initial = emptyList())
    var ytdlp by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var enhanceAudio by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enhanceAudio = container.settings.enhanceAudio() }
    LaunchedEffect(Unit) { ytdlp = runCatching { container.ytDlp.version() }.getOrNull() }
    var hasYoutubeKey by remember { mutableStateOf(container.settings.hasYoutubeKey()) }
    var editingYoutubeKey by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    if (checkingUpdate) UpdateSheet(container, onDone = { checkingUpdate = false })
    val newer by container.updates.latest.collectAsState(initial = null)
    val checkedAt by container.updates.checkedAt.collectAsState(initial = null)
    if (editingYoutubeKey) YoutubeKeySheet(container, hasYoutubeKey, onDone = { editingYoutubeKey = false; hasYoutubeKey = container.settings.hasYoutubeKey() })

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = FloatingNavSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Pengaturan")

        SettingsGroup("AI & transkrip") {
            SettingsRow("Analisis AI", analysis?.first, onClick = onOpenAnalysis, trailing = { analysis?.let { ReadyMark(it.second) } })
            GroupDivider()
            SettingsRow("Transkripsi API", transcription?.first, onClick = onOpenTranscription, trailing = { transcription?.let { ReadyMark(it.second) } })
            GroupDivider()
            val active = corrections.count { it.isActive }
            SettingsRow("Kamus koreksi", if (corrections.isEmpty()) "Kosong" else "$active kata aktif", onClick = onOpenCorrections)
            GroupDivider()
            SettingsRow("Pemakaian AI", if (usages.isEmpty()) "Belum ada" else "${usages.size} pemakaian", onClick = onOpenUsage)
        }

        SettingsGroup("Tampilan klip") {
            val captionStyles by container.database.styles().observeCaptionStyles().collectAsState(initial = emptyList())
            val frameStyles by container.database.styles().observeFrameStyles().collectAsState(initial = emptyList())
            val bumpers by container.database.bumpers().observeAll().collectAsState(initial = emptyList())
            // Gaya subtitle dan bingkai: satu menu dengan dua tab.
            SettingsRow("Gaya klip", "${captionStyles.size} subtitle · ${frameStyles.size} bingkai", onClick = onOpenCaptionStyles)
            GroupDivider()
            SettingsRow(
                "Intro & outro",
                if (bumpers.isEmpty()) "Belum ada" else "${bumpers.count { it.kind == "intro" }} intro · ${bumpers.count { it.kind == "outro" }} outro",
                onClick = onOpenBumpers,
            )
            GroupDivider()
            SwitchRow("Rapikan audio saat render", "Kurangi noise, samakan volume", enhanceAudio, onCheckedChange = { on ->
                enhanceAudio = on
                scope.launch { container.settings.setEnhanceAudio(on) }
            })
        }

        SettingsGroup("Jadwal & statistik") {
            SettingsRow(
                "YouTube Data API",
                if (hasYoutubeKey) "Key tersimpan" else "Opsional",
                onClick = { editingYoutubeKey = true },
                trailing = { ReadyMark(hasYoutubeKey) },
            )
        }

        SettingsGroup("Data") {
            val rendered by container.database.clips().observeRendered().collectAsState(initial = emptyList())
            val used by produceState<Long?>(null) {
                value = withContext(Dispatchers.IO) { container.database.projects().all().sumOf { container.workspace.projectSize(it.id) } }
            }
            val done = rendered.count { it.render == RenderStatus.Done }
            SettingsRow("Hasil render", if (done == 0) "Belum ada" else "$done video", onClick = onOpenResults)
            GroupDivider()
            SettingsRow("Data & penyimpanan", used?.let { "Terpakai ${formatBytes(it)}" } ?: "Menghitung…", onClick = onOpenData)
        }

        SettingsGroup("Aplikasi") {
            SettingsRow(
                "Pengunduh YouTube",
                ytdlp?.let { "yt-dlp $it" } ?: "yt-dlp",
                trailing = {
                    ActionChip(if (updating) "Memperbarui…" else "Perbarui", enabled = !updating, height = 36.dp, onClick = {
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
                SettingsRow(
                    "Pembaruan aplikasi",
                    newer?.let { "Versi ${it.version} tersedia" }
                        ?: ("Versi ${BuildConfig.VERSION_NAME}" + (checkedAt?.toLongOrNull()?.let { " · dicek ${IdDate.dayMonthTime(id.clipmi.android.domain.Wall.of(it))}" } ?: "")),
                    onClick = { checkingUpdate = true },
                    trailing = if (newer != null) ({ StatusBadge("Baru", Tone.Good) }) else ({ id.clipmi.android.ui.components.ChevronIcon() }),
                )
            }
            if (BuildConfig.DEBUG) {
                GroupDivider()
                SettingsRow("Uji Fase 0", "Alat pengembang", onClick = onOpenLab)
            }
            GroupDivider()
            SettingsRow("Tentang ClipMI", "Versi ${BuildConfig.VERSION_NAME} · data hanya di HP ini")
        }
    }
}

@Composable
internal fun ReadyBadge(ready: Boolean) = StatusBadge(if (ready) "Siap" else "Belum", if (ready) Tone.Good else Tone.Idle)

/** Tanda siap di baris pengaturan: lingkaran centang hijau, atau "Belum" redup. */
@Composable
internal fun ReadyMark(ready: Boolean) {
    if (ready) Icon(ClipMiIcons.CircleCheck, contentDescription = "Siap", tint = ClipMi.colors.accent, modifier = Modifier.size(24.dp))
    else Text("Belum", style = MaterialTheme.typography.labelMedium, color = ClipMi.colors.textMuted)
}

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
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Untuk video tanpa subtitle Indonesia · Groq paling murah",
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
            Field(baseUrl, { baseUrl = it }, label = "Alamat API", placeholder = provider.baseUrl ?: "https://…/v1", singleLine = true, supporting = "Kosong = alamat bawaan")
            Field(model, { model = it }, label = "Model", placeholder = provider.defaultModel ?: "nama model Whisper", singleLine = true)
            Field(
                apiKey, { apiKey = it },
                label = "API key",
                placeholder = if (hasKey) "Tersimpan (isi untuk mengganti)" else provider.keyPlaceholder,
                supporting = "Terenkripsi di HP · tidak ikut cadangan",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            ClipMiCard(padding = androidx.compose.foundation.layout.PaddingValues(0.dp), spacing = 0.dp) {
                SwitchRow("Transkripsi otomatis", "Setelah video tanpa subtitle selesai diunduh", auto, onCheckedChange = { auto = it })
            }
            if (hasKey) {
                GhostButton("Hapus API key ${provider.label}", danger = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    container.settings.removeKey(provider)
                    hasKey = false
                    notifier.show("API key ${provider.label} dihapus dari HP ini.")
                })
            }
            Spacer(Modifier.height(8.dp))
        }
        // Simpan tetap di bawah layar.
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)) {
        PrimaryButton("Simpan", modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                container.settings.saveTranscription(provider, baseUrl, model, apiKey)
                container.settings.setAutoTranscribe(auto)
                apiKey = ""
                hasKey = container.settings.hasKey(provider)
                notifier.show(if (container.settings.transcription() != null) "Tersimpan. Transkripsi API siap dipakai." else "Tersimpan, tetapi belum lengkap (alamat, model, atau API key).")
            }
        })
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
internal fun UpdateSheet(container: AppContainer, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { id.clipmi.android.domain.AppUpdater(context) }
    val scope = rememberCoroutineScope()
    val c = ClipMi.colors
    var checking by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<id.clipmi.android.domain.AppUpdater.Release?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var canInstall by remember { mutableStateOf(updater.canInstall()) }
    // Unduhan berjalan di latar (WorkManager); progres dan hasilnya dibaca dari sini.
    val work by remember { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(id.clipmi.android.work.UpdateQueue.DOWNLOAD) }
        .collectAsState(initial = emptyList())
    val info = work.lastOrNull()
    // Unduhan otomatis yang masih menunggu Wi-Fi bukan "sedang mengunduh": pengguna boleh mengunduhnya sekarang.
    val waitingWifi = info?.state == androidx.work.WorkInfo.State.ENQUEUED && info.constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED
    val running = !waitingWifi && (info?.state == androidx.work.WorkInfo.State.RUNNING || info?.state == androidx.work.WorkInfo.State.ENQUEUED)
    val progress = info?.progress?.getInt(id.clipmi.android.work.UpdateQueue.KEY_PROGRESS, -1)?.takeIf { it >= 0 }
    val failed = info?.takeIf { it.state == androidx.work.WorkInfo.State.FAILED }?.outputData?.getString(id.clipmi.android.work.UpdateQueue.KEY_ERROR)
    LaunchedEffect(Unit) {
        // Rilis yang sudah diketahui (mungkin sudah diunduh di latar) langsung tampil; cek ulang tetap jalan, dan gagal
        // terhubung tidak menyembunyikan APK yang sudah siap.
        container.updates.latest.first()?.let { release = it; checking = false }
        runCatching { updater.check() }
            .onSuccess { release = it; container.updates.saveCheck(it) }
            .onFailure { if (release == null) error = it.message }
        checking = false
    }
    val readyApk = release?.let { r -> if (running) null else updater.downloaded(r.version) }
    // Kembali dari halaman izin sistem: periksa ulang izinnya.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canInstall = updater.canInstall()
        onPauseOrDispose { }
    }
    id.clipmi.android.ui.components.ClipMiSheet(
        onDone,
        title = if (readyApk != null) "Pembaruan siap dipasang" else "Pembaruan aplikasi",
        subtitle = "Terpasang: versi ${updater.currentVersion}",
    ) {
        when {
            checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                Text("Memeriksa rilis terbaru…", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            error != null -> Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.bad)
            release == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(ClipMiIcons.CircleCheck, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Text("Sudah versi terbaru.", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        release?.let { r ->
            val apk = readyApk
            Text("Versi ${r.version}" + (r.size?.let { " · ${id.clipmi.android.ui.components.formatBytes(it)}" } ?: ""), style = MaterialTheme.typography.titleMedium, color = c.text)
            ReleaseNotes(r.notes)
            failed?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.bad) }
            when {
                running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(progress?.let { "Mengunduh $it%" } ?: "Menyiapkan unduhan…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    id.clipmi.android.ui.components.ProgressBar(progress?.let { it / 100f })
                }
                apk != null && !canInstall -> {
                    Text("Izinkan ClipMI memasang aplikasi di halaman sistem, lalu kembali ke sini.", style = MaterialTheme.typography.bodySmall, color = c.warn)
                    PrimaryButton("Buka izin pemasangan", onClick = { context.startActivity(updater.permissionIntent()) }, modifier = Modifier.fillMaxWidth())
                }
                apk != null -> PrimaryButton("Pasang versi ${r.version}", onClick = { context.startActivity(updater.installIntent(apk)) }, modifier = Modifier.fillMaxWidth())
                waitingWifi -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Akan diunduh otomatis saat tersambung Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    PrimaryButton("Unduh sekarang", onClick = { id.clipmi.android.work.UpdateQueue.download(context, r) }, icon = ClipMiIcons.Download, modifier = Modifier.fillMaxWidth())
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    id.clipmi.android.ui.components.ActionChip("Nanti", {
                        scope.launch { container.updates.dismiss(r.version) }
                        onDone()
                    }, Modifier.weight(1f), height = 48.dp)
                    PrimaryButton("Unduh", onClick = { id.clipmi.android.work.UpdateQueue.download(context, r) }, icon = ClipMiIcons.Download, modifier = Modifier.weight(1.4f))
                }
            }
        }
    }
}

/** Catatan rilis tanpa baris SHA-256 (hanya untuk pemeriksaan berkas). */
@Composable
private fun ReleaseNotes(notes: String) {
    val clean = notes.lines().filterNot { Regex("\\b[0-9a-fA-F]{64}\\b").containsMatchIn(it) }.joinToString("\n").trim()
    if (clean.isNotBlank()) Text(clean.take(1500), style = MaterialTheme.typography.bodySmall, color = ClipMi.colors.textBody)
}

/** Catatan "yang baru" sekali setelah aplikasi diperbarui. */
@Composable
internal fun WhatsNewSheet(release: id.clipmi.android.domain.AppUpdater.Release, onDone: () -> Unit) {
    id.clipmi.android.ui.components.ClipMiSheet(onDone, title = "Yang baru di ${release.version}") {
        ReleaseNotes(release.notes)
        PrimaryButton("Oke", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}
