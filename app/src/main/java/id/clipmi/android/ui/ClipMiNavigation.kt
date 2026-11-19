package id.clipmi.android.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import id.clipmi.android.ui.styles.StylesHeader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import id.clipmi.android.ui.components.ScreenFrame
import id.clipmi.android.ui.components.isWideScreen
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import id.clipmi.android.AppContainer
import id.clipmi.android.lab.LabScreen
import id.clipmi.android.ui.components.ClipMiSnackbarHost
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.LocalNotifier
import id.clipmi.android.ui.components.Notifier
import id.clipmi.android.ui.components.SnackbarInset
import id.clipmi.android.ui.components.StatusBarScrim
import id.clipmi.android.ui.editor.EditorScreen
import id.clipmi.android.ui.screens.AiUsageScreen
import id.clipmi.android.ui.screens.BumpersScreen
import id.clipmi.android.ui.screens.ResultsScreen
import id.clipmi.android.ui.styles.CaptionStyleEditScreen
import id.clipmi.android.ui.styles.CaptionStylesScreen
import id.clipmi.android.ui.styles.FrameStyleEditScreen
import id.clipmi.android.ui.styles.FrameStylesScreen
import id.clipmi.android.ui.screens.AnalysisSettingsScreen
import id.clipmi.android.ui.screens.CorrectionsScreen
import id.clipmi.android.ui.screens.DataScreen
import id.clipmi.android.ui.screens.ProjectDetailScreen
import id.clipmi.android.ui.screens.ProjectListScreen
import id.clipmi.android.ui.screens.SettingsScreen
import id.clipmi.android.ui.screens.TranscriptionSettingsScreen
import id.clipmi.android.ui.schedule.AccountsScreen
import id.clipmi.android.ui.schedule.PerformanceScreen
import id.clipmi.android.ui.schedule.ReadyScreen
import id.clipmi.android.ui.schedule.ScheduleEditScreen
import id.clipmi.android.ui.schedule.ScheduleScreen
import id.clipmi.android.ui.schedule.StatsScreen
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.serialization.Serializable

@Serializable object ProjectRoute
@Serializable data class ProjectDetailRoute(val id: Long)
@Serializable object ScheduleRoute
@Serializable object StatsRoute
@Serializable object SettingsRoute
@Serializable object CorrectionsRoute
@Serializable object LabRoute
@Serializable object AiUsageRoute
@Serializable object TranscriptionSettingsRoute
@Serializable object AnalysisSettingsRoute
@Serializable data class EditorRoute(val clipId: Long)
@Serializable object CaptionStylesRoute
@Serializable data class CaptionStyleEditRoute(val id: Long, val copyOf: Long = 0)
@Serializable object FrameStylesRoute
@Serializable data class FrameStyleEditRoute(val id: Long, val copyOf: Long = 0)
@Serializable object BumpersRoute
@Serializable object ResultsRoute
@Serializable object AccountsRoute
@Serializable data class ScheduleEditRoute(val id: Long = 0, val clipId: Long = 0, val date: String? = null)
@Serializable data class ReadyRoute(val id: Long)
@Serializable object PerformanceRoute
@Serializable object DataRoute

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(ProjectRoute, "Project", ClipMiIcons.Home),
    Tab(ScheduleRoute, "Jadwal", ClipMiIcons.Calendar),
    Tab(StatsRoute, "Statistik", ClipMiIcons.Chart),
    Tab(SettingsRoute, "Pengaturan", ClipMiIcons.Sliders),
)

/**
 * Akar navigasi: layar penuh tepi-ke-tepi, navigasi pil melayang di empat layar utama (layar lebar: pil tegak di kiri),
 * snackbar di atasnya.
 * @param shared hasil membagikan link dari aplikasi lain: project yang dibuka, atau pesan untuk daftar project.
 */
