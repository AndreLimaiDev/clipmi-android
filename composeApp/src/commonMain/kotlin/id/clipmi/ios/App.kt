package id.clipmi.ios

import id.clipmi.core.analysis.ClipImporter
import id.clipmi.core.analysis.ChatPaste
import id.clipmi.android.ui.components.SwitchRow
import id.clipmi.android.ui.components.ScreenFrame
import id.clipmi.android.ui.components.ReadableWidth
import id.clipmi.android.ui.components.isSideBySide
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.NavigationEventInfo
import id.clipmi.core.schedule.ScheduleState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ClipSource
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.data.ReviewStatus
import id.clipmi.android.data.TranscriptSegmentEntity
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.ClipMiSheet
import id.clipmi.android.ui.components.ClipMiSnackbarHost
import id.clipmi.android.ui.components.ConfirmDialog
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.Field
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.components.GroupDivider
import id.clipmi.android.ui.components.IconBadge
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.Notifier
import id.clipmi.android.ui.components.PrimaryButton
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.ScreenHeader
import id.clipmi.android.ui.components.SearchField
import id.clipmi.android.ui.components.SecondaryButton
import id.clipmi.android.ui.components.SegmentedTabs
import id.clipmi.android.ui.components.SettingsGroup
import id.clipmi.android.ui.components.SettingsRow
import id.clipmi.android.ui.components.SnackbarInset
import id.clipmi.android.ui.components.StatusBadge
import id.clipmi.android.ui.components.Tone
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.android.ui.theme.ClipMiDarkSurface
import id.clipmi.android.ui.theme.ClipMiTheme
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.transcript.Timecode
import id.clipmi.core.youtube.TranscriptSource
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Empat tab utama, sama dengan navigasi Android. */
private enum class MainTab(val label: String, val icon: ImageVector) {
    Projects("Project", ClipMiIcons.Home),
    Schedule("Jadwal", ClipMiIcons.Calendar),
    Stats("Statistik", ClipMiIcons.Chart),
    Settings("Pengaturan", ClipMiIcons.Sliders),
}

/** Layar di atas tab utama (navigasi pil disembunyikan). */
private sealed interface Route {
    data class Project(val id: Long) : Route
    data class Editor(val clipId: Long) : Route
    data object Backup : Route
    data class ScheduleEdit(val id: Long = 0, val clipId: Long = 0, val date: LocalDate? = null) : Route
    data class Ready(val id: Long) : Route
    data object Accounts : Route
    data object AiSettings : Route
    data object TranscriptionSettings : Route
    data object Performance : Route
    data object Bumpers : Route
    data class Styles(val tab: Int = 0) : Route
    data class CaptionStyleEdit(val id: Long, val copyOf: Long = 0) : Route
    data class FrameStyleEdit(val id: Long, val copyOf: Long = 0) : Route
    data object TeamPack : Route
    data object Corrections : Route
    data object AiUsage : Route
    data object Results : Route
    data object Data : Route
}

