package it.spese.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.DataMenu
import it.spese.app.common.FilterHeader
import it.spese.app.common.TabTopBar
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.*
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.finance.*
import java.math.BigDecimal

@Composable
fun DashboardScreen(
    menu: DataMenu, onOpenAlerts: () -> Unit, onOpenCategory: (Long?) -> Unit, onImport: () -> Unit, onScan: () -> Unit, onRestore: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(), vm: DashboardViewModel = hiltViewModel(),
) = Scaffold(topBar = { TabTopBar("Panoramica", menu) }, contentWindowInsets = WindowInsets(0)) { pad ->
    val st by vm.state.collectAsStateWithLifecycle()
    val m = st.model
    Column(Modifier.fillMaxSize().padding(pad)) {
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
        FilterHeader(Modifier.padding(vertical = 4.dp))
        val s = m.stats
        val months = remember(s) { StatsEngine.allMonths(s.byMonth) }
        val labels = remember(months) { months.map { Dates.monthLabel(it) } }
        val data = st.data
        fun catColor(id: Long?) = data.categoriesById[id]?.color?.toColor() ?: Color(0xFF888888)
        fun catName(id: Long?) = data.categoryName(id)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("Saldo (entrate − spese)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(s.net, signed = true, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Entrate", style = MaterialTheme.typography.labelMedium); MoneyText(s.income, decimals = false) }
                        Column { Text("Spese", style = MaterialTheme.typography.labelMedium); MoneyText(s.expense.negate(), decimals = false) }
                        Column { Text("Risparmio", style = MaterialTheme.typography.labelMedium); Text(s.savingsRatePercent?.let { "$it%" } ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard("Spesa media / mese", Modifier.weight(1f)) { MoneyText(m.monthlyAvgExpense, decimals = false, colored = false, style = MaterialTheme.typography.titleLarge) }
                    KpiCard("Trasferimenti (esclusi)", Modifier.weight(1f), hint = "Giroconti tra conti") { MoneyText(s.transfers, decimals = false, muted = true, style = MaterialTheme.typography.titleLarge) }
                }
            }
            if (m.alerts.any { it.level >= 1 }) item {
                SectionCard(title = "Avvisi", action = { TextButton(onClick = onOpenAlerts) { Text("Vedi tutti") } }) {
                    m.alerts.filter { it.level >= 1 }.take(4).forEach { a -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.padding(top = 6.dp)) { SeverityDot(a.level) }; Text(a.message, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            if (data.accounts.count { a -> m.perAccount.any { it.account.id == a.id && (it.income.signum() != 0 || it.expense.signum() != 0 || it.transfers.signum() != 0) } } > 1 && st.filter.accountId == null) item {
                SectionCard(title = "Per conto") {
                    m.perAccount.filter { it.income.signum() != 0 || it.expense.signum() != 0 || it.transfers.signum() != 0 }.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.account.name, fontWeight = FontWeight.Medium)
                                Text("Entrate ${Money.eur0(r.income)} · Spese ${Money.eur0(r.expense)} · Trasf. ${Money.eur0(r.transfers)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(r.balance, decimals = false, signed = true)
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Spese per categoria") {
                    DonutChart(m.categories.take(12).map { Slice(catName(it.category?.id), it.total, catColor(it.category?.id)) } +
                        (if (m.categories.size > 12) listOf(Slice("Altre", m.categories.drop(12).fold(BigDecimal.ZERO) { a, c -> a + c.total }, Color(0xFFAAAABB))) else emptyList()), Modifier.fillMaxWidth(), centerTitle = "Spese")
                }
            }
            item {
                SectionCard(title = "Entrate e spese per mese") {
                    BarLineChart(labels, listOf(
                        ChartSeries("Entrate", SpeseTheme.finance.positive, months.map { s.byMonth[it]?.income ?: BigDecimal.ZERO }),
                        ChartSeries("Spese", SpeseTheme.finance.negative, months.map { s.byMonth[it]?.expense ?: BigDecimal.ZERO }),
                    ), lines = listOf(ChartSeries("Saldo", MaterialTheme.colorScheme.primary, months.map { s.byMonth[it]?.net ?: BigDecimal.ZERO })))
                }
            }
            item {
                SectionCard(title = "Spese per categoria e mese") {
                    val top = m.categories.take(9)
                    val topIds = top.map { it.category?.id }
                    val series = top.map { c -> ChartSeries(catName(c.category?.id), catColor(c.category?.id), months.map { s.byMonth[it]?.byCategory?.get(c.category?.id) ?: BigDecimal.ZERO }) } +
                        listOf(ChartSeries("Altre", Color(0xFFAAAABB), months.map { mo -> s.byMonth[mo]?.byCategory?.filterKeys { it !in topIds }?.values?.fold(BigDecimal.ZERO) { a, b -> a + b } ?: BigDecimal.ZERO }))
                    BarLineChart(labels, series, stacked = true, height = 260.dp)
                }
            }
            item {
                SectionCard(title = "Andamento del saldo cumulato") {
                    LineChart(labels, listOf(ChartSeries("Saldo cumulato", MaterialTheme.colorScheme.primary, m.cumulative.map { it.second })))
                }
            }
            item {
                SectionCard(title = "Principali esercenti") {
                    HorizontalBars(m.merchants.map { (n, st2) -> BarItem(n, st2.total, MaterialTheme.colorScheme.primary, "${st2.count} mov.") })
                }
            }
            item {
                SectionCard(title = "Dettaglio categorie") {
                    m.categories.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Row(Modifier.weight(1f).let { it }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ColorDot(catColor(r.category?.id)); Text(catName(r.category?.id), maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Money.eur(r.total), fontWeight = FontWeight.Medium)
                                Text("${r.percentOfExpense.toPlainString().replace('.', ',')}% · ${Money.eur0(r.monthlyAvg)}/mese · ${r.count} mov.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { onOpenCategory(r.category?.id) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) { Text("Vedi movimenti", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item {
                SectionCard(title = "Le 10 spese singole più alte") {
                    m.biggestExpenses.forEach { x ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(x.merchant, maxLines = 1); Text("${Dates.fmt(x.bookingDate)} · ${catName(x.categoryId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(x.amount)
                        }
                    }
                }
            }
        }
    }
}
