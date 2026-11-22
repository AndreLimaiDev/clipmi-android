package id.clipmi.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
    val notifier = remember { Notifier(SnackbarHostState(), scope) }
    var tab by remember { mutableStateOf(MainTab.Projects) }
    // Tumpukan layar di atas tab (kembali = ambil yang teratas).
    var stack by remember { mutableStateOf(listOf<Route>()) }
    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
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

    CompositionLocalProvider(LocalNotifier provides notifier) {
        Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
            when (val top = stack.lastOrNull()) {
                // Editor selalu gelap, sama dengan Android.
                is Route.Editor -> ClipMiDarkSurface {
                    Box(Modifier.fillMaxSize().background(ClipMi.colors.bg).safeDrawingPadding()) {
                        ClipEditorScreen(repo, renders, top.clipId, onBack = pop)
                    }
                }
                is Route.Project -> ProjectScreen(
                    repo, engine, downloads, renders, analysis, transcriber, top.id, onBack = pop, onEdit = { push(Route.Editor(it)) }, onSchedule = { push(Route.ScheduleEdit(clipId = it)) },
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
                Route.Performance -> Box(Modifier.fillMaxSize()) { PerformanceScreen(repo, performance, settings, onBack = pop, onOpenSchedule = { push(Route.Ready(it)) }) }
                null -> when (tab) {
                    MainTab.Projects -> ProjectListScreen(repo, engine, downloads, onOpen = { push(Route.Project(it)) })
                    MainTab.Schedule -> ScheduleScreen(
                        repo, engine, scheduler, renders,
                        onOpenAccounts = { push(Route.Accounts) },
                        onOpenSchedule = { push(Route.Ready(it)) },
                        onNewSchedule = { clip, date -> push(Route.ScheduleEdit(clipId = clip, date = date)) },
                    )
                    MainTab.Stats -> StatsScreen(repo, engine, scheduler, downloads, onOpenSchedule = { push(Route.Ready(it)) }, onOpenPerformance = { push(Route.Performance) })
                    MainTab.Settings -> SettingsScreen(repo, settings, onAnalysis = { push(Route.AiSettings) }, onTranscription = { push(Route.TranscriptionSettings) }, onBumpers = { push(Route.Bumpers) }, onBackup = { push(Route.Backup) })
                }
            }
            val topLevel = stack.isEmpty()
            AnimatedVisibility(
                visible = topLevel,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { FloatingNavBar(tab) { tab = it } }
            if (topLevel) SnackbarInset(FloatingNavSpace - 16.dp)
            ClipMiSnackbarHost(notifier, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Navigasi utama: pil melayang 68 dp, item aktif berlatar emerald (sama dengan Android). */
@Composable
private fun FloatingNavBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(34.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
            .fillMaxWidth()
            .height(68.dp)
            .shadow(if (c.isDark) 0.dp else 12.dp, shape, ambientColor = c.text.copy(alpha = 0.08f), spotColor = c.text.copy(alpha = 0.08f))
            .clip(shape)
            .background(if (c.isDark) c.panel.copy(alpha = 0.96f) else c.card)
            .border(1.dp, if (c.isDark) c.lineStrong else c.line, shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).clickable(role = Role.Tab, onClickLabel = tab.label) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.clip(CircleShape).background(if (active) c.accent else Color.Transparent).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Icon(tab.icon, contentDescription = null, tint = if (active) c.onAccent else c.textMuted, modifier = Modifier.size(22.dp))
                }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (active) c.text else c.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: Repository, settings: IosSettings, onAnalysis: () -> Unit, onTranscription: () -> Unit, onBumpers: () -> Unit, onBackup: () -> Unit) {
    val bumperList by remember { repo.db.bumpers().observeAll() }.collectAsState(emptyList())
    val transcription by produceState<Pair<String, Boolean>?>(null) {
        val cfg = settings.transcription()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    val analysis by produceState<Pair<String, Boolean>?>(null) {
        val cfg = settings.analysis()
        value = if (cfg != null) "${cfg.provider.label.substringBefore(" (")} · ${cfg.model}" to true else "Belum diatur" to false
    }
    var hasYoutubeKey by remember { mutableStateOf(settings.youtubeKey() != null) }
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
            }
        }
        item {
            SettingsGroup("Render") {
                SettingsRow(
                    "Intro & outro",
                    if (bumperList.isEmpty()) "Belum ada" else "${bumperList.count { it.kind == "intro" }} intro · ${bumperList.count { it.kind == "outro" }} outro",
                    onClick = onBumpers,
                )
            }
        }
        item {
            SettingsGroup("Jadwal & statistik") {
                SettingsRow("YouTube Data API", if (hasYoutubeKey) "Key tersimpan" else "Opsional", onClick = { editingYoutubeKey = true }, trailing = { ReadyMark(hasYoutubeKey) })
            }
        }
        item {
            SettingsGroup("Data") {
                SettingsRow("Cadangkan & pulihkan", "Satu file ZIP, sama dengan ClipMI Android", onClick = onBackup)
            }
        }
        item {
            SettingsGroup("Tentang") {
                SettingsRow("ClipMI iOS", "Versi uji · dipasang lewat sideload")
            }
        }
    }
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
private fun ProjectListScreen(repo: Repository, engine: MediaEngine, downloads: Downloads, onOpen: (Long) -> Unit) {
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
                ScreenHeader("Project", Modifier.padding(horizontal = ScreenGutter))
                Row(Modifier.padding(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntryCard(ClipMiIcons.Link, "Link YouTube", "Tempel link", Modifier.weight(1f)) { linking = true }
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
                    listOf(if (project.isYouTube) "YouTube" else "Video", dayMonth(project.createdAt)).joinToString(" · "),
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
        if (step.tone == Tone.Good) Icon(ClipMiIcons.Check, null, tint = color, modifier = Modifier.size(14.dp))
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
    repo: Repository, engine: MediaEngine, downloads: Downloads, renders: Renders, analysis: IosAnalysis, transcriber: IosTranscriber, id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit,
) {
    val project by remember(id) { repo.db.projects().observe(id) }.collectAsState(null)
    val clips by remember(id) { repo.db.clips().observe(id) }.collectAsState(emptyList())
    val segments by remember(id) { repo.db.segments().observe(id) }.collectAsState(emptyList())
    var tab by remember { mutableStateOf(0) }
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

    Column(Modifier.fillMaxSize()) {
        // Video tetap di atas; hanya isi di bawahnya yang bergulir.
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding().aspectRatio(16f / 9f)) {
            if (source != null) SourceVideo(source, Modifier.fillMaxSize())
            else ProjectThumbnail(repo, engine, p, Modifier.fillMaxSize())
            IconCircle(
                ClipMiIcons.Back, "Kembali", onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                background = Color(0x990B0F0E), tint = Color.White, border = false,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(if (p.isYouTube) "YouTube" else "Video", p.durationSeconds?.let { Timecode.format(it) }, dayMonth(p.createdAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        )
                    }
                    StatusCard(p, downloads, transcriptionReady)
                    SegmentedTabs(listOf("Klip ${clips.count { it.review != ReviewStatus.Rejected }}", "Transkrip ${segments.size}"), tab, { tab = it })
                }
            }
            if (tab == 0) {
                item { ClipsSection(repo, engine, renders, analysis, p, clips, onEdit, onSchedule) }
            } else {
                item {
                    ChipRow(Modifier.padding(top = 12.dp)) {
                        if (transcribing) {
                            ActionChip("Batalkan transkripsi", { scope.launch { transcriber.cancel(p.id) } }, height = 32.dp)
                        } else {
                            ActionChip("Impor SRT/VTT", { importSubtitle() }, height = 32.dp)
                            ActionChip(if (segments.isEmpty()) "Transkripsi API" else "Transkripsi ulang lewat API", { transcribeApi() }, height = 32.dp, enabled = canTranscribe)
                        }
                    }
                }
                if (segments.isEmpty()) item {
                    Text(
                        if (transcribing) "Transkrip sedang dibuat." else if (transcriptionReady) "Transkrip belum ada." else "Transkrip belum ada. Atur Transkripsi API di Pengaturan, atau impor file SRT/VTT.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(ScreenGutter),
                    )
                }
                items(segments, key = { it.id }) { SegmentRow(it) }
            }
        }
    }
}

/** Status video dan transkrip selama belum siap (unduhan, gagal, tanpa subtitle). */
@Composable
private fun StatusCard(p: ProjectEntity, downloads: Downloads, transcriptionReady: Boolean) {
    val c = ClipMi.colors
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
        if (failed && p.isYouTube && downloads.active == null) {
            SecondaryButton("Unduh ulang", onClick = { downloads.start(p.id) }, height = 44.dp, modifier = Modifier.fillMaxWidth())
        }
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
private fun ClipsSection(repo: Repository, engine: MediaEngine, renders: Renders, analysis: IosAnalysis, project: ProjectEntity, clips: List<ClipEntity>, onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit) {
    val c = ClipMi.colors
    var filter by remember { mutableStateOf(ReviewFilter.Active) }
    Column(Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (project.transcript != TranscriptStatus.Ready) {
            ClipMiCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(ClipMiIcons.Sparkle, Tone.Idle)
                    Text("Analisis AI dimulai setelah transkrip tersedia.", style = MaterialTheme.typography.bodyMedium, color = c.textBody)
                }
            }
        } else {
            AnalysisCard(repo, analysis, project, compact = clips.isNotEmpty())
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
        clips.filter(filter::matches).forEach { clip -> ClipCard(repo, engine, renders, clip, onEdit, onSchedule) }
    }
}

/** Kartu "Pilih momen dengan AI": Mode API (otomatis) atau lewat chat AI (salin prompt, tempel balasan), sama dengan Android. */
@Composable
private fun AnalysisCard(repo: Repository, analysis: IosAnalysis, project: ProjectEntity, compact: Boolean) {
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
        if (manual) ManualAnalysisSheet(repo, project, prompts, onDismiss = { manual = false })
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
    if (manual) ManualAnalysisSheet(repo, project, prompts, onDismiss = { manual = false })
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
private fun ManualAnalysisSheet(repo: Repository, project: ProjectEntity, prompts: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    val c = ClipMi.colors
    var reply by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }

    ClipMiSheet(
        onDismiss, title = "Lewat chat AI",
        subtitle = "Salin prompt ke Claude, ChatGPT, atau Gemini, lalu tempel balasan JSON-nya di sini." +
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
                }
            }
        }
        Field(reply, { reply = it; replyError = null }, label = "Balasan JSON dari AI", placeholder = "{\"clips\": [ … ]}", error = replyError, minLines = 4, maxLines = 8)
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

/** Kartu klip: ringkas (skor, judul, ringkasan, status); ketuk untuk aksi (sama dengan kartu Android, tanpa geser). */
@Composable
private fun ClipCard(repo: Repository, engine: MediaEngine, renders: Renders, clip: ClipEntity, onEdit: (Long) -> Unit, onSchedule: (Long) -> Unit) {
    val c = ClipMi.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    var expanded by remember(clip.id) { mutableStateOf(false) }
    ClipMiCard(onClick = { expanded = !expanded }, spacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreBadge(clip.score)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ClipText.displayTitle(clip.title, clip.partNumber), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
                (clip.reason ?: clip.caption)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.textBody, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                }
                ClipMeta(clip, renders, expanded)
            }
        }
        if (!expanded) return@ClipMiCard
        clip.hook?.takeIf { it.isNotBlank() }?.let { HookLine(it) }
        val rendering = renders.active == clip.id
        if (rendering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Merender ${(renders.fraction * 100).toInt()}% · biarkan aplikasi tetap terbuka", style = MaterialTheme.typography.bodySmall, color = c.textBody, modifier = Modifier.weight(1f))
                GhostButton("Batalkan", onClick = { renders.cancel() })
            }
            ProgressBar(renders.fraction.toFloat(), color = c.busy)
        } else if (clip.render == RenderStatus.Failed) {
            clip.renderError?.let { Text("Render gagal: $it", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        if (clip.render == RenderStatus.Done && !rendering) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Jadwalkan", { onSchedule(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Calendar, primary = true)
                ActionChip("Bagikan", {
                    val output = clip.outputPath?.let { repo.absolute(it) }
                    if (output != null && fileSize(output) > 0) {
                        clipboard.setText(AnnotatedString(ClipText.shareText(clip.title, clip.partNumber, clip.caption, repo.hashtags(clip))))
                        notifier.show("Caption & hashtag disalin. Tempel di aplikasi tujuan.")
                        engine.shareFile(output)
                    } else {
                        notifier.show("File hasil tidak ditemukan. Render ulang klip ini.")
                    }
                }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Share)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionChip("Editor", { onEdit(clip.id) }, height = 40.dp, icon = ClipMiIcons.Scissors)
            if (!rendering) {
                val label = when (clip.render) { RenderStatus.Done -> "Render ulang"; RenderStatus.Failed -> "Coba lagi"; else -> "Render" }
                ActionChip(label, { renders.start(clip.id) }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Film, primary = clip.render == RenderStatus.NotRendered, enabled = renders.active == null)
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (clip.review) {
                ReviewStatus.Candidate -> {
                    ActionChip("Pilih", { scope.launch { repo.db.clips().setReview(clip.id, ReviewStatus.Selected.value) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Check)
                    ActionChip("Buang", {
                        scope.launch { repo.db.clips().setReview(clip.id, ReviewStatus.Rejected.value) }
                        notifier.show("Klip dibuang", action = "Urungkan") { scope.launch { repo.db.clips().setReview(clip.id, ReviewStatus.Candidate.value) } }
                    }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Close)
                }
                ReviewStatus.Selected -> ActionChip("Batal pilih", { scope.launch { repo.db.clips().setReview(clip.id, ReviewStatus.Candidate.value) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Close)
                ReviewStatus.Rejected -> ActionChip("Pulihkan", { scope.launch { repo.db.clips().setReview(clip.id, ReviewStatus.Candidate.value) } }, Modifier.weight(1f), height = 40.dp, icon = ClipMiIcons.Refresh)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipMeta(clip: ClipEntity, renders: Renders, expanded: Boolean) {
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
            clip.render == RenderStatus.Done -> StatusBadge(if (clip.changedAfterRender) "Hasil lama" else "Siap unggah", if (clip.changedAfterRender) Tone.Warn else Tone.Good)
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

@Composable
private fun SegmentRow(segment: TranscriptSegmentEntity) {
    val c = ClipMi.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Timecode.format(segment.startTime), style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Text(segment.text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
    }
}