@Composable
fun App(repo: Repository, engine: MediaEngine) = ClipMiTheme {
    val scope = rememberCoroutineScope()
    val settings = remember { IosSettings(repo, engine) }
    val ai = remember { IosAiClient(engine) }
    val analysis = remember { IosAnalysis(repo, settings, ai, scope) }
    val renders = remember { Renders(IosRenderer(repo, engine), scope) }
    val transcriber = remember { IosTranscriber(repo, engine, renders.renderer, settings, analysis, scope) }
    // Begitu unduhan selesai (bila diaktifkan): transkripsi API untuk video tanpa subtitle, atau analisis bila transkrip siap.
    val downloads = remember {
        Downloads(repo, engine, scope).also { it.onFinished = { id -> if (!transcriber.startAutomatically(id)) analysis.startAutomatically(id) } }
    }
    val scheduler = remember { IosScheduler(repo, engine) { settings.youtubeKey() } }
    val performance = remember { IosPerformance(repo, renders.renderer, scheduler, settings, ai, scope) }
    val bumpers = remember { IosBumpers(repo, renders.renderer) }
    val localVideos = remember { LocalVideos(repo, renders.renderer) }
    val styleMedia = remember { StyleMedia(repo, renders.renderer) }
    val teamPacks = remember { IosTeamPacks(repo, renders.renderer, settings, bumpers) }
    val notifier = remember { Notifier(SnackbarHostState(), scope) }
    // Dialog dan lembar asli iOS untuk ClipMiSheet/ConfirmDialog (modul :ui).
    remember(engine) { installNativeUi(engine) }
    var tab by remember { mutableStateOf(MainTab.Projects) }
    // Tumpukan layar di atas tab (kembali = ambil yang teratas).
    var stack by remember { mutableStateOf(listOf<Route>()) }
    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BusyKeeper(engine) {
        downloads.active != null || renders.active != null || transcriber.active != null || analysis.active != null || localVideos.busy ||
            performance.running
    }
    // Bilah tab asli iOS (UITabBar, Liquid Glass di iOS 26): Kotlin menentukan tab aktif dan kapan bilah tampil.
    LaunchedEffect(Unit) {
        engine.onTabSelected(object : TabListener {
            override fun selected(index: Int) {
                tab = MainTab.entries.getOrElse(index) { MainTab.Projects }
            }
        })
    }
    LaunchedEffect(tab, stack.isEmpty()) { engine.setTabBar(tab.ordinal, stack.isEmpty()) }
    // Ketuk pengingat jadwal: langsung ke halaman siap posting jadwal itu, di atas tab Jadwal.
    LaunchedEffect(Unit) {
        engine.onReminderTapped(object : ResultListener {
            override fun finished(resultJson: String) {
                val id = resultJson.toLongOrNull() ?: return
                tab = MainTab.Schedule
                stack = listOf(Route.Ready(id))
            }
        })
    }
    LaunchedEffect(Unit) {
        finishRestore(repo)?.let { notifier.show(it) }
        repo.recoverInterrupted()
        performance.recoverInterrupted()
    }

    CompositionLocalProvider(LocalNotifier provides notifier, LocalMediaEngine provides engine) {
        val focusManager = LocalFocusManager.current
        // Ketuk di luar kolom teks (bagian yang tidak menangkap ketukan) menutup keyboard.
        Box(Modifier.fillMaxSize().background(ClipMi.colors.bg).pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }) {
            /** Isi satu halaman: halaman di atas tumpukan, atau tab utama bila tumpukan kosong. */
            @Composable
            fun Page(top: Route?) {
                    when (top) {
                    // Editor selalu gelap, sama dengan Android.
                    is Route.Editor -> ClipMiDarkSurface {
                        Box(Modifier.fillMaxSize().background(ClipMi.colors.bg).safeDrawingPadding()) {
                            ClipEditorScreen(repo, renders, top.clipId, onBack = pop, onEditStyle = { caption, id ->
                                push(if (caption) Route.CaptionStyleEdit(id) else Route.FrameStyleEdit(id))
                            })
                        }
                    }
                    is Route.Project -> ProjectScreen(
                        repo, engine, downloads, renders, analysis, transcriber, localVideos, top.id, onBack = pop, onEdit = { push(Route.Editor(it)) }, onSchedule = { push(Route.ScheduleEdit(clipId = it)) },
                    )
                    Route.Backup -> Box(Modifier.statusBarsPadding()) { BackupScreen(repo, engine, onBack = pop) }
                    is Route.ScheduleEdit -> Box(Modifier.fillMaxSize()) {
                        ScheduleEditScreen(repo, engine, scheduler, top.id, top.clipId, top.date, onBack = pop, onSaved = { saved ->
                            // Jadwal baru: kembali ke asal. Diubah: kembali ke Siap Unggah. Dihapus: tutup juga Siap Unggah-nya.
                            stack = if (saved == 0L) stack.dropLast(1).dropLastWhile { it is Route.Ready } else stack.dropLast(1)
                        })
                    }
                    is Route.Ready -> Box(Modifier.fillMaxSize()) {
                        ReadyScreen(
                            repo, engine, scheduler, renders, top.id, onBack = pop, onEdit = { push(Route.ScheduleEdit(id = top.id)) },
                            onOpened = { other -> stack = stack.dropLast(1) + Route.Ready(other) },
                        )
                    }
                    Route.Accounts -> Box(Modifier.fillMaxSize()) { AccountsScreen(repo, scheduler, onBack = pop) }
                    Route.AiSettings -> Box(Modifier.fillMaxSize()) { AnalysisSettingsScreen(settings, ai, onBack = pop) }
                    Route.TranscriptionSettings -> Box(Modifier.fillMaxSize()) { TranscriptionSettingsScreen(settings, onBack = pop) }
                    Route.Bumpers -> Box(Modifier.fillMaxSize()) { BumpersScreen(repo, bumpers, onBack = pop) }
                    Route.TeamPack -> Box(Modifier.fillMaxSize()) { TeamPackScreen(teamPacks, onBack = pop) }
                    Route.Corrections -> Box(Modifier.fillMaxSize()) { CorrectionsScreen(repo, onBack = pop) }
                    Route.AiUsage -> Box(Modifier.fillMaxSize()) { AiUsageScreen(repo, onBack = pop) }
                    Route.Data -> Box(Modifier.fillMaxSize()) { DataScreen(repo, downloads, localVideos, onBack = pop) }
                    Route.Results -> Box(Modifier.fillMaxSize()) {
                        ResultsScreen(
                            repo, engine, renders, onBack = pop,
                            onOpenProject = { push(Route.Project(it)) }, onOpenEditor = { push(Route.Editor(it)) },
                            onSchedule = { push(Route.ScheduleEdit(clipId = it)) }, onOpenSchedule = { push(Route.Ready(it)) },
                        )
                    }
                    is Route.Styles -> Box(Modifier.fillMaxSize()) {
                        StylesScreen(
                            repo, renders.renderer, top.tab, onTab = { stack = stack.dropLast(1) + Route.Styles(it) }, onBack = pop,
                            onEdit = { caption, id, copyOf -> push(if (caption) Route.CaptionStyleEdit(id, copyOf) else Route.FrameStyleEdit(id, copyOf)) },
                        )
                    }
                    is Route.CaptionStyleEdit -> Box(Modifier.fillMaxSize()) {
                        CaptionStyleEditScreen(repo, renders.renderer, top.id, top.copyOf, onBack = pop, onDuplicate = { from -> stack = stack.dropLast(1) + Route.CaptionStyleEdit(0, from) })
                    }
                    is Route.FrameStyleEdit -> Box(Modifier.fillMaxSize()) {
                        FrameStyleEditScreen(repo, renders.renderer, styleMedia, top.id, top.copyOf, onBack = pop, onDuplicate = { from -> stack = stack.dropLast(1) + Route.FrameStyleEdit(0, from) })
                    }
                    Route.Performance -> Box(Modifier.fillMaxSize()) { PerformanceScreen(repo, performance, settings, onBack = pop, onOpenSchedule = { push(Route.Ready(it)) }) }
                    null -> when (tab) {
                        MainTab.Projects -> ProjectListScreen(repo, engine, downloads, renders, localVideos, onOpen = { push(Route.Project(it)) }, onOpenResults = { push(Route.Results) }, onLocalCreated = { id ->
                            push(Route.Project(id))
                            scope.launch { transcriber.startAutomatically(id) }
                        })
                        MainTab.Schedule -> ScheduleScreen(
                            repo, engine, scheduler, renders,
                            onOpenAccounts = { push(Route.Accounts) },
                            onOpenSchedule = { push(Route.Ready(it)) },
                            onNewSchedule = { clip, date -> push(Route.ScheduleEdit(clipId = clip, date = date)) },
                        )
                        MainTab.Stats -> StatsScreen(repo, engine, scheduler, downloads, onOpenSchedule = { push(Route.Ready(it)) }, onOpenPerformance = { push(Route.Performance) })
                        MainTab.Settings -> SettingsScreen(repo, settings, engine, downloads, onAnalysis = { push(Route.AiSettings) }, onTranscription = { push(Route.TranscriptionSettings) }, onBumpers = { push(Route.Bumpers) }, onStyles = { push(Route.Styles()) }, onTeamPack = { push(Route.TeamPack) }, onBackup = { push(Route.Backup) }, open = push)
                    }
                }
            }

            /** Halaman beserta bingkainya: selain editor dan project, isi dibatasi lebarnya di tengah (iPad). */
            @Composable
            fun Screen(top: Route?) {
                if (top !is Route.Editor && top !is Route.Project) {
                    ScreenFrame { Page(top) }
                } else {
                    Page(top)
                }
            }

            // Usap dari tepi kiri untuk kembali (gestur bawaan iOS, lewat NavigationBackHandler). Selama digeser,
            // halaman di bawahnya ikut tampil; kunci per posisi tumpukan menjaga keadaan halaman itu setelah kembali.
            // Editor dan form yang bisa kehilangan isian tidak memakai gestur ini (pakai tombol kembali/tutup).
            val current = stack.lastOrNull()
            val swipeBack = current != null && current !is Route.Editor && current !is Route.CaptionStyleEdit &&
                current !is Route.FrameStyleEdit && current !is Route.ScheduleEdit
            val backState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(state = backState, isBackEnabled = swipeBack, onBackCancelled = {}, onBackCompleted = { pop() })
            val progress = (backState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = constraints.maxWidth.toFloat()
                if (progress > 0f) {
                    key(stack.size - 1, stack.getOrNull(stack.size - 2)) {
                        Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) { Screen(stack.getOrNull(stack.size - 2)) }
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f * (1f - progress))))
                }
                key(stack.size, current) {
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = width * progress }) { Screen(current) }
                }
            }
            // Navigasi utama = bilah tab asli iOS di atas layar Compose (lihat RootController di iosApp).
            val topLevel = stack.isEmpty()
            if (topLevel) SnackbarInset(FloatingNavSpace - 16.dp)
            ClipMiSnackbarHost(notifier, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Selama ada proses panjang, layar tidak terkunci otomatis (iOS menghentikan aplikasi yang terkunci) dan iOS memberi waktu
 * tambahan singkat bila aplikasi ditinggal. Dipisah supaya perubahan status tidak menyusun ulang seluruh aplikasi.
 */
@Composable
private fun BusyKeeper(engine: MediaEngine, busy: () -> Boolean) {
    val now = busy()
    LaunchedEffect(now) { engine.setBusy(now) }
}

@Composable
private fun SettingsScreen(repo: Repository, settings: IosSettings, engine: MediaEngine, downloads: Downloads, onAnalysis: () -> Unit, onTranscription: () -> Unit, onBumpers: () -> Unit, onStyles: () -> Unit, onTeamPack: () -> Unit, onBackup: () -> Unit, open: (Route) -> Unit) {
    val corrections by remember { repo.db.corrections().observeAll() }.collectAsState(emptyList())
    val usages by remember { repo.db.aiUsages().observeAll() }.collectAsState(emptyList())
    val rendered by remember { repo.db.clips().observeRendered() }.collectAsState(emptyList())
    val bumperList by remember { repo.db.bumpers().observeAll() }.collectAsState(emptyList())
    val captionStyles by remember { repo.db.styles().observeCaptionStyles() }.collectAsState(emptyList())
    val frameStyles by remember { repo.db.styles().observeFrameStyles() }.collectAsState(emptyList())
    val transcription by produceState<Pair<String, Boolean>?>(null) {
        val cfg = settings.transcription()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    val analysis by produceState<Pair<String, Boolean>?>(null) {
        val cfg = settings.analysis()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    var hasYoutubeKey by remember { mutableStateOf(settings.youtubeKey() != null) }
    val scope = rememberCoroutineScope()
    var enhanceAudio by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enhanceAudio = repo.db.settings().get(IosRenderer.KEY_ENHANCE_AUDIO) == "1" }
    var editingYoutubeKey by remember { mutableStateOf(false) }
    if (editingYoutubeKey) YoutubeKeySheet(settings, hasYoutubeKey, onDone = { editingYoutubeKey = false; hasYoutubeKey = settings.youtubeKey() != null })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = FloatingNavSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("Pengaturan", Modifier.statusBarsPadding().padding(top = 12.dp)) }
        item {
            SettingsGroup("AI & transkrip") {
                SettingsRow("Transkripsi API", transcription?.first, onClick = onTranscription, trailing = { transcription?.let { ReadyMark(it.second) } })
                GroupDivider()
                SettingsRow("Analisis AI", analysis?.first, onClick = onAnalysis, trailing = { analysis?.let { ReadyMark(it.second) } })
                GroupDivider()
                SettingsRow("Kamus koreksi", if (corrections.isEmpty()) "Kosong" else "${corrections.count { it.isActive }} kata aktif", onClick = { open(Route.Corrections) })
                GroupDivider()
                SettingsRow("Pemakaian AI", if (usages.isEmpty()) "Belum ada" else "${usages.size} pemakaian", onClick = { open(Route.AiUsage) })
            }
        }
        item {
            SettingsGroup("Render") {
                SettingsRow("Gaya klip", "${captionStyles.size} subtitle · ${frameStyles.size} bingkai", onClick = onStyles)
                GroupDivider()
                SettingsRow(
                    "Intro & outro",
                    if (bumperList.isEmpty()) "Belum ada" else "${bumperList.count { it.kind == "intro" }} intro · ${bumperList.count { it.kind == "outro" }} outro",
                    onClick = onBumpers,
                )
                GroupDivider()
                // Kunci render.enhance_audio sama dengan Android dan PC. Di HP bawaan mati; nyalakan untuk audio yang bising.
                SwitchRow("Rapikan audio saat render", "Kurangi noise, samakan volume", enhanceAudio, onCheckedChange = { on ->
                    enhanceAudio = on
                    scope.launch { repo.db.settings().put(IosRenderer.KEY_ENHANCE_AUDIO, if (on) "1" else "0") }
                })
            }
        }
        item {
            SettingsGroup("Jadwal & statistik") {
                SettingsRow("YouTube Data API", if (hasYoutubeKey) "Key tersimpan" else "Opsional", onClick = { editingYoutubeKey = true }, trailing = { ReadyMark(hasYoutubeKey) })
            }
        }
        item {
            SettingsGroup("Data") {
                val done = rendered.count { it.render == RenderStatus.Done }
                SettingsRow("Hasil render", if (done == 0) "Belum ada" else "$done video", onClick = { open(Route.Results) })
                GroupDivider()
                SettingsRow("Data & penyimpanan", "Sisa ruang, video sumber per project", onClick = { open(Route.Data) })
                GroupDivider()
                SettingsRow("Cadangkan & pulihkan", "Satu file ZIP, sama dengan ClipMI Android", onClick = onBackup)
                GroupDivider()
                SettingsRow("Paket Standar Tim", "Gaya, prompt, intro/outro, dan kamus untuk tim", onClick = onTeamPack)
            }
        }
        item {
            SettingsGroup("Aplikasi") {
                YtDlpRow(engine, downloads)
                GroupDivider()
                SettingsRow("ClipMI iOS", "Versi uji · dipasang lewat sideload")
            }
        }
    }
}

