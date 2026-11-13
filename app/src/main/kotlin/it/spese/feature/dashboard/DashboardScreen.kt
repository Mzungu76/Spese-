package it.spese.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onAdd: () -> Unit, onAnalytics: () -> Unit, onOpenClassify: () -> Unit, onOpenPlan: () -> Unit,
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
        val fin = SpeseTheme.finance
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1 · risultato del periodo: il numero più grande della schermata
            item(key = "hero", contentType = "card") {
                SectionCard {
                    Eyebrow("Risultato del periodo (entrate − spese)")
                    MoneyText(s.net, signed = true, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Eyebrow("Entrate"); MoneyText(s.income, decimals = false, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); DeltaBadge(m.incomeChange, upIsGood = true) }
                        Column(Modifier.weight(1f)) { Eyebrow("Spese"); MoneyText(s.expense.negate(), decimals = false, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); DeltaBadge(m.expenseChange, upIsGood = false) }
                        Column(Modifier.weight(0.7f)) { Eyebrow("Risparmio"); Text(s.savingsRatePercent?.let { "$it%" } ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    }
                    if (m.previous != null) Text("Variazioni rispetto al periodo precedente di pari durata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 2 · azioni rapide
            item(key = "quick", contentType = "quick") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Default.Add, "Aggiungi", onAdd, Modifier.weight(1f))
                    QuickAction(Icons.Default.DocumentScanner, "Scansiona", onScan, Modifier.weight(1f))
                    QuickAction(Icons.Default.UploadFile, "Importa", onImport, Modifier.weight(1f))
                    QuickAction(Icons.Default.Insights, "Analisi", onAnalytics, Modifier.weight(1f))
                }
            }
            // 3 · avvisi
            if (m.alerts.any { it.level >= 1 }) item(key = "alerts", contentType = "alerts") {
                val crit = m.alerts.any { it.level >= 2 }
                Card(Modifier.fillMaxWidth().clickable(onClick = onOpenAlerts), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = if (crit) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text("${m.alerts.count { it.level >= 1 }} avvisi", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null)
                        }
                        m.alerts.filter { it.level >= 1 }.take(2).forEach { a -> Text(a.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    }
                }
            }
            // 4 · numeri chiave
            item(key = "kpi1", contentType = "kpi") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard("Spesa media al mese", Modifier.weight(1f), hint = "${Money.eur0(m.monthlyAvgExpense.divide(java.math.BigDecimal(30), 2, java.math.RoundingMode.HALF_UP))} al giorno circa") { MoneyText(m.monthlyAvgExpense, decimals = false, colored = false, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    val p = m.monthPace
                    KpiCard(if (p != null) "Fine ${Dates.monthLabel(p.month)} a" else "Proiezione mese", Modifier.weight(1f), hint = p?.let { "speso ${Money.eur0(it.spent)} · giorno ${it.dayOfMonth}/${it.daysInMonth}" }) {
                        MoneyText((p?.projected ?: java.math.BigDecimal.ZERO), decimals = false, colored = false, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item(key = "kpi2", contentType = "kpi") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.weight(1f).clickable(onClick = onOpenPlan), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProgressRing(m.goalProgress, size = 64.dp, label = "${(m.goalProgress * 100).toInt().coerceAtMost(999)}%", color = if (m.goalProgress >= 1f) fin.positive else MaterialTheme.colorScheme.primary)
                            Column { Eyebrow("Obiettivo"); Text("${Money.eur0(m.savingsPerMonth)} / ${Money.eur0(m.goalMonthly)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold); Eyebrow("risparmio mensile") }
                        }
                    }
                    Card(Modifier.weight(1f).clickable(onClick = onOpenClassify), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Eyebrow("Da classificare"); Text("${m.unclassified}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (m.unclassified > 0) fin.warning else fin.positive)
                            Eyebrow(if (m.unclassified > 0) "movimenti senza regola" else "tutto in ordine")
                        }
                    }
                }
            }
            // 5 · prossime scadenze
            if (m.upcoming.isNotEmpty()) item(key = "upcoming", contentType = "card") {
                SectionCard(title = "Prossime scadenze", action = { Text("30 gg: ${Money.eur0(m.upcoming30)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                    m.upcoming.forEach { u ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(u.name, maxLines = 1, fontWeight = FontWeight.Medium)
                                Text("${Dates.fmt(u.date)} · " + when { u.daysLeft < 0 -> "${-u.daysLeft} g fa"; u.daysLeft == 0 -> "oggi"; else -> "tra ${u.daysLeft} g" } + if (u.manual) "" else " · rilevata",
                                    style = MaterialTheme.typography.bodySmall, color = if (u.daysLeft < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(u.amount, decimals = false, colored = false)
                        }
                    }
                }
            }
            // 6 · budget
            if (m.budgets.isNotEmpty()) item(key = "budgets", contentType = "card") {
                SectionCard(title = "Budget del mese") {
                    m.budgets.take(3).forEach { b ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(b.category.name, fontWeight = FontWeight.Medium, maxLines = 1); Text("${Money.eur0(b.spent)} / ${Money.eur0(b.budget)}", style = MaterialTheme.typography.bodyMedium, color = if (b.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            UsageBar(b.percent.toFloat() / 100f, over = b.exceeded)
                        }
                    }
                }
            }
            // 7 · dove vanno i soldi
            item(key = "donut", contentType = "chart") {
                SectionCard(title = "Dove vanno i soldi") {
                    DonutChart(m.categories.take(8).map { Slice(catName(it.category?.id), it.total, catColor(it.category?.id)) } +
                        (if (m.categories.size > 8) listOf(Slice("Altre", m.categories.drop(8).fold(java.math.BigDecimal.ZERO) { a, c -> a + c.total }, Color(0xFF6B7280))) else emptyList()), Modifier.fillMaxWidth(), centerTitle = "Spese")
                    m.categories.take(5).forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenCategory(r.category?.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryAvatar(catName(r.category?.id), catColor(r.category?.id), size = 32); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(catName(r.category?.id), maxLines = 1, fontWeight = FontWeight.Medium); Text("${r.percentOfExpense.toPlainString().replace('.', ',')}% · ${r.count} mov.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(r.total, decimals = false, colored = false)
                        }
                    }
                }
            }
            if (m.topChanges.isNotEmpty()) item(key = "changes", contentType = "card") {
                SectionCard(title = "Cosa è cambiato") {
                    m.topChanges.forEach { x ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(catName(x.categoryId), Modifier.weight(1f), maxLines = 1)
                            Text((if (x.delta.signum() > 0) "+" else "") + Money.eur0(x.delta), color = if (x.delta.signum() > 0) fin.negative else fin.positive, fontWeight = FontWeight.SemiBold)
                            DeltaBadge(x.percent, upIsGood = false, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            // 8 · grafici (statici: niente gesti in conflitto con lo scorrimento)
            item(key = "months", contentType = "chart") {
                SectionCard(title = "Mese per mese", action = { TextButton(onClick = onAnalytics) { Text("Analisi") } }) {
                    BarLineChart(labels, listOf(
                        ChartSeries("Entrate", fin.positive, months.map { s.byMonth[it]?.income ?: java.math.BigDecimal.ZERO }),
                        ChartSeries("Spese", fin.negative, months.map { s.byMonth[it]?.expense ?: java.math.BigDecimal.ZERO }),
                    ), lines = listOf(ChartSeries("Saldo", MaterialTheme.colorScheme.primary, months.map { s.byMonth[it]?.net ?: java.math.BigDecimal.ZERO })), interactive = false)
                }
            }
            item(key = "cum", contentType = "chart") {
                SectionCard(title = "Saldo cumulato") { LineChart(labels, listOf(ChartSeries("Saldo cumulato", MaterialTheme.colorScheme.primary, m.cumulative.map { it.second })), interactive = false, height = 180.dp) }
            }
            if (data.accounts.count { a -> m.perAccount.any { it.account.id == a.id && (it.income.signum() != 0 || it.expense.signum() != 0) } } > 1 && st.filter.accountId == null) item(key = "accounts", contentType = "card") {
                SectionCard(title = "Per conto") {
                    m.perAccount.filter { it.income.signum() != 0 || it.expense.signum() != 0 }.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(r.account.name, fontWeight = FontWeight.Medium); Text("Entrate ${Money.eur0(r.income)} · Spese ${Money.eur0(r.expense)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(r.balance, decimals = false, signed = true)
                        }
                    }
                }
            }
            item(key = "merch", contentType = "card") {
                SectionCard(title = "Principali esercenti") { HorizontalBars(m.merchants.take(6).map { (n, st2) -> BarItem(n, st2.total, MaterialTheme.colorScheme.primary, "${st2.count} mov.") }) }
            }
            item(key = "top10", contentType = "card") {
                SectionCard(title = "Le spese singole più alte") {
                    m.biggestExpenses.take(6).forEach { x ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(x.merchant, maxLines = 1, fontWeight = FontWeight.Medium); Text("${Dates.fmt(x.bookingDate)} · ${catName(x.categoryId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(x.amount)
                        }
                    }
                }
            }
        }
    }
}
