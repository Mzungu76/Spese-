package it.spese.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
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
import it.spese.feature.capture.CaptureScreen
import it.spese.domain.advice.InsightTarget
import it.spese.feature.advice.AdviceScreen
import it.spese.feature.advice.MonthReviewScreen
import it.spese.feature.goals.GoalsScreen
import it.spese.feature.transfers.TransfersScreen
import it.spese.feature.budgets.BudgetsScreen
import it.spese.feature.reminders.RemindersScreen
import it.spese.domain.widgets.WidgetPage
import it.spese.feature.dashboard.HomeCallbacks
import it.spese.feature.widgets.CustomizeScreen
import it.spese.feature.health.HealthScreen
import it.spese.feature.more.MoreActions
import it.spese.feature.more.MoreScreen
import it.spese.feature.networth.NetWorthScreen
import it.spese.feature.recurring.RecurringScreen
import it.spese.feature.subscriptions.SubscriptionsScreen
import it.spese.feature.reports.ReportsScreen
import it.spese.feature.trends.TrendsScreen
import it.spese.feature.categories.CategoriesScreen
import it.spese.feature.categories.CategoryDetailScreen
import it.spese.feature.categories.CategoryDetailViewModel
import it.spese.feature.categories.ManageScreen
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
    const val REMINDERS = "reminders"; const val CUSTOMIZE = "customize/{page}"; const val CATEGORY = "category/{id}"; const val MANAGE = "manage/{tab}"; const val CAPTURE = "capture"; const val TRENDS = "trends"; const val REPORTS = "reports"; const val RECURRING = "recurring"; const val SUBSCRIPTIONS = "subscriptions"; const val GOALS = "goals"; const val NETWORTH = "networth"; const val REVIEW = "review"; const val ADVICE = "advice"; const val MORE = "more"; const val TRANSFERS_REVIEW = "transfers-review"; const val BUDGETS = "budgets"; const val HEALTH = "health"; const val SHARE = "share"
    fun customize(page: WidgetPage) = "customize/${page.name}"
    fun edit(id: Long) = "edit/$id"
    fun category(id: Long) = "category/$id"
    fun manage(tab: Int) = "manage/$tab"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