/**
 * Pengunduh YouTube: versi yt-dlp aktif, Perbarui (dari PyPI, langsung dipakai), dan kembali ke versi bawaan bila
 * versi unduhan bermasalah. Tidak bisa diperbarui selama ada unduhan berjalan.
 */
@Composable
private fun YtDlpRow(engine: MediaEngine, downloads: Downloads) {
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val updater = remember(engine) { YtDlpUpdater(engine) }
    var info by remember { mutableStateOf<YtDlpUpdater.Info?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(updater) { info = updater.info() }
    val current = info
    SettingsRow(
        "Pengunduh YouTube",
        current?.version?.let { "yt-dlp $it" + if (current.updated) " · diperbarui" else " · bawaan" } ?: "yt-dlp",
        trailing = {
            Box {
                ActionChip(if (updating) "Memperbarui…" else "Perbarui", enabled = !updating, height = 36.dp, onClick = {
                    if (downloads.active != null) {
                        notifier.show("Tunggu unduhan video selesai dulu.")
                        return@ActionChip
                    }
                    if (current?.updated == true) {
                        engine.actionSheet("Pengunduh YouTube", listOf(
                            NativeMenuItem("Cek versi terbaru", "arrow.clockwise") {
                                updating = true
                                scope.launch {
                                    notifier.show(
                                        when (val r = updater.update()) {
                                            is YtDlpUpdater.Result.Updated -> "yt-dlp ${r.version} diperbarui."
                                            is YtDlpUpdater.Result.UpToDate -> "yt-dlp ${r.version} sudah terbaru."
                                            is YtDlpUpdater.Result.Failed -> "Gagal memperbarui yt-dlp: ${r.message}"
                                        },
                                    )
                                    info = updater.info()
                                    updating = false
                                }
                            },
                            NativeMenuItem("Kembali ke versi bawaan", "arrow.uturn.backward", destructive = true) {
                                scope.launch {
                                    val version = updater.reset()
                                    notifier.show("Memakai yt-dlp bawaan${version?.let { " $it" }.orEmpty()}.")
                                    info = updater.info()
                                }
                            },
                        ))
                        return@ActionChip
                    }
                    updating = true
                    scope.launch {
                        notifier.show(
                            when (val r = updater.update()) {
                                is YtDlpUpdater.Result.Updated -> "yt-dlp ${r.version} diperbarui."
                                is YtDlpUpdater.Result.UpToDate -> "yt-dlp ${r.version} sudah terbaru."
                                is YtDlpUpdater.Result.Failed -> "Gagal memperbarui yt-dlp: ${r.message}"
                            },
                        )
                        info = updater.info()
                        updating = false
                    }
                })
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Daftar project (padanan ProjectListScreen Android)
// ---------------------------------------------------------------------------------------------------------------

private enum class ProjectFilter(val label: String) {
    All("Semua"),
    Busy("Diproses"),
    Ready("Siap"),
    Action("Perlu tindakan"),
    Failed("Gagal");

    fun matches(p: ProjectEntity) = when (this) {
        All -> true
        Busy -> p.isBusy()
        Ready -> p.video == VideoStatus.Ready && p.transcript == TranscriptStatus.Ready
        Action -> !p.isBusy() && !p.isFailed() && (p.transcript == TranscriptStatus.NeedsTranscript || p.video == VideoStatus.Deleted)
        Failed -> p.isFailed()
    }
}

private fun ProjectEntity.isBusy() =
    video == VideoStatus.Downloading || video == VideoStatus.Processing || video == VideoStatus.Pending || transcript == TranscriptStatus.Fetching

private fun ProjectEntity.isFailed() = video == VideoStatus.Failed || transcript == TranscriptStatus.Failed

@Composable
private fun ProjectListScreen(
    repo: Repository, engine: MediaEngine, downloads: Downloads, renders: Renders, localVideos: LocalVideos, onOpen: (Long) -> Unit,
    onOpenResults: () -> Unit, onLocalCreated: (Long) -> Unit,
) {
    val rendered by remember { repo.db.clips().observeRendered() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var choosingLocal by remember { mutableStateOf(false) }
    if (choosingLocal) {
        LocalVideoSheet(onDismiss = { choosingLocal = false }, onPick = { fromFiles ->
            choosingLocal = false
            scope.launch {
                localVideos.create(fromFiles)
                    .onSuccess { id -> if (id != null) onLocalCreated(id) }
                    .onFailure { notifier.show(it.message ?: "Video tidak bisa dipakai.") }
            }
        })
    }
    val projects by remember { repo.db.projects().observeAll() }.collectAsState(null)
    val clipCounts by remember { repo.db.clips().observeCounts() }.collectAsState(emptyList())
    var linking by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ProjectFilter.All) }
    var query by remember { mutableStateOf("") }
    val all = projects

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FloatingNavSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Hasil render sebagai tombol ikon di kanan judul (angka = video siap; biru = sedang dirender), sama dengan Android.
                ScreenHeader("Project", Modifier.padding(horizontal = ScreenGutter)) {
                    if (rendered.isNotEmpty()) {
                        val done = rendered.count { it.render == RenderStatus.Done }
                        val busy = renders.active != null
                        Box {
                            IconCircle(ClipMiIcons.Film, "Hasil render: $done video siap", onOpenResults)
                            if (done > 0 || busy) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                                        .clip(RoundedCornerShape(50)).background(if (busy) ClipMi.colors.busy else ClipMi.colors.accent).padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (busy && done == 0) "…" else "$done", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                            }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntryCard(ClipMiIcons.Link, "Link YouTube", "Tempel link", Modifier.weight(1f)) { linking = true }
                    EntryCard(
                        ClipMiIcons.Image, "Dari galeri", if (localVideos.busy) "Menyalin video…" else "MP4, MOV", Modifier.weight(1f),
                    ) { if (!localVideos.busy) choosingLocal = true }
                }
                if (!all.isNullOrEmpty()) {
                    if (all.size > 3) {
                        SearchField(query, { query = it }, "Cari judul project", Modifier.padding(horizontal = ScreenGutter), onClear = { query = "" })
                    }
                    val filters = ProjectFilter.entries.filter { f -> f == ProjectFilter.All || filter == f || all.any(f::matches) }
                    val separates = filter != ProjectFilter.All || filters.any { f -> f != ProjectFilter.All && all.count(f::matches) in 1 until all.size }
                    if (filters.size > 1 && separates) {
                        ChipRow {
                            filters.forEach { f -> FilterPill("${f.label} · ${all.count(f::matches)}", selected = filter == f, onClick = { filter = f }) }
                        }
                    }
                }
            }
        }
        when {
            all == null -> Unit
            all.isEmpty() -> item {
                EmptyState(
                    "Belum ada project",
                    "Mulai dari link YouTube: tempel link video kajian, ClipMI mengunduh video dan subtitle-nya.",
                    Modifier.heightIn(min = 320.dp),
                    icon = ClipMiIcons.Film,
                )
            }
            else -> {
                val shown = all.filter { filter.matches(it) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
                if (shown.isEmpty()) item {
                    Text(
                        if (query.isNotBlank()) "Tidak ada project berjudul \"${query.trim()}\"." else "Tidak ada project di filter ini.",
                        style = MaterialTheme.typography.bodyMedium, color = ClipMi.colors.textMuted,
                        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 24.dp),
                    )
                }
                items(shown, key = { it.id }) { p ->
                    val counts = clipCounts.firstOrNull { it.projectId == p.id }
                    ProjectCard(repo, engine, downloads, p, counts?.total ?: 0, counts?.selected ?: 0, counts?.rendered ?: 0, onClick = { onOpen(p.id) }, modifier = Modifier.padding(horizontal = ScreenGutter))
                }
            }
        }
    }

    if (linking) NewProjectSheet(repo, downloads, onDismiss = { linking = false }, onOpen = { linking = false; onOpen(it) })
}

/** Pintu masuk project baru di atas daftar. */
@Composable
private fun EntryCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = ClipMi.colors
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Step(val label: String, val tone: Tone)

private fun ProjectEntity.steps(clipCount: Int, downloading: Double?): List<Step> = listOf(
    when (video) {
        VideoStatus.Ready -> Step("Video", Tone.Good)
        VideoStatus.Downloading -> Step("Video ${((downloading ?: 0.0) * 100).toInt()}%", Tone.Busy)
        VideoStatus.Processing, VideoStatus.Pending -> Step("Video", Tone.Busy)
        VideoStatus.Failed -> Step("Video gagal", Tone.Bad)
        VideoStatus.Deleted -> Step("Video dihapus", Tone.Warn)
    },
    when (transcript) {
        TranscriptStatus.Ready -> Step("Transkrip", Tone.Good)
        TranscriptStatus.Fetching -> Step("Transkrip", Tone.Busy)
        TranscriptStatus.NeedsTranscript -> Step("Butuh transkrip", Tone.Warn)
        TranscriptStatus.Failed -> Step("Transkrip gagal", Tone.Bad)
        else -> Step(transcript.label, Tone.Idle)
    },
    if (clipCount > 0) Step("$clipCount klip", Tone.Good) else Step("Belum ada klip", Tone.Idle),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectCard(
    repo: Repository, engine: MediaEngine, downloads: Downloads, project: ProjectEntity, clipCount: Int, selected: Int, rendered: Int,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = ClipMi.colors
    val downloading = if (downloads.active == project.id) downloads.fraction else null
    val steps = project.steps(clipCount, downloading)
    val ready = steps.all { it.tone == Tone.Good }
    ClipMiCard(modifier, onClick = onClick, padding = PaddingValues(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).height(72.dp).clip(RoundedCornerShape(14.dp))) {
                ProjectThumbnail(repo, engine, project, Modifier.fillMaxSize())
                project.durationSeconds?.let {
                    Text(
                        Timecode.format(it), style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(if (project.isYouTube) "YouTube" else "Galeri", dayMonth(project.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
                if (ready) {
                    Text(
                        listOfNotNull("$clipCount klip", selected.takeIf { it > 0 }?.let { "$it terpilih" }, rendered.takeIf { it > 0 }?.let { "$it dirender" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = c.textBody, maxLines = 1,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { steps.forEach { StepMark(it) } }
                }
            }
        }
        when {
            downloading != null -> ProgressBar(downloading.toFloat())
            project.video == VideoStatus.Processing || project.transcript == TranscriptStatus.Fetching -> ProgressBar(null, color = c.busy)
            else -> Unit
        }
    }
}

@Composable
private fun StepMark(step: Step) {
    val c = ClipMi.colors
    val color = when (step.tone) {
        Tone.Good -> c.accent
        Tone.Busy -> c.busy
        Tone.Warn -> c.warn
        Tone.Bad -> c.bad
        Tone.Idle -> c.textMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (step.tone == Tone.Good) Icon(ClipMiIcons.CircleCheck, null, tint = color, modifier = Modifier.size(15.dp))
        else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(step.label, style = MaterialTheme.typography.labelMedium, color = if (step.tone == Tone.Good) c.textBody else color, maxLines = 1)
    }
}

/** Project baru dari link YouTube (panel bawah). */
@Composable
private fun NewProjectSheet(repo: Repository, downloads: Downloads, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val c = ClipMi.colors

    ClipMiSheet(onDismiss, title = "Project dari YouTube", subtitle = "Hanya konten yang sudah diizinkan pemiliknya.") {
        Field(
            link, { link = it; error = null },
            label = "Link YouTube", placeholder = "https://youtu.be/…", error = error, singleLine = true, leadingIcon = ClipMiIcons.Link,
            trailing = {
                Box(
                    Modifier.padding(end = 6.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(c.panel)
                        .clickable(role = Role.Button) { clipboard.getText()?.text?.let { link = it.trim(); error = null } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Tempel", style = MaterialTheme.typography.labelLarge, color = c.text) }
            },
        )
        PrimaryButton(
            "Buat project", enabled = link.isNotBlank() && downloads.active == null, height = 52.dp, modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val created = repo.createYouTube(link.trim())
                    if (created == null) {
                        error = "Bukan link video YouTube."
                        return@launch
                    }
                    val (id, isNew) = created
                    if (isNew) downloads.start(id)
                    onOpen(id)
                }
            },
        )
        Text(
            if (downloads.active != null) "Satu video diunduh pada satu waktu. Biarkan aplikasi tetap terbuka sampai selesai."
            else "Biarkan aplikasi tetap terbuka selama video diunduh.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Detail project (padanan ProjectDetailScreen + ClipsTab Android)
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ProjectScreen(
    repo: Repository, engine: MediaEngine, downloads: Downloads, renders: Renders, analysis: IosAnalysis, transcriber: IosTranscriber, localVideos: LocalVideos,
    id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit,
) {
    val project by remember(id) { repo.db.projects().observe(id) }.collectAsState(null)
    val clips by remember(id) { repo.db.clips().observe(id) }.collectAsState(emptyList())
    val segments by remember(id) { repo.db.segments().observe(id) }.collectAsState(emptyList())
    var tab by remember { mutableStateOf(0) }
    val playback = remember(id) { SourcePlayback() }
    // Pilih rentang transkrip: tahan segmen awal, ketuk segmen akhir (sama dengan Android).
    var selectStart by remember(id) { mutableStateOf<Int?>(null) }
    var selectEnd by remember(id) { mutableStateOf<Int?>(null) }
    var editingSegment by remember(id) { mutableStateOf<TranscriptSegmentEntity?>(null) }
    var query by remember(id) { mutableStateOf("") }
    val p = project ?: return
    val c = ClipMi.colors
    val source = p.videoPath?.takeIf { p.video == VideoStatus.Ready }?.let { repo.absolute(it) }
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val transcriptionReady by produceState(false, p.transcriptStatus) { value = transcriber.config() != null }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    confirm?.let { (body, action) ->
        ConfirmDialog("Ganti transkrip?", body, "Lanjutkan", onConfirm = { confirm = null; action() }, onDismiss = { confirm = null })
    }
    // Transkripsi API sedang berjalan/menunggu (pengambilan subtitle YouTube tidak memakai pesan ini).
    val transcribing = p.transcript == TranscriptStatus.Fetching && p.transcriptionMessage != null && p.video == VideoStatus.Ready
    val canTranscribe = p.video == VideoStatus.Ready && transcriptionReady && transcriber.active == null
    val importSubtitle = {
        val open = {
            createDirectory(repo.absolute("tmp"))
            val output = repo.absolute("tmp/subtitle-import")
            engine.pickSubtitle(output, object : PickListener {
                override fun finished(path: String?) {
                    if (path == null) return
                    scope.launch {
                        val bytes = readBytes(path)
                        removeItem(path)
                        val isVtt = bytes != null && bytes.decodeToString().trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT")
                        val source = if (isVtt) TranscriptSource.ImportVtt else TranscriptSource.ImportSrt
                        notifier.show(
                            if (bytes == null) "File subtitle tidak bisa dibaca."
                            else try {
                                val count = repo.importTranscript(p.id, bytes, source.value)
                                repo.db.projects().modify(p.id) { it.copy(transcriptionMessage = null) }
                                "Transkrip diganti: $count segmen dari ${source.label}."
                            } catch (e: Exception) {
                                "File subtitle tidak bisa dibaca: ${e.message ?: "format tidak dikenal"}"
                            },
                        )
                    }
                }
            })
        }
        if (segments.isEmpty()) open() else confirm = "Transkrip sekarang (${segments.size} segmen) diganti isi file subtitle." to open
    }
    val transcribeApi = {
        val start = { scope.launch { transcriber.start(p.id) }; Unit }
        if (segments.isEmpty()) start()
        else confirm = "Transkrip sekarang diganti hasil transkripsi API. Biaya API ditagih penyedia sesuai panjang audio." to start
    }

    val selecting = selectStart != null
    val selectedClips = clips.filter { it.review == ReviewStatus.Selected }
    val pendingClips = selectedClips.filter {
        (it.render == RenderStatus.NotRendered || it.render == RenderStatus.Failed || it.changedAfterRender) && it.id != renders.active && it.id !in renders.queued
    }
    val renderBar = tab == 0 && !selecting && selectedClips.isNotEmpty()
    // iPad mendatar (atau layar lebar miring): video di kiri setinggi layar, isi di kanan (sama dengan Android).
    val side = isSideBySide()
    val videoBox: @Composable (Modifier) -> Unit = { area ->
        Box(area) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f)) {
                if (source != null) SourceVideo(source, Modifier.fillMaxSize(), playback)
                else ProjectThumbnail(repo, engine, p, Modifier.fillMaxSize())
            }
            IconCircle(
                ClipMiIcons.Back, "Kembali", onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                background = Color(0x990B0F0E), tint = Color.White, border = false,
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
    if (side) videoBox(Modifier.weight(0.45f).fillMaxHeight().background(Color.Black).statusBarsPadding())
    Column(Modifier.weight(0.55f).fillMaxHeight().then(if (side) Modifier.statusBarsPadding() else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
        // Video tetap di atas; hanya isi di bawahnya yang bergulir.
        if (!side) videoBox(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding().aspectRatio(16f / 9f))
        // Layar lebar tegak (iPad): isi dibatasi lebarnya supaya baris tidak memanjang.
        LazyColumn(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = if (renderBar || selecting) 120.dp else 40.dp)) {
            item {
                Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(if (p.isYouTube) "YouTube" else "Galeri", p.durationSeconds?.let { Timecode.format(it) }, dayMonth(p.createdAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                    }
                    StatusCard(p, downloads, localVideos, transcriptionReady)
                    SegmentedTabs(
                        listOf("Klip", "Transkrip"), tab, { tab = it; selectStart = null; selectEnd = null },
                        badges = listOf(clips.count { it.review != ReviewStatus.Rejected }, segments.size),
                    )
                }
            }
            if (tab == 0) {
                item {
                    ClipsSection(repo, engine, renders, analysis, p, clips, onEdit, onSchedule, onPlay = { clip ->
                        if (source != null) playback.play(clip.startTime, clip.endTime) else notifier.show("Video sumber belum siap diputar.")
                    })
                }
            } else {
                // Belum ada transkrip (atau transkripsi berjalan): alatnya tampil langsung. Sudah ada: di menu ⋯ samping cari.
                if (segments.isEmpty() || transcribing) item {
                    ChipRow(Modifier.padding(top = 12.dp)) {
                        if (transcribing) {
                            ActionChip("Batalkan transkripsi", { scope.launch { transcriber.cancel(p.id) } }, height = 32.dp)
                        } else {
                            ActionChip("Impor SRT/VTT", { importSubtitle() }, height = 32.dp)
                            ActionChip("Transkripsi Otomatis", { transcribeApi() }, height = 32.dp, enabled = canTranscribe)
                        }
                    }
                }
                val needle = query.trim()
                val shown = if (needle.isEmpty()) segments else segments.filter { it.text.contains(needle, ignoreCase = true) }
                if (segments.isNotEmpty()) item {
                    Row(
                        Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchField(
                            query, { query = it }, "Cari di transkrip", Modifier.weight(1f),
                            trailingText = if (needle.isNotEmpty()) "${shown.size} cocok" else null,
                            onClear = { query = "" },
                        )
                        NativeMenuButton(
                            buildList {
                                add(NativeMenuItem("Terapkan kamus", "character.book.closed") {
                                    scope.launch {
                                        val count = repo.applyCorrections(p.id)
                                        notifier.show(if (count > 0) "$count kata dikoreksi kamus." else "Tidak ada kata yang perlu dikoreksi.")
                                    }
                                })
                                add(NativeMenuItem("Impor SRT/VTT", "doc.text") { importSubtitle() })
                                if (!transcribing) add(NativeMenuItem("Transkripsi Otomatis", "waveform", enabled = canTranscribe) { transcribeApi() })
                            },
                            background = c.bg, fill = c.panel, tint = c.text, size = 48.dp,
                        )
                    }
                }
                if (segments.isEmpty()) item {
                    Text(
                        if (transcribing) "Transkrip sedang dibuat." else if (transcriptionReady) "Transkrip belum ada." else "Transkrip belum ada. Atur Transkripsi API di Pengaturan, atau impor file SRT/VTT.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(ScreenGutter),
                    )
                }
                if (segments.isNotEmpty()) item {
                    Text(
                        "Ketuk kalimat untuk memutar · tahan untuk memilih rentang, membuat klip, atau mengubah teks",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp),
                    )
                }
                items(shown, key = { it.id }) { segment ->
                    val start = selectStart
                    val selected = start != null && segment.sequence in minOf(start, selectEnd ?: start)..maxOf(start, selectEnd ?: start)
                    val now = playback.position
                    val playing = now != null && now >= segment.startTime && now < segment.endTime
                    SegmentRow(
                        segment, needle, selected, playing,
                        onClick = {
                            when {
                                start == null -> if (source != null) playback.play(segment.startTime) else notifier.show("Video sumber belum siap diputar.")
                                selectEnd != null || segment.sequence < start -> { selectStart = segment.sequence; selectEnd = null }
                                else -> selectEnd = segment.sequence
                            }
                        },
                        onLongClick = { selectStart = segment.sequence; selectEnd = null },
                    )
                }
            }
        }
    }
    }

    // Tab Klip: klip terpilih dirender sekaligus (berurutan), sama dengan bilah render Android.
    AnimatedVisibility(
        visible = renderBar,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        BottomBar {
            val busy = selectedClips.count { it.id == renders.active || it.id in renders.queued }
            Text(
                "${selectedClips.size} klip terpilih" + if (busy > 0) " · $busy dirender" else "",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = c.text, modifier = Modifier.weight(1f),
            )
            if (pendingClips.isNotEmpty()) {
                PrimaryButton(if (pendingClips.size == selectedClips.size) "Render semua" else "Render ${pendingClips.size} klip", height = 48.dp, onClick = {
                    renders.startAll(pendingClips.map { it.id })
                    notifier.show("Render berjalan satu per satu. Biarkan aplikasi tetap terbuka.")
                })
            } else {
                Text(if (busy > 0) "Merender…" else "Semua sudah dirender", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
    // Rentang transkrip terpilih: batal, ubah teks (satu segmen), atau buat klip manual.
    AnimatedVisibility(
        visible = selecting,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val start = selectStart ?: 0
        val end = selectEnd ?: start
        val first = segments.firstOrNull { it.sequence == minOf(start, end) }
        val last = segments.firstOrNull { it.sequence == maxOf(start, end) }
        BottomBar {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (selectEnd == null) "Ketuk segmen akhir" else "${kotlin.math.abs(end - start) + 1} segmen dipilih",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 13.sp), color = c.text,
                )
                if (first != null && last != null) {
                    Text(Timecode.format(last.endTime - first.startTime), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            GhostButton("Batal", onClick = { selectStart = null; selectEnd = null })
            if (end == start && first != null) {
                ActionChip("", { editingSegment = first; selectStart = null; selectEnd = null }, height = 44.dp, icon = ClipMiIcons.Edit)
            }
            PrimaryButton("Buat klip", height = 44.dp, onClick = {
                scope.launch {
                    repo.createManualClip(p.id, start, end)
                    selectStart = null
                    selectEnd = null
                    tab = 0
                    notifier.show("Klip manual dibuat dan langsung terpilih.")
                }
            })
        }
    }
    }

    editingSegment?.let { segment ->
        SegmentEditSheet(segment, onDone = { editingSegment = null }) { text ->
            scope.launch {
                if (repo.editSegment(segment.id, text)) {
                    editingSegment = null
                    notifier.show("Teks disimpan.")
                }
            }
        }
    }
}

/** Bilah melayang di bawah halaman project (render klip terpilih, rentang transkrip). */
@Composable
private fun BottomBar(content: @Composable RowScope.() -> Unit) {
    val c = ClipMi.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(c.panel)
            .border(1.dp, c.lineStrong, RoundedCornerShape(28.dp))
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Ubah teks satu segmen transkrip (subtitle klip di bagian ini ikut berubah). */
@Composable
private fun SegmentEditSheet(segment: TranscriptSegmentEntity, onDone: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(segment.id) { mutableStateOf(segment.text) }
    ClipMiSheet(onDone, title = "Edit teks", subtitle = "${Timecode.format(segment.startTime)} – ${Timecode.format(segment.endTime)}") {
        Field(text, { text = it }, minLines = 3, maxLines = 8)
        PrimaryButton("Simpan", { onSave(text) }, enabled = text.isNotBlank() && text.trim() != segment.text, modifier = Modifier.fillMaxWidth())
    }
}

/** Status video dan transkrip selama belum siap (unduhan, gagal, tanpa subtitle). */
@Composable
private fun StatusCard(p: ProjectEntity, downloads: Downloads, localVideos: LocalVideos, transcriptionReady: Boolean) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var pickLocal by remember(p.id) { mutableStateOf(false) }
    val downloading = downloads.active == p.id
    val failed = p.video == VideoStatus.Failed || p.transcript == TranscriptStatus.Failed
    val message = p.transcriptionMessage
    if (!downloading && p.video == VideoStatus.Ready && (p.transcript == TranscriptStatus.NeedsTranscript || message != null) && !failed) {
        TranscriptionCard(p, transcriptionReady)
        return
    }
    if (!downloading && !failed && p.video == VideoStatus.Ready && p.transcript == TranscriptStatus.Ready) return
    ClipMiCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (failed) ClipMiIcons.Alert else ClipMiIcons.Download, if (failed) Tone.Bad else if (downloading) Tone.Busy else Tone.Warn)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        downloading -> "Mengunduh video"
                        p.video == VideoStatus.Failed -> "Video gagal diunduh"
                        p.transcript == TranscriptStatus.Failed -> "Transkrip gagal"
                        p.transcript == TranscriptStatus.NeedsTranscript -> "Tanpa subtitle Indonesia"
                        else -> "Video ${p.video.label.lowercase()}"
                    },
                    style = MaterialTheme.typography.titleSmall, color = c.text,
                )
                Text(
                    if (downloading) downloads.status.ifEmpty { "Biarkan aplikasi tetap terbuka" } else p.errorMessage ?: "Transkrip: ${p.transcript.label.lowercase()}",
                    style = MaterialTheme.typography.bodySmall, color = if (failed) c.bad else c.textMuted, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (downloading) ProgressBar(downloads.fraction.toFloat())
        val deleted = p.video == VideoStatus.Deleted
        if (deleted && !downloading) {
            Text(
                "Transkrip dan klip tetap ada. Untuk memutar atau merender ulang, " + if (p.isYouTube) "unduh ulang videonya." else "pilih ulang video sumbernya.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
        if ((failed || deleted) && !downloading) {
            if (p.isYouTube) {
                SecondaryButton("Unduh ulang video", icon = ClipMiIcons.Download, height = 44.dp, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (downloads.active == null) downloads.start(p.id)
                    else notifier.show("Satu video diunduh pada satu waktu. Tunggu unduhan yang berjalan selesai.")
                })
            } else if (deleted) {
                SecondaryButton(
                    if (localVideos.busy) "Menyalin video…" else "Pilih ulang video", icon = ClipMiIcons.Refresh, height = 44.dp, modifier = Modifier.fillMaxWidth(),
                    onClick = { if (!localVideos.busy) pickLocal = true },
                )
            }
        }
    }
    if (pickLocal) {
        LocalVideoSheet(onDismiss = { pickLocal = false }, onPick = { fromFiles ->
            pickLocal = false
            scope.launch {
                localVideos.recover(p.id, fromFiles)
                    .onSuccess { ok -> if (ok) notifier.show("Video sumber dipasang lagi.") }
                    .onFailure { notifier.show(it.message ?: "Video tidak bisa dipakai.") }
            }
        })
    }
}

/** Status transkripsi (Transkripsi API, tanpa subtitle, hasil terakhir), sama dengan kartu status Android. */
@Composable
private fun TranscriptionCard(p: ProjectEntity, transcriptionReady: Boolean) {
    val c = ClipMi.colors
    val message = p.transcriptionMessage
    val fetching = p.transcript == TranscriptStatus.Fetching
    val needsTranscript = p.transcript == TranscriptStatus.NeedsTranscript
    val (icon, tone, title) = when {
        fetching -> Triple(ClipMiIcons.Mic, Tone.Busy, "Transkripsi API")
        needsTranscript -> Triple(ClipMiIcons.Alert, Tone.Warn, "Butuh transkrip")
        message?.startsWith(IosTranscriber.DONE_PREFIX) == true -> Triple(ClipMiIcons.Check, Tone.Good, "Transkripsi selesai")
        else -> Triple(ClipMiIcons.Mic, Tone.Idle, "Transkripsi")
    }
    ClipMiCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tone)
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
        }
        if (fetching) ProgressBar(null, color = c.busy)
        if (needsTranscript) {
            Text(
                "Video ini tidak punya subtitle Indonesia. Impor SRT/VTT atau jalankan Transkripsi API di tab Transkrip" +
                    (if (!transcriptionReady) " (atur dulu di Pengaturan)." else "."),
                style = MaterialTheme.typography.bodySmall, color = c.textBody,
            )
        }
        message?.let {
            val color = when {
                it.startsWith(IosTranscriber.DONE_PREFIX) -> c.accent
                it.startsWith(IosTranscriber.CANCELLED_PREFIX) -> c.textMuted
                fetching -> c.textBody
                else -> c.bad
            }
            Text(it, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class ReviewFilter(val label: String) {
    Active("Aktif"),
    Selected("Terpilih"),
    Rejected("Dibuang");

    fun matches(clip: ClipEntity) = when (this) {
        Active -> clip.review != ReviewStatus.Rejected
        Selected -> clip.review == ReviewStatus.Selected
        Rejected -> clip.review == ReviewStatus.Rejected
    }
}

@Composable
private fun ClipsSection(
    repo: Repository, engine: MediaEngine, renders: Renders, analysis: IosAnalysis, project: ProjectEntity, clips: List<ClipEntity>,
    onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit, onPlay: (ClipEntity) -> Unit,
) {
    val c = ClipMi.colors
    var filter by remember { mutableStateOf(ReviewFilter.Active) }
    // Status jadwal per klip (label Terbit, sama dengan halaman Jadwal).
    val schedule by rememberScheduleData(repo)
    val states = schedule?.clipStates.orEmpty()
    Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (project.transcript != TranscriptStatus.Ready) {
            ClipMiCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(ClipMiIcons.Sparkle, Tone.Idle)
                    Text("Analisis AI dimulai setelah transkrip tersedia.", style = MaterialTheme.typography.bodyMedium, color = c.textBody)
                }
            }
        } else {
            AnalysisCard(repo, engine, analysis, project, compact = clips.isNotEmpty())
        }
        if (clips.isNotEmpty()) {
            val counts = ReviewFilter.entries.associateWith { f -> clips.count(f::matches) }
            LaunchedEffect(counts[filter]) { if (filter != ReviewFilter.Active && counts[filter] == 0) filter = ReviewFilter.Active }
            if (counts.count { (f, n) -> f == ReviewFilter.Active || n > 0 } > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewFilter.entries.filter { it == ReviewFilter.Active || counts.getValue(it) > 0 }.forEach { f ->
                        FilterPill("${f.label} ${counts.getValue(f)}", selected = filter == f, height = 32.dp, onClick = { filter = f })
                    }
                }
            }
        }
        if (clips.isNotEmpty()) {
            Text(
                if (filter == ReviewFilter.Rejected) "Geser ke kiri untuk menghapus permanen · ke kanan untuk memulihkan"
                else "Geser ke kanan untuk memilih · ke kiri untuk membuang",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
        clips.filter(filter::matches).forEach { clip -> ClipCard(repo, engine, renders, clip, states[clip.id], onEdit, onSchedule, onPlay) }
    }
}

/** Kartu "Pilih momen dengan AI": Mode API (otomatis) atau lewat chat AI (salin prompt, tempel balasan), sama dengan Android. */
@Composable
private fun AnalysisCard(repo: Repository, engine: MediaEngine, analysis: IosAnalysis, project: ProjectEntity, compact: Boolean) {
    val scope = rememberCoroutineScope()
    val c = ClipMi.colors
    var manual by remember { mutableStateOf(false) }
    val prompts by produceState(emptyList<String>(), project.id, project.transcriptStatus, project.updatedAt) { value = analysis.prompts(project) }
    val config by produceState<AiConfig?>(null, project.analysisStatus) { value = analysis.config() }
    val resumable by produceState(emptyList<Int>(), prompts, project.analysisParts, project.analysisStatus) {
        value = if (AnalysisStatus.isActive(project.analysisStatus) || prompts.size < 2) emptyList() else {
            val done = analysis.completedParts(project.id, prompts)
            if (done.isEmpty()) emptyList() else prompts.indices.filter { it !in done }
        }
    }
    val active = AnalysisStatus.isActive(project.analysisStatus) || analysis.active == project.id
    val cfg = config
    fun summary(cfg: AiConfig, parts: List<Int>?): String {
        val estimate = analysis.estimate(prompts, cfg, parts)
        return listOfNotNull(
            cfg.provider.label.substringBefore(" ("),
            "±${IosAnalysis.groupThousands(estimate.tokens)} token",
            estimate.cost?.let { "±$" + fixed(it, if (it < 0.1) 3 else 2) },
            if (!parts.isNullOrEmpty()) "${parts.size} bagian tersisa" else null,
        ).joinToString(" · ")
    }

    // Sudah ada klip dan tidak ada yang berjalan/tersisa: cukup dua tombol kecil, analisis ulang dikonfirmasi dulu.
    if (compact && !active && project.analysisMessage == null && resumable.isEmpty()) {
        var confirmAgain by remember { mutableStateOf(false) }
        PromptStyleChip(analysis, project)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cfg != null) ActionChip("Analisis lagi", { confirmAgain = true }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Sparkle, enabled = prompts.isNotEmpty())
            ActionChip("Lewat chat AI", { manual = true }, Modifier.weight(1f), height = 40.dp, icon = if (cfg == null) ClipMiIcons.Sparkle else null, enabled = prompts.isNotEmpty())
        }
        if (confirmAgain && cfg != null) {
            ConfirmDialog(
                "Analisis lagi?", summary(cfg, null), "Analisis",
                onConfirm = { confirmAgain = false; analysis.start(project.id) },
                onDismiss = { confirmAgain = false },
            )
        }
        if (manual) ManualAnalysisSheet(repo, engine, project, prompts, onDismiss = { manual = false })
        return
    }

    ClipMiCard(spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ClipMiIcons.Sparkle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pilih momen dengan AI", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    if (cfg == null) "Mode API belum diatur · lewat chat AI tetap gratis" else summary(cfg, resumable.ifEmpty { null }),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
        }
        if (active) {
            ProgressBar(null, color = c.busy)
            project.analysisMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody) }
            SecondaryButton("Batalkan", onClick = { scope.launch { analysis.cancel(project.id) } }, height = 44.dp, modifier = Modifier.fillMaxWidth())
        } else {
            project.analysisMessage?.let { message ->
                val color = when (project.analysisStatus) {
                    AnalysisStatus.DONE -> c.accent
                    AnalysisStatus.FAILED -> c.bad
                    else -> c.textMuted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconCircle(ClipMiIcons.Close, "Tutup pesan", { scope.launch { analysis.dismiss(project.id) } }, size = 36.dp, background = Color.Transparent, border = false, tint = c.textMuted)
                }
            }
            PromptStyleChip(analysis, project)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cfg != null) {
                    if (resumable.isNotEmpty()) {
                        PrimaryButton("Lanjutkan", onClick = { analysis.start(project.id, resume = true) }, height = 44.dp, modifier = Modifier.weight(1f))
                    } else {
                        PrimaryButton("Analisis otomatis", onClick = { analysis.start(project.id) }, enabled = prompts.isNotEmpty(), height = 44.dp, modifier = Modifier.weight(1f))
                    }
                }
                SecondaryButton("Lewat chat AI", onClick = { manual = true }, enabled = prompts.isNotEmpty(), height = 44.dp, modifier = Modifier.weight(1f))
            }
            if (cfg != null && resumable.isNotEmpty()) {
                GhostButton("Analisis ulang dari awal", onClick = { analysis.start(project.id) })
            }
        }
    }
    if (manual) ManualAnalysisSheet(repo, engine, project, prompts, onDismiss = { manual = false })
}

/** Angka dengan [decimals] desimal dan titik desimal (pengganti String.format di Kotlin/Native). */
private fun fixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    return "${scaled / factor}." + (scaled % factor).toString().padStart(decimals, '0')
}

/** Salin prompt ke aplikasi chat AI, lalu tempel balasan JSON-nya (sama dengan Android). */
@Composable
private fun ManualAnalysisSheet(repo: Repository, engine: MediaEngine, project: ProjectEntity, prompts: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    val c = ClipMi.colors
    var reply by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }

    ClipMiSheet(
        onDismiss, title = "Lewat chat AI",
        subtitle = "Salin atau bagikan prompt ke Claude, ChatGPT, atau Gemini, lalu tempel balasan JSON-nya di sini." +
            if (prompts.size > 1) " Transkrip panjang dibagi ${prompts.size} bagian; kirim satu per satu." else "",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            prompts.forEachIndexed { i, prompt ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(18.dp))
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (prompts.size > 1) "Bagian ${i + 1}" else "Prompt", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text("${prompt.length / 1000} rb karakter", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    IconCircle(ClipMiIcons.Copy, "Salin prompt", {
                        clipboard.setText(AnnotatedString(prompt))
                        notifier.show("Prompt disalin. Tempel ke aplikasi chat AI.")
                    }, size = 40.dp, background = c.panel)
                    // Lembar bagikan iOS: kirim prompt langsung ke aplikasi ChatGPT, Gemini, Claude, dan lainnya.
                    IconCircle(ClipMiIcons.Share, "Bagikan prompt ke aplikasi AI", { engine.shareText(prompt) }, size = 40.dp, background = c.panel)
                }
            }
        }
        // Centang hijau begitu balasan terbaca sebagai JSON klip (termasuk salinan ChatGPT yang perlu dirapikan).
        val readable = remember(reply) {
            reply.isNotBlank() && ChatPaste.candidates(reply).any { runCatching { ClipImporter.decode(it) }.isSuccess }
        }
        Box {
            Field(reply, { reply = it; replyError = null }, label = "Balasan JSON dari AI", placeholder = "{\"clips\": [ … ]}", error = replyError, minLines = 4, maxLines = 8)
            if (readable && replyError == null) {
                Icon(
                    ClipMiIcons.CircleCheck, "Balasan JSON terbaca", tint = c.accent,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp).size(22.dp).background(c.bg, CircleShape),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Tempel", icon = ClipMiIcons.Copy, modifier = Modifier.weight(1f), onClick = {
                clipboard.getText()?.text?.let { reply = it; replyError = null }
            })
            PrimaryButton("Simpan kandidat", modifier = Modifier.weight(1f), onClick = {
                if (reply.isBlank()) {
                    replyError = "Tempel balasan JSON dari AI dulu."
                    return@PrimaryButton
                }
                scope.launch {
                    try {
                        val outcome = repo.importReply(project.id, reply)
                        notifier.show(outcome.message())
                        reply = ""
                        onDismiss()
                    } catch (e: AnalysisResultException) {
                        replyError = e.message ?: "Balasan tidak berisi JSON klip."
                    }
                }
            })
        }
    }
}

