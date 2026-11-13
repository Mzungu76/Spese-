package it.spese.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.spese.app.common.DataMenu
import it.spese.app.common.RootViewModel
import it.spese.feature.accounts.AccountsScreen
import it.spese.feature.alerts.AlertsScreen
import it.spese.feature.analytics.AnalyticsScreen
import it.spese.feature.categories.CategoriesScreen
import it.spese.feature.dashboard.DashboardScreen
import it.spese.feature.importer.ImportScreen
import it.spese.feature.ocr.ScanScreen
import it.spese.feature.settings.SettingsScreen
import it.spese.feature.simulation.PlanScreen
import it.spese.feature.transactions.TransactionEditScreen
import it.spese.feature.transactions.TransactionsScreen

object Routes {
    const val DASHBOARD = "dashboard"; const val TRANSACTIONS = "transactions"; const val CATEGORIES = "categories"; const val PLAN = "plan"; const val ALERTS = "alerts"
    const val IMPORT = "import"; const val ANALYTICS = "analytics"; const val SCAN = "scan"; const val SETTINGS = "settings"; const val ACCOUNTS = "accounts"
    const val EDIT = "edit/{id}"
    fun edit(id: Long) = "edit/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val TABS = listOf(
    Tab(Routes.DASHBOARD, "Panoramica", Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    Tab(Routes.TRANSACTIONS, "Movimenti", Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong),
    Tab(Routes.CATEGORIES, "Categorie", Icons.Outlined.Category, Icons.Filled.Category),
    Tab(Routes.PLAN, "Piano", Icons.Outlined.Flag, Icons.Filled.Flag),
    Tab(Routes.ALERTS, "Avvisi", Icons.Outlined.Notifications, Icons.Filled.Notifications),
)

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val isTab = TABS.any { it.route == route }
    val undoAvailable by vm.undoAvailable.collectAsStateWithLifecycle()
    val incoming by vm.incoming.uri.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messenger.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) } }
    LaunchedEffect(incoming) { if (incoming != null) nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }

    val menu = DataMenu(
        onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onAnalytics = { nav.navigate(Routes.ANALYTICS) },
        onAccounts = { nav.navigate(Routes.ACCOUNTS) }, onSettings = { nav.navigate(Routes.SETTINGS) }, undoAvailable = undoAvailable, onUndo = vm::undoLast,
    )
    fun goTab(r: String) = nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (isTab) NavigationBar {
                TABS.forEach { t -> NavigationBarItem(route == t.route, { goTab(t.route) }, { Icon(if (route == t.route) t.selected else t.icon, t.label) }, label = { Text(t.label, maxLines = 1) }, modifier = Modifier.testTag("tab_${t.route}")) }
            }
        },
    ) { pad ->
        NavHost(nav, Routes.DASHBOARD, Modifier, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }, popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(menu, onOpenAlerts = { goTab(Routes.ALERTS) }, onOpenCategory = { id -> vm.showCategory(id); goTab(Routes.TRANSACTIONS) },
                    onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onRestore = { nav.navigate(Routes.SETTINGS) }, contentPadding = pad)
            }
            composable(Routes.TRANSACTIONS) { TransactionsScreen(menu, onOpen = { nav.navigate(Routes.edit(it)) }, onAdd = { nav.navigate(Routes.edit(0)) }, bottomPadding = pad) }
            composable(Routes.CATEGORIES) { CategoriesScreen(menu, bottomPadding = pad) }
            composable(Routes.PLAN) { PlanScreen(menu, bottomPadding = pad) }
            composable(Routes.ALERTS) { AlertsScreen(menu, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, bottomPadding = pad) }
            composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })) { TransactionEditScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.IMPORT) { ImportScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SCAN) { ScanScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ANALYTICS) { AnalyticsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ACCOUNTS) { AccountsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