@Composable
fun ClipMiNavigation(container: AppContainer, shared: SharedResult?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    val notifier = remember { Notifier(SnackbarHostState(), scope) }
    val topLevel = tabs.any { tab -> entry?.destination?.chain()?.any { it.hasRoute(tab.route::class) } == true }

    // Lembar pembaruan (dari notifikasi, kartu di Project, atau Pengaturan) dan catatan "yang baru" setelah pembaruan.
    var updateOpen by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf<id.clipmi.android.domain.AppUpdater.Release?>(null) }
    // APK versi baru selesai diunduh di latar: lembar "siap dipasang" terbuka sendiri sekali per versi, hanya di layar
    // utama (tidak menyela editor atau form).
    val context = androidx.compose.ui.platform.LocalContext.current
    val available by container.updates.available.collectAsState(initial = null)
    val download by remember { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(id.clipmi.android.work.UpdateQueue.DOWNLOAD) }
        .collectAsState(initial = emptyList())
    val downloadState = download.lastOrNull()?.state
    LaunchedEffect(available?.version, downloadState, topLevel) {
        val release = available ?: return@LaunchedEffect
        if (!topLevel || downloadState == androidx.work.WorkInfo.State.RUNNING) return@LaunchedEffect
        if (id.clipmi.android.domain.AppUpdater(context).downloaded(release.version) != null && container.updates.markPrompted(release.version)) updateOpen = true
    }
    LaunchedEffect(shared) {
        val result = shared ?: return@LaunchedEffect
        result.openProject?.let { nav.navigate(ProjectDetailRoute(it)) { launchSingleTop = true } }
        result.openEditor?.let { nav.navigate(EditorRoute(it)) { launchSingleTop = true } }
        if (result.openScheduleList || result.openSchedule != null) {
            nav.navigate(ScheduleRoute) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        result.openSchedule?.let { nav.navigate(ReadyRoute(it)) { launchSingleTop = true } }
        result.message?.let { notifier.show(it) }
        if (result.openUpdate) updateOpen = true
        result.whatsNew?.let { whatsNew = it }
        onSharedHandled()
    }

    CompositionLocalProvider(LocalNotifier provides notifier) {
        val wide = isWideScreen()
        val navigateTab: (Any) -> Unit = { route ->
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        Box(Modifier.fillMaxSize().background(ClipMi.colors.bg)) {
          // Layar lebar: navigasi utama di samping kiri, isi di kanannya (poni kamera di kiri ditangani navigasinya).
          Row(Modifier.fillMaxSize()) {
            if (wide) {
                AnimatedVisibility(
                    visible = topLevel,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) { SideNavRail(entry?.destination, navigateTab) }
            }
            NavHost(
                nav, startDestination = ProjectRoute,
                modifier = Modifier.weight(1f).fillMaxHeight().then(
                    if (wide && topLevel) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier,
                ),
            ) {
                composable<ProjectRoute> { ScreenFrame { ProjectListScreen(container, onOpen = { nav.navigate(ProjectDetailRoute(it)) }, onOpenResults = { nav.navigate(ResultsRoute) }, onOpenUpdate = { updateOpen = true }) } }
                composable<ProjectDetailRoute> {
                    ProjectDetailScreen(container, it.toRoute<ProjectDetailRoute>().id, onBack = { nav.popBackStack() }, onOpenEditor = { clip -> nav.navigate(EditorRoute(clip)) }, onSchedule = { clip -> nav.navigate(ScheduleEditRoute(clipId = clip)) })
                }
                composable<EditorRoute> { EditorScreen(container, it.toRoute<EditorRoute>().clipId, onClose = { nav.popBackStack() }, onRendered = { if (!nav.popBackStack(ResultsRoute, inclusive = false)) nav.navigate(ResultsRoute) }) }
                composable<ScheduleRoute> {
                    ScreenFrame { ScheduleScreen(
                        container,
                        onOpenAccounts = { nav.navigate(AccountsRoute) },
                        onOpenSchedule = { nav.navigate(ReadyRoute(it)) },
                        onNewSchedule = { clip, date -> nav.navigate(ScheduleEditRoute(clipId = clip, date = date)) },
                    ) }
                }
                composable<StatsRoute> { ScreenFrame { StatsScreen(container, onOpenSchedule = { nav.navigate(ReadyRoute(it)) }, onOpenPerformance = { nav.navigate(PerformanceRoute) }) } }
                composable<PerformanceRoute> { ScreenFrame { PerformanceScreen(container, onBack = { nav.popBackStack() }, onOpenSchedule = { nav.navigate(ReadyRoute(it)) }) } }
                composable<AccountsRoute> { ScreenFrame { AccountsScreen(container, onBack = { nav.popBackStack() }) } }
                composable<DataRoute> { ScreenFrame { DataScreen(container, onBack = { nav.popBackStack() }) } }
                composable<ScheduleEditRoute> {
                    val route = it.toRoute<ScheduleEditRoute>()
                    ScreenFrame { ScheduleEditScreen(container, route.id, route.clipId, route.date, onBack = { nav.popBackStack() }, onSaved = { saved ->
                        // Jadwal baru: kembali ke asal. Diubah: kembali ke Siap Unggah. Dihapus: tutup juga Siap Unggah-nya.
                        if (saved == 0L) { nav.popBackStack<ReadyRoute>(inclusive = true) || nav.popBackStack() } else nav.popBackStack()
                    }) }
                }
                composable<ReadyRoute> {
                    val id = it.toRoute<ReadyRoute>().id
                    ScreenFrame { ReadyScreen(container, id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(ScheduleEditRoute(id = id)) }, onOpened = { other ->
                        nav.navigate(ReadyRoute(other)) { popUpTo<ReadyRoute> { inclusive = true } }
                    }) }
                }
                composable<SettingsRoute> {
                    ScreenFrame { SettingsScreen(
                        container,
                        onOpenTranscription = { nav.navigate(TranscriptionSettingsRoute) },
                        onOpenAnalysis = { nav.navigate(AnalysisSettingsRoute) },
                        onOpenCorrections = { nav.navigate(CorrectionsRoute) },
                        onOpenUsage = { nav.navigate(AiUsageRoute) },
                        onOpenLab = { nav.navigate(LabRoute) },
                        onOpenCaptionStyles = { nav.navigate(CaptionStylesRoute) },
                        onOpenFrameStyles = { nav.navigate(FrameStylesRoute) },
                        onOpenBumpers = { nav.navigate(BumpersRoute) },
                        onOpenResults = { nav.navigate(ResultsRoute) },
                        onOpenData = { nav.navigate(DataRoute) },
                    ) }
                }
                composable<TranscriptionSettingsRoute> { ScreenFrame { TranscriptionSettingsScreen(container, onBack = { nav.popBackStack() }) } }
                composable<AnalysisSettingsRoute> { ScreenFrame { AnalysisSettingsScreen(container, onBack = { nav.popBackStack() }) } }
                composable<CorrectionsRoute> { ScreenFrame { CorrectionsScreen(container, onBack = { nav.popBackStack() }) } }
                composable<LabRoute> { Box(Modifier.statusBarsPadding()) { LabScreen() } }
                composable<AiUsageRoute> { ScreenFrame { AiUsageScreen(container, onBack = { nav.popBackStack() }) } }
                composable<CaptionStylesRoute> {
                    // Menu Gaya klip: tab Subtitle (rute ini) dan Bingkai (FrameStylesRoute) saling menggantikan.
                    ScreenFrame { CaptionStylesScreen(container, onBack = { nav.popBackStack() }, onEdit = { id, copy -> nav.navigate(CaptionStyleEditRoute(id, copy)) }, header = {
                        StylesHeader(0, onBack = { nav.popBackStack() }, onAdd = { nav.navigate(CaptionStyleEditRoute(0, 0)) }) { tab -> if (tab == 1) nav.navigate(FrameStylesRoute) { popUpTo<CaptionStylesRoute> { inclusive = true } } }
                    }) }
                }
                composable<CaptionStyleEditRoute> {
                    val route = it.toRoute<CaptionStyleEditRoute>()
                    ScreenFrame(maxWidth = null) { CaptionStyleEditScreen(container, route.id, route.copyOf, onBack = { nav.popBackStack() }, onDuplicate = { from ->
                        nav.navigate(CaptionStyleEditRoute(0, from)) { popUpTo<CaptionStylesRoute>() }
                    }) }
                }
                composable<FrameStylesRoute> {
                    ScreenFrame { FrameStylesScreen(container, onBack = { nav.popBackStack() }, onEdit = { id, copy -> nav.navigate(FrameStyleEditRoute(id, copy)) }, header = {
                        StylesHeader(1, onBack = { nav.popBackStack() }, onAdd = { nav.navigate(FrameStyleEditRoute(0, 0)) }) { tab -> if (tab == 0) nav.navigate(CaptionStylesRoute) { popUpTo<FrameStylesRoute> { inclusive = true } } }
                    }) }
                }
                composable<FrameStyleEditRoute> {
                    val route = it.toRoute<FrameStyleEditRoute>()
                    ScreenFrame(maxWidth = null) { FrameStyleEditScreen(container, route.id, route.copyOf, onBack = { nav.popBackStack() }, onDuplicate = { from ->
                        nav.navigate(FrameStyleEditRoute(0, from)) { popUpTo<FrameStylesRoute>() }
                    }) }
                }
                composable<BumpersRoute> { ScreenFrame { BumpersScreen(container, onBack = { nav.popBackStack() }) } }
                composable<ResultsRoute> {
                    ScreenFrame { ResultsScreen(
                        container, onBack = { nav.popBackStack() },
                        // Editor atau project yang sudah terbuka di belakang: kembali ke sana, bukan membuka salinan baru.
                        onOpenProject = { if (!nav.popBackStack(ProjectDetailRoute(it), inclusive = false)) nav.navigate(ProjectDetailRoute(it)) },
                        onOpenEditor = { if (!nav.popBackStack(EditorRoute(it), inclusive = false)) nav.navigate(EditorRoute(it)) },
                        onSchedule = { nav.navigate(ScheduleEditRoute(clipId = it)) },
                        onOpenSchedule = { nav.navigate(ReadyRoute(it)) },
                    ) }
                }
            }
          }

            if (topLevel) StatusBarScrim(Modifier.align(Alignment.TopCenter), color = ClipMi.colors.bg.copy(alpha = 0.96f))
            if (!wide) {
                AnimatedVisibility(
                    visible = topLevel,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { FloatingNavBar(entry?.destination, navigateTab) }
            }
            if (topLevel) SnackbarInset(FloatingNavSpace - 16.dp)
            ClipMiSnackbarHost(notifier, Modifier.align(Alignment.BottomCenter))
        }
        if (updateOpen) id.clipmi.android.ui.screens.UpdateSheet(container, onDone = { updateOpen = false })
        whatsNew?.let { r -> id.clipmi.android.ui.screens.WhatsNewSheet(r, onDone = { whatsNew = null }) }
    }
}

/** Navigasi utama: pil melayang 68 dp, item aktif berlatar emerald. */
@Composable
private fun FloatingNavBar(destination: NavDestination?, onNavigate: (Any) -> Unit) {
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
        tabs.forEach { tab -> NavItem(tab, destination, Modifier.weight(1f).fillMaxHeight(), onNavigate) }
    }
}