/**
 * Kartu klip: skor, judul, ringkasan, status, dan menu ⋯ berisi semua aksi. Ketuk untuk menampilkan hook dan alasan
 * lengkap; geser ke kanan = pilih (di tab Dibuang: pulihkan), geser ke kiri = buang (di tab Dibuang: hapus permanen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    repo: Repository, engine: MediaEngine, renders: Renders, clip: ClipEntity, scheduleState: ScheduleState?,
    onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit, onPlay: (ClipEntity) -> Unit,
) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    var expanded by remember(clip.id) { mutableStateOf(false) }
    var confirmDelete by remember(clip.id) { mutableStateOf(false) }
    val rendering = renders.active == clip.id
    val rejected = clip.review == ReviewStatus.Rejected

    fun setReview(status: ReviewStatus) = scope.launch { repo.db.clips().setReview(clip.id, status.value) }
    fun discard() {
        setReview(ReviewStatus.Rejected)
        renders.unqueue(clip.id)
        notifier.show("Klip dibuang", action = "Urungkan") { setReview(clip.review) }
    }
    fun share() {
        val output = clip.outputPath?.let { repo.absolute(it) }
        if (output != null && fileSize(output) > 0) {
            clipboard.setText(AnnotatedString(ClipText.shareText(clip.title, clip.partNumber, clip.caption, repo.hashtags(clip))))
            notifier.show("Caption & hashtag disalin. Tempel di aplikasi tujuan.")
            engine.shareFile(output)
        } else {
            notifier.show("File hasil tidak ditemukan. Render ulang klip ini.")
        }
    }

    // Aksi klip: menu ⋯ asli iOS dan lembar aksi saat kartu ditekan lama.
    val items = buildList {
        add(NativeMenuItem("Putar", "play") { onPlay(clip) })
        add(NativeMenuItem("Buka editor", "scissors") { onEdit(clip.id) })
        when {
            rendering -> add(NativeMenuItem("Batalkan render", "xmark.circle") { renders.cancel() })
            clip.id in renders.queued -> add(NativeMenuItem("Keluarkan dari antrean", "xmark.circle") { renders.unqueue(clip.id) })
            else -> add(NativeMenuItem(when (clip.render) { RenderStatus.Done -> "Render ulang"; RenderStatus.Failed -> "Render lagi"; else -> "Render" }, "film") {
                renders.start(clip.id)
                if (renders.active != clip.id) notifier.show("Masuk antrean render.")
            })
        }
        if (clip.render == RenderStatus.Done && !rendering) {
            add(NativeMenuItem("Jadwalkan", "calendar") { onSchedule(clip.id) })
            add(NativeMenuItem("Bagikan", "square.and.arrow.up") { share() })
        }
        when (clip.review) {
            ReviewStatus.Candidate -> {
                add(NativeMenuItem("Pilih", "checkmark.circle") { setReview(ReviewStatus.Selected) })
                add(NativeMenuItem("Buang", "xmark", destructive = true) { discard() })
            }
            ReviewStatus.Selected -> {
                add(NativeMenuItem("Batal pilih", "circle") { setReview(ReviewStatus.Candidate) })
                add(NativeMenuItem("Buang", "xmark", destructive = true) { discard() })
            }
            ReviewStatus.Rejected -> {
                add(NativeMenuItem("Pulihkan", "arrow.uturn.backward") { setReview(ReviewStatus.Candidate) })
                add(NativeMenuItem("Hapus permanen", "trash", destructive = true) { confirmDelete = true })
            }
        }
    }

    SwipeCard(
        right = if (rejected) SwipeAction("Pulihkan", ClipMiIcons.Refresh, c.accent) { setReview(ReviewStatus.Candidate) }
        else if (clip.review != ReviewStatus.Selected) SwipeAction("Pilih", ClipMiIcons.CircleCheck, c.accent) { setReview(ReviewStatus.Selected) }
        else null,
        left = if (rejected) SwipeAction("Hapus", ClipMiIcons.Trash, c.bad) { confirmDelete = true }
        else SwipeAction("Buang", ClipMiIcons.Close, c.bad) { discard() },
    ) {
        ClipMiCard(
            Modifier.clip(MaterialTheme.shapes.large).combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    engine.haptic("medium")
                    engine.actionSheet(ClipText.displayTitle(clip.title, clip.partNumber), items)
                },
            ),
            spacing = 10.dp,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreBadge(clip.score)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
                    (clip.reason ?: clip.caption)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    }
                    ClipMeta(clip, renders, expanded, scheduleState)
                }
                // Semua aksi di menu ⋯ asli (sudut kanan atas kartu).
                NativeMenuButton(items, background = c.card, tint = c.textMuted)
            }
            // Progres dan galat render selalu terlihat (bukan aksi).
            if (rendering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", style = MaterialTheme.typography.bodySmall, color = c.textBody, modifier = Modifier.weight(1f))
                    GhostButton("Batalkan", onClick = { renders.cancel() })
                }
                ProgressBar(renders.fraction.toFloat(), color = c.busy)
            } else if (clip.render == RenderStatus.Failed) {
                clip.renderError?.let { Text("Render gagal: $it", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            // Diketuk: hook dan alasan lengkap saja.
            if (expanded) clip.hook?.takeIf { it.isNotBlank() }?.let { HookLine(it) }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Hapus klip permanen?", "\"${ClipText.displayTitle(clip.title, clip.partNumber)}\" dihapus beserta video hasil dan jadwalnya. Tidak bisa diurungkan.", "Hapus",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch { repo.deleteClip(clip.id); notifier.show("Klip dihapus permanen.") }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Aksi geser kartu: label, ikon, warna, dan yang dijalankan saat geseran melewati batas. */
