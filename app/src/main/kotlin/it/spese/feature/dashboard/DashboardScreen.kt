package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.DataMenu
import it.spese.app.common.FilterSheet
import it.spese.app.common.FilterViewModel
import it.spese.app.common.LargeHeader
import it.spese.app.common.RoundIconButton
import it.spese.core.common.Money
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
    contentPadding: PaddingValues = PaddingValues(), vm: DashboardViewModel = hiltViewModel(), filterVm: FilterViewModel = hiltViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val m = st.model
    var filters by remember { mutableStateOf(false) }
    val alertCount = m?.alerts?.count { it.level >= 1 } ?: 0
    Column(Modifier.fillMaxSize()) {
        LargeHeader("Panoramica", menu = menu, actions = {
            RoundIconButton(Icons.Default.Notifications, if (alertCount > 0) "Avvisi: $alertCount da vedere" else "Avvisi", onOpenAlerts, badge = alertCount > 0)
            RoundIconButton(Icons.Default.Tune, "Filtri", { filters = true }, badge = st.filter.activeCount > 0)
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
        val shownPeriod: Period = when (period) { Period.All, Period.YearToDate, Period.Last3Months, Period.CurrentMonth -> period; else -> Period.All }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item(key = "hero") {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
                    Eyebrow(if (saved) "Hai messo da parte" else "Hai speso in più di quanto incassi")
                    MoneyText(s.net.abs(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, colored = false, decimals = true)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.savingsRatePercent?.let { r ->
                            Text(if (r >= 0) "$r%" else "$r%", Modifier.background(if (r >= 0) fin.positive.copy(alpha = 0.16f) else fin.negative.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = if (r >= 0) fin.positive else fin.negative, fontWeight = FontWeight.SemiBold)
                            Text("di quello che incassi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(key = "legend") {
                Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    LegendItem("Entrate", Money.eur0(s.income), fin.positive)
                    LegendItem("Spese", Money.eur0(s.expense), fin.negative)
                }
            }
            item(key = "chart") { GapAreaChart(points, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), height = 300.dp) }
            item(key = "period") {
                PillSegmented(listOf<Pair<Period, String>>(Period.CurrentMonth to "Mese", Period.Last3Months to "3 mesi", Period.YearToDate to "Anno", Period.All to "Tutto"), shownPeriod, vm::setPeriod, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            item(key = "alerts") {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpenAlerts).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val dot = when { m.alerts.any { it.level >= 2 } -> MaterialTheme.colorScheme.error; alertCount > 0 -> fin.warning; else -> fin.positive }
                    Box(Modifier.size(11.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
                    Text(if (alertCount > 0) (if (alertCount == 1) "1 cosa da guardare" else "$alertCount cose da guardare") else "Tutto sotto controllo", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (filters) FilterSheet(st.filter, st.data, filterVm::update, filterVm::reset) { filters = false }
}

@Composable
private fun LegendItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
