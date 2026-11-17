package id.clipmi.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import id.clipmi.android.ui.screens.AiUsageScreen
import id.clipmi.android.ui.screens.CorrectionsScreen
import id.clipmi.android.ui.screens.ProjectDetailScreen
import id.clipmi.android.ui.screens.ProjectListScreen
import id.clipmi.android.ui.screens.ScheduleScreen
import id.clipmi.android.ui.screens.SettingsScreen
import id.clipmi.android.ui.screens.StatsScreen
import kotlinx.serialization.Serializable

@Serializable object ProjectRoute
@Serializable data class ProjectDetailRoute(val id: Long)
@Serializable object ScheduleRoute
@Serializable object StatsRoute
@Serializable object SettingsRoute
@Serializable object CorrectionsRoute
@Serializable object LabRoute
@Serializable object AiUsageRoute

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(ProjectRoute, "Project", Icons.Filled.Home),
    Tab(ScheduleRoute, "Jadwal", Icons.Filled.DateRange),
    Tab(StatsRoute, "Statistik", Icons.Filled.Info),
    Tab(SettingsRoute, "Pengaturan", Icons.Filled.Settings),
)

/**
 * @param shared hasil membagikan link dari aplikasi lain: project yang dibuka, atau pesan untuk daftar project.
 */
@Composable
fun ClipMiNavigation(container: AppContainer, shared: SharedResult?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()

    LaunchedEffect(shared) {
        val projectId = shared?.openProject ?: return@LaunchedEffect
        nav.navigate(ProjectDetailRoute(projectId)) { launchSingleTop = true }
        onSharedHandled()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = entry?.destination?.chain()?.any { it.hasRoute(tab.route::class) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = ProjectRoute, modifier = Modifier.padding(padding)) {
            composable<ProjectRoute> {
                ProjectListScreen(
                    container,
                    onOpen = { nav.navigate(ProjectDetailRoute(it)) },
                    message = shared?.message,
                    onMessageShown = onSharedHandled,
                )
            }
            composable<ProjectDetailRoute> { ProjectDetailScreen(container, it.toRoute<ProjectDetailRoute>().id, onBack = { nav.popBackStack() }) }
            composable<ScheduleRoute> { ScheduleScreen() }
            composable<StatsRoute> { StatsScreen() }
            composable<SettingsRoute> {
                SettingsScreen(
                    container,
                    onOpenCorrections = { nav.navigate(CorrectionsRoute) },
                    onOpenUsage = { nav.navigate(AiUsageRoute) },
                    onOpenLab = { nav.navigate(LabRoute) },
                )
            }
            composable<CorrectionsRoute> { CorrectionsScreen(container, onBack = { nav.popBackStack() }) }
            composable<LabRoute> { LabScreen() }
            composable<AiUsageRoute> { AiUsageScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}

/** Hasil share intent: buka satu project, atau tampilkan pesan (beberapa project dibuat / link ditolak). */
data class SharedResult(val openProject: Long? = null, val message: String? = null)

private fun NavDestination.chain(): Sequence<NavDestination> = generateSequence(this) { it.parent }