private class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val run: () -> Unit)

/**
 * Kartu yang bisa digeser: ke kanan menjalankan [right], ke kiri [left]; latar berwarna dengan ikon dan label tampil di
 * bawahnya, lalu kartu kembali ke tempat. Geseran vertikal tetap menggulir daftar.
 */
@Composable
private fun SwipeCard(right: SwipeAction?, left: SwipeAction?, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 88.dp.toPx() }
    val maxTravel = with(density) { 120.dp.toPx() }
    val shape = RoundedCornerShape(24.dp)
    // Getar halus saat geseran melewati titik aksi (lepas di sini = aksi dijalankan).
    val engine = LocalMediaEngine.current
    val passed = kotlin.math.abs(offset.value) >= threshold
    LaunchedEffect(passed) { if (passed) engine.haptic("light") }
    Box(Modifier.fillMaxWidth()) {
        val x = offset.value
        val action = if (x > 0) right else if (x < 0) left else null
        if (action != null) {
            val reached = kotlin.math.abs(x) >= threshold
            Row(
                Modifier.matchParentSize().clip(shape).background(action.color.copy(alpha = if (reached) 0.22f else 0.12f)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (x > 0) Arrangement.Start else Arrangement.End,
            ) {
                Icon(action.icon, null, tint = action.color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(action.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = action.color)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(right, left) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val value = offset.value
                            val chosen = if (value >= threshold) right else if (value <= -threshold) left else null
                            scope.launch { offset.animateTo(0f) }
                            chosen?.run?.invoke()
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, amount ->
                        // Melewati batas aksi geseran melambat, dan kartu tidak pernah terlempar penuh ke samping.
                        val damped = if (kotlin.math.abs(offset.value) >= threshold) amount * 0.35f else amount
                        val next = offset.value + damped
                        // Arah tanpa aksi tidak bisa digeser jauh.
                        val limited = when {
                            next > 0 && right == null -> next.coerceAtMost(24f)
                            next < 0 && left == null -> next.coerceAtLeast(-24f)
                            else -> next.coerceIn(-maxTravel, maxTravel)
                        }
                        change.consume()
                        scope.launch { offset.snapTo(limited) }
                    }
                },
        ) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipMeta(clip: ClipEntity, renders: Renders, expanded: Boolean, scheduleState: ScheduleState? = null) {
    val c = ClipMi.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            if (expanded) "${Timecode.format(clip.startTime)} – ${Timecode.format(clip.endTime)} · ${Timecode.format(clip.duration)}" else Timecode.format(clip.duration),
            style = MaterialTheme.typography.labelMedium, color = c.textMuted, maxLines = 1,
        )
        when (clip.review) {
            ReviewStatus.Selected -> StatusBadge("Terpilih", Tone.Good)
            ReviewStatus.Rejected -> StatusBadge("Dibuang", Tone.Bad)
            else -> Unit
        }
        when {
            renders.active == clip.id -> StatusBadge("Render ${(renders.fraction * 100).toInt()}%", Tone.Busy)
            clip.id in renders.queued -> StatusBadge("Antre render", Tone.Idle)
            scheduleState == ScheduleState.Published -> StatusBadge("Terbit", Tone.Good)
            scheduleState == ScheduleState.Partial -> StatusBadge("Sebagian terbit", Tone.Busy)
            clip.render == RenderStatus.Done && clip.changedAfterRender -> StatusBadge("Hasil lama", Tone.Warn)
            scheduleState == ScheduleState.Scheduled -> StatusBadge("Dijadwalkan", Tone.Busy)
            clip.render == RenderStatus.Done -> StatusBadge("Siap unggah", Tone.Good)
            clip.render == RenderStatus.Failed -> StatusBadge("Render gagal", Tone.Bad)
            else -> Unit
        }
        if (clip.source == ClipSource.MANUAL) StatusBadge("Manual", Tone.Idle)
    }
}