/** Cinque sezioni nella barra, equidistanti: oggi (Home), movimenti, obiettivi, patrimonio e «Altro» (categorie, piano, tendenze, dati e impostazioni). */
private val TABS = listOf(
    Tab(Routes.DASHBOARD, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.TRANSACTIONS, "Movimenti", Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong),
    Tab(Routes.GOALS, "Obiettivi", Icons.Outlined.Savings, Icons.Filled.Savings),
    Tab(Routes.NETWORTH, "Patrimonio", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    Tab(Routes.MORE, "Altro", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

/** Categorie e Piano si aprono da «Altro» ma mostrano la barra, con «Altro» evidenziato. */
private val MORE_ROUTES = setOf(Routes.MORE, Routes.CATEGORIES, Routes.PLAN)

@Composable
private fun SpeseBottomBar(route: String?, onTab: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp)) {
            TABS.forEach { t ->
                val on = route == t.route || (t.route == Routes.MORE && route in MORE_ROUTES)
                val tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onTab(t.route) }.testTag("tab_${t.route}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (on) t.selected else t.icon, null, Modifier.size(26.dp), tint = tint)
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = tint)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onManual: () -> Unit, onScan: () -> Unit, onImport: () -> Unit, onRestore: () -> Unit, onRecurring: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Cosa vuoi aggiungere?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            @Composable
            fun opt(icon: ImageVector, title: String, sub: String, tint: Color, bg: Color, tag: String, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { onDismiss(); onClick() }.testTag(tag).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(48.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
                    Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            opt(Icons.Filled.Add, "Movimento a mano", "Importo, descrizione e categoria", Color(0xFF9FC0FF), Color(0xFF16315F), "add_manual", onManual)
            opt(Icons.Filled.Autorenew, "Entrata o spesa ricorrente", "Stipendio, affitto incassato, abbonamenti: si creano da soli", Color(0xFF9FC0FF), Color(0xFF16315F), "add_recurring", onRecurring)
            opt(Icons.Filled.DocumentScanner, "Scansiona uno scontrino", "Riconosco importo, data ed esercente", Color(0xFF34D399), Color(0xFF12332A), "add_scan", onScan)
            opt(Icons.Filled.UploadFile, "Importa estratto conto", "Excel, CSV o TXT dalla tua banca", Color(0xFFFFB020), Color(0xFF3A2A08), "add_import", onImport)
            opt(Icons.Filled.Restore, "Ripristina un backup", "Dalla versione web o da un altro telefono", Color(0xFFC792F0), Color(0xFF2A1E3D), "add_restore", onRestore)
        }
    }
}

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val isTab = TABS.any { it.route == route } || route in MORE_ROUTES
    val undoAvailable by vm.undoAvailable.collectAsStateWithLifecycle()
    val incoming by vm.incoming.uri.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var addOpen by remember { mutableStateOf(false) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.generateRecurring()
        // dopo un aggiornamento dell'app Android a volte non ricollega l'ascolto delle notifiche: lo si richiede a ogni apertura
        if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(appCtx).contains(appCtx.packageName))
            try { android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(appCtx, it.spese.app.capture.CaptureListenerService::class.java)) } catch (_: Exception) { }
    }
    LaunchedEffect(Unit) { vm.messenger.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) } }
    // errore dell'ultima volta: si mostra una volta, con il pulsante per copiarlo
    val crashFile = remember { java.io.File(appCtx.filesDir, it.spese.app.SpeseApplication.CRASH_FILE) }
    var crash by remember { mutableStateOf(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }
    crash?.let { text ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(onDismissRequest = { crashFile.delete(); crash = null }, title = { Text("L'app si è chiusa l'ultima volta") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Ecco il dettaglio dell'errore (resta sul telefono). Copialo e mandalo per farlo correggere.", style = MaterialTheme.typography.bodySmall); Text(text.take(1800), style = MaterialTheme.typography.labelSmall) } },
            confirmButton = { TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copia l'errore") } },
            dismissButton = { TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Chiudi") } })
    }
    val launchRoute by vm.incoming.route.collectAsStateWithLifecycle()
    LaunchedEffect(launchRoute) { launchRoute?.let { r -> nav.navigate(r) { launchSingleTop = true }; vm.incoming.consumeRoute() } }
    LaunchedEffect(incoming) { if (incoming != null) nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }

    val menu = DataMenu(
        onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onAnalytics = { nav.navigate(Routes.ANALYTICS) },
        onAccounts = { nav.navigate(Routes.ACCOUNTS) }, onSettings = { nav.navigate(Routes.SETTINGS) }, undoAvailable = undoAvailable, onUndo = vm::undoLast, onReports = { nav.navigate(Routes.REPORTS) }, onRecurring = { nav.navigate(Routes.RECURRING) },
    )
    fun goTab(r: String) = nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    /** I tocchi dei widget condivisi: stessi per la Home e per le altre pagine che li ospitano. */
    val homeCb = HomeCallbacks(
        onOpenAlerts = { nav.navigate(Routes.ALERTS) }, onOpenCategory = { id -> if (id != null) nav.navigate(Routes.category(id)) else { vm.showCategory(null); goTab(Routes.TRANSACTIONS) } },
        onOpenNetWorth = { goTab(Routes.NETWORTH) }, onOpenCategories = { goTab(Routes.CATEGORIES) }, onOpenTransfers = { vm.showTransfers(); goTab(Routes.TRANSACTIONS) },
        onOpenTrends = { nav.navigate(Routes.TRENDS) }, onOpenCapture = { nav.navigate(Routes.CAPTURE) }, onOpenSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) }, onOpenAdvice = { nav.navigate(Routes.ADVICE) },
        onOpenTransfersReview = { nav.navigate(Routes.TRANSFERS_REVIEW) }, onOpenUnassigned = { vm.requestReview(); goTab(Routes.TRANSACTIONS) }, onOpenHealth = { nav.navigate(Routes.HEALTH) }, onOpenReview = { nav.navigate(Routes.REVIEW) },
        onAdd = { addOpen = true }, onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onOpenReminders = { nav.navigate(Routes.REMINDERS) },
        onOpenGoals = { goTab(Routes.GOALS) }, onOpenBudgets = { nav.navigate(Routes.BUDGETS) },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0),
        bottomBar = { if (isTab) SpeseBottomBar(route, ::goTab) },
    ) { pad ->
        NavHost(nav, Routes.DASHBOARD, Modifier, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }, popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(menu, onCustomize = { nav.navigate(Routes.customize(WidgetPage.HOME)) }, onOpenAlerts = { nav.navigate(Routes.ALERTS) }, onOpenNetWorth = { goTab(Routes.NETWORTH) }, onOpenCapture = { nav.navigate(Routes.CAPTURE) }, onOpenSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) }, onOpenCategory = { id -> if (id != null) nav.navigate(Routes.category(id)) else { vm.showCategory(null); goTab(Routes.TRANSACTIONS) } },
                    onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onRestore = { nav.navigate(Routes.SETTINGS) },
                    onAdd = { addOpen = true }, onAnalytics = { nav.navigate(Routes.ANALYTICS) }, onOpenClassify = { nav.navigate(Routes.manage(2)) }, onOpenPlan = { goTab(Routes.PLAN) },
                    onOpenCategories = { goTab(Routes.CATEGORIES) }, onOpenTransfers = { vm.showTransfers(); goTab(Routes.TRANSACTIONS) }, onSearch = { vm.requestSearch(); goTab(Routes.TRANSACTIONS) }, onOpenTrends = { nav.navigate(Routes.TRENDS) }, onOpenAdvice = { nav.navigate(Routes.ADVICE) }, onOpenTransfersReview = { nav.navigate(Routes.TRANSFERS_REVIEW) }, onOpenUnassigned = { vm.requestReview(); goTab(Routes.TRANSACTIONS) }, onOpenHealth = { nav.navigate(Routes.HEALTH) }, onOpenReview = { nav.navigate(Routes.REVIEW) }, onOpenReminders = { nav.navigate(Routes.REMINDERS) }, onOpenGoals = { goTab(Routes.GOALS) }, onOpenBudgets = { nav.navigate(Routes.BUDGETS) }, contentPadding = pad)
            }
            composable(Routes.TRANSACTIONS) { TransactionsScreen(menu, onOpen = { nav.navigate(Routes.edit(it)) }, onAdd = { addOpen = true }, onOpenClassify = { nav.navigate(Routes.manage(2)) }, onCustomize = { nav.navigate(Routes.customize(WidgetPage.TRANSACTIONS)) }, home = homeCb, bottomPadding = pad) }
            composable(Routes.CATEGORIES) { CategoriesScreen(menu, onOpenCategory = { nav.navigate(Routes.category(it)) }, onManage = { nav.navigate(Routes.manage(it)) }, onOpenTrends = { nav.navigate(Routes.TRENDS) }, bottomPadding = pad) }
            composable(Routes.CATEGORY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val catVm: CategoryDetailViewModel = hiltViewModel()
                CategoryDetailScreen(onBack = { nav.popBackStack() }, onOpenTransaction = { nav.navigate(Routes.edit(it)) }, onOpenMovements = { catVm.showInMovements(); goTab(Routes.TRANSACTIONS) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) }, onOpenPlan = { goTab(Routes.PLAN) }, vm = catVm)
            }
            composable(Routes.MANAGE, arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { entry ->
                ManageScreen(initialTab = entry.arguments?.getInt("tab") ?: 0, onBack = { nav.popBackStack() })
            }
            composable(Routes.PLAN) { PlanScreen(menu, bottomPadding = pad) }
            composable(Routes.ALERTS) { AlertsScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onOpenSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) }) }
            composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })) { TransactionEditScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.IMPORT) { ImportScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SCAN) { ScanScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ANALYTICS) { AnalyticsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onOpenCapture = { nav.navigate(Routes.CAPTURE) }) }
            composable(Routes.CAPTURE) { CaptureScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.RECURRING) { RecurringScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.REVIEW) { MonthReviewScreen(onBack = { nav.popBackStack() }, onOpenAdvice = { nav.navigate(Routes.ADVICE) }) }
            composable(Routes.ADVICE) {
                AdviceScreen(onBack = { nav.popBackStack() }, onOpen = { t ->
                    when (t) {
                        InsightTarget.GOALS -> goTab(Routes.GOALS)
                        InsightTarget.NET_WORTH -> goTab(Routes.NETWORTH)
                        InsightTarget.SUBSCRIPTIONS -> nav.navigate(Routes.SUBSCRIPTIONS)
                        InsightTarget.REVIEW -> nav.navigate(Routes.REVIEW)
                        InsightTarget.NONE -> Unit
                    }
                })
            }
            composable(Routes.NETWORTH) { NetWorthScreen(menu = menu, bottomPadding = pad, onCustomize = { nav.navigate(Routes.customize(WidgetPage.NETWORTH)) }, home = homeCb) }
            composable(Routes.GOALS) { GoalsScreen(menu = menu, bottomPadding = pad, onOpenBudgets = { nav.navigate(Routes.BUDGETS) }, onCustomize = { nav.navigate(Routes.customize(WidgetPage.GOALS)) }, home = homeCb) }
            composable(Routes.HEALTH) { HealthScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BUDGETS) { BudgetsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CUSTOMIZE, arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "HOME" })) { CustomizeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.REMINDERS) { RemindersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TRANSFERS_REVIEW) { TransfersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MORE) {
                MoreScreen(menu, MoreActions(
                    onCategories = { goTab(Routes.CATEGORIES) }, onHealth = { nav.navigate(Routes.HEALTH) }, onBudgets = { nav.navigate(Routes.BUDGETS) }, onPlan = { goTab(Routes.PLAN) }, onTrends = { nav.navigate(Routes.TRENDS) }, onAdvice = { nav.navigate(Routes.ADVICE) }, onReview = { nav.navigate(Routes.REVIEW) },
                    onAlerts = { nav.navigate(Routes.ALERTS) }, onRecurring = { nav.navigate(Routes.RECURRING) }, onSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) }, onReports = { nav.navigate(Routes.REPORTS) },
                    onAccounts = { nav.navigate(Routes.ACCOUNTS) }, onShare = { nav.navigate(Routes.SHARE) }, onTransfers = { nav.navigate(Routes.TRANSFERS_REVIEW) }, onImport = { nav.navigate(Routes.IMPORT) }, onScan = { nav.navigate(Routes.SCAN) }, onSettings = { nav.navigate(Routes.SETTINGS) }, onReminders = { nav.navigate(Routes.REMINDERS) }, onCustomize = { nav.navigate(Routes.customize(WidgetPage.HOME)) }), pad)
            }
            composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.REPORTS) { ReportsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TRENDS) { TrendsScreen(onBack = { nav.popBackStack() }, onOpenCategory = { nav.navigate(Routes.category(it)) }) }
            composable(Routes.ACCOUNTS) { AccountsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SHARE) { it.spese.feature.share.ShareScreen(onBack = { nav.popBackStack() }) }
        }
    }
    if (addOpen) AddSheet({ addOpen = false }, { nav.navigate(Routes.edit(0)) }, { nav.navigate(Routes.SCAN) }, { nav.navigate(Routes.IMPORT) }, { nav.navigate(Routes.SETTINGS) }, { nav.navigate(Routes.RECURRING) })
}