/** Navigasi utama di layar lebar (tablet, HP miring): pil tegak melayang di kiri, sama gayanya dengan pil bawah. */
@Composable
private fun SideNavRail(destination: NavDestination?, onNavigate: (Any) -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(34.dp)
    Box(
        Modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(84.dp)
                .shadow(if (c.isDark) 0.dp else 12.dp, shape, ambientColor = c.text.copy(alpha = 0.08f), spotColor = c.text.copy(alpha = 0.08f))
                .clip(shape)
                .background(if (c.isDark) c.panel.copy(alpha = 0.96f) else c.card)
                .border(1.dp, if (c.isDark) c.lineStrong else c.line, shape)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab -> NavItem(tab, destination, Modifier.fillMaxWidth().height(64.dp), onNavigate) }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, destination: NavDestination?, modifier: Modifier, onNavigate: (Any) -> Unit) {
    val c = ClipMi.colors
    val selected = destination?.chain()?.any { it.hasRoute(tab.route::class) } == true
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable(role = Role.Tab, onClickLabel = tab.label) { onNavigate(tab.route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.clip(CircleShape).background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Icon(tab.icon, contentDescription = null, tint = if (selected) c.onAccent else c.textMuted, modifier = Modifier.size(22.dp))
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = if (selected) c.text else c.textMuted,
            maxLines = 1,
        )
    }
}

/** Hasil share intent: buka satu project, atau tampilkan pesan (beberapa project dibuat / link ditolak). */
data class SharedResult(
    val openProject: Long? = null,
    val message: String? = null,
    val openEditor: Long? = null,
    val openSchedule: Long? = null,
    val openScheduleList: Boolean = false,
    val openUpdate: Boolean = false,
    val whatsNew: id.clipmi.android.domain.AppUpdater.Release? = null,
)

private fun NavDestination.chain(): Sequence<NavDestination> = generateSequence(this) { it.parent }