/** Hook (teks pembuka di layar) dengan garis aksen di kiri. */
@Composable
private fun HookLine(hook: String) {
    val c = ClipMi.colors
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = 0.5f)))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Hook", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Text(hook, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
    }
}

/** Skor AI dalam kotak membulat: hijau ≥ 80, biru ≥ 60, abu-abu di bawahnya. */
@Composable
private fun ScoreBadge(score: Int?) {
    val c = ClipMi.colors
    val color = when {
        score == null -> c.textMuted
        score >= 80 -> c.accent
        score >= 60 -> c.busy
        else -> c.textMuted
    }
    Column(
        Modifier.width(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(score?.toString() ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = color)
        Text("skor", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = color.copy(alpha = 0.8f))
    }
}

/** Satu kalimat transkrip: ketuk untuk memutar, tahan untuk memilih rentang (ditandai saat dipilih atau sedang diputar). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(segment: TranscriptSegmentEntity, query: String, selected: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .then(
                when {
                    selected -> Modifier.background(c.accent.copy(alpha = 0.12f)).border(1.dp, c.accent.copy(alpha = 0.35f), shape)
                    playing -> Modifier.background(c.panel)
                    else -> Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Timecode.format(segment.startTime), style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Text(highlight(segment.text, query, c.highlight, c.onHighlight), style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
    }
}

/** Teks dengan kata yang dicari ditebalkan dan diberi latar (sama dengan Android). */
private fun highlight(text: String, query: String, background: Color, color: Color) = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    while (true) {
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) break
        append(text.substring(start, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = background, color = color)) { append(text.substring(index, index + query.length)) }
        start = index + query.length
    }
    append(text.substring(start))
}
