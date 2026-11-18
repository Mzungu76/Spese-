package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import it.spese.feature.categories.CategoryRankRow
import it.spese.feature.categories.ComparisonSection
import it.spese.feature.categories.OpportunitiesCard
import it.spese.feature.categories.WatchCard
import it.spese.domain.finance.SavingsOpportunities
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.DataMenu
import it.spese.app.common.FilterSheet
import it.spese.app.common.FilterViewModel
import it.spese.app.common.LargeHeader
import it.spese.app.common.SearchFilterBar
import it.spese.app.common.RoundIconButton
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.domain.finance.PeriodResolver
import it.spese.core.model.TxKind
import it.spese.core.designsystem.chart.GapAreaChart
import it.spese.core.designsystem.chart.GapPoint
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.Period
import java.math.BigDecimal

/**
 * Home essenziale: un numero, un grafico, un selettore del periodo e una riga per gli avvisi.
 * Totali, categorie e dettagli stanno nelle altre sezioni (e in Analisi).
 */
@Composable
fun DashboardScreen(
    menu: DataMenu, onOpenAlerts: () -> Unit, onOpenCategory: (Long?) -> Unit, onImport: () -> Unit, onScan: () -> Unit, onRestore: () -> Unit,
    onAdd: () -> Unit, onAnalytics: () -> Unit, onOpenClassify: () -> Unit, onOpenPlan: () -> Unit,
    onCustomize: () -> Unit = {}, onOpenNetWorth: () -> Unit = {}, onOpenCategories: () -> Unit = {}, onOpenTransfers: () -> Unit = {}, onSearch: () -> Unit = {}, onOpenTrends: () -> Unit = {}, onOpenCapture: () -> Unit = {}, onOpenSubscriptions: () -> Unit = {}, onOpenAdvice: () -> Unit = {}, onOpenTransfersReview: () -> Unit = {}, onOpenUnassigned: () -> Unit = {}, onOpenHealth: () -> Unit = {}, onOpenReview: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(), vm: DashboardViewModel = hiltViewModel(), filterVm: FilterViewModel = hiltViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val pendingNotifs by vm.pendingNotifications.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val pendingAmounts by vm.pendingAmounts.collectAsStateWithLifecycle()
    val safe by vm.safeToSpend.collectAsStateWithLifecycle()
    val advice by vm.advice.collectAsStateWithLifecycle()
    val transferCount by vm.transferPairs.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val m = st.model
    var filters by remember { mutableStateOf(false) }
    val alertCount = m?.alerts?.count { it.level >= 1 } ?: 0
    Column(Modifier.fillMaxSize()) {
        LargeHeader("Panoramica", menu = menu, actions = {
            RoundIconButton(Icons.Default.Add, "Aggiungi", onAdd, Modifier.testTag("fab_add"), primary = true)
            RoundIconButton(Icons.Default.Notifications, if (alertCount > 0) "Avvisi: $alertCount da vedere" else "Avvisi", onOpenAlerts, badge = alertCount > 0)
        })
        if (!st.data.loaded || m == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        if (st.data.transactions.isEmpty()) {
            EmptyState(Icons.Default.AccountBalanceWallet, "Nessun movimento", "Importa un estratto conto (Excel o CSV), scansiona uno scontrino oppure ripristina un backup della versione web. I dati restano solo su questo telefono.") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Importa estratto conto") }
                    OutlinedButton(onClick = onScan) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("Scansiona scontrino") }
                    TextButton(onClick = onRestore) { Text("Ho già un backup: importa") }
                }
            }
            return@Column
        }
        val s = m.stats
        val fin = SpeseTheme.finance
        val saved = s.net.signum() >= 0
        val points = remember(st.buckets) { st.buckets.map { GapPoint(it.label, it.income, it.expense, it.title) } }
        val period = st.filter.period
        val shownPeriod: Period = when (period) { Period.All, Period.YearToDate, Period.Last3Months, Period.CurrentMonth, Period.CurrentWeek -> period; else -> Period.All }
        val catOpt = layout.firstOrNull { w -> w.widgetId == "categories" }?.options?.get("kind")
        val catKind = rememberSaveable(catOpt) { mutableStateOf(if (catOpt == "ENTRATA") TxKind.ENTRATA else TxKind.SPESA) }
        SearchFilterBar(onSearch, { filters = true }, st.filter.activeCount > 0)
        val cb = HomeCallbacks(onOpenAlerts, onOpenCategory, onOpenNetWorth, onOpenCategories, onOpenTransfers, onOpenTrends, onOpenCapture, onOpenSubscriptions, onOpenAdvice, onOpenTransfersReview, onOpenUnassigned, onOpenHealth, onOpenReview)
        val ctx = HomeCtx(st, m, vm, fin, health, safe, advice, balances, subs, transferCount, pendingAmounts, pendingNotifs, alertCount, points, shownPeriod, catKind, cb)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            layout.forEach { inst -> if (inst.enabled) homeWidget(inst, ctx) }
            item(key = "customize") { TextButton(onClick = onCustomize, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("customize_home")) { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Personalizza la Home") } }
        }
    }
    if (filters) FilterSheet(st.filter, st.data, filterVm::update, filterVm::reset) { filters = false }
}
