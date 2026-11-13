package it.spese.feature.analytics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.FilterHeader
import it.spese.app.common.SubTopBar
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.*
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.TxKind
import it.spese.domain.finance.StatsEngine
import it.spese.domain.simulation.Trends
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

@Composable
fun AnalyticsScreen(onBack: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { SubTopBar("Analisi e grafici", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val m = st.model
            if (m == null || st.data.transactions.isEmpty()) { EmptyState(Icons.Default.Insights, "Nessun dato da analizzare", "Importa dei movimenti per vedere grafici e analisi."); return@Column }
            FilterHeader(Modifier.padding(bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisType.entries.forEach { t -> FilterChip(st.type == t, { vm.setType(t) }, { Text(t.label) }) }
            }
            val catsWithSpend = m.categories
            if (st.type in listOf(AnalysisType.DONUT, AnalysisType.LINES, AnalysisType.CATEGORIES, AnalysisType.STACKED, AnalysisType.TREND)) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.selected.isNotEmpty()) AssistChip(onClick = vm::clear, label = { Text("Tutte") })
                    catsWithSpend.forEach { r -> val id = r.category?.id
                        FilterChip(id in st.selected, { vm.toggle(id) }, { Text(r.category?.name ?: "Senza categoria") }, leadingIcon = { ColorDot(r.category?.color?.toColor() ?: Color.Gray) }) }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Body(st) } }
        }
    }
}

@Composable
private fun Body(st: AnalyticsState) {
    val m = st.model!!; val s = m.stats; val d = st.data
    val months = remember(s) { StatsEngine.allMonths(s.byMonth) }
    val labels = remember(months) { months.map { Dates.monthLabel(it) } }
    fun col(id: Long?) = d.categoriesById[id]?.color?.toColor() ?: Color(0xFF888888)
    fun name(id: Long?) = d.categoryName(id)
    val chosen = if (st.selected.isEmpty()) m.categories.take(5).map { it.category?.id } else st.selected.toList()
    fun monthly(id: Long?) = months.map { s.byMonth[it]?.byCategory?.get(id) ?: BigDecimal.ZERO }
    when (st.type) {
        AnalysisType.DONUT -> {
            val rows = if (st.selected.isEmpty()) m.categories else m.categories.filter { it.category?.id in st.selected }
            SectionCard(title = "Distribuzione per categoria") {
                DonutChart(rows.map { Slice(name(it.category?.id), it.total, col(it.category?.id)) }, Modifier.fillMaxWidth(), centerTitle = "Spese")
                HorizontalBars(rows.map { BarItem(name(it.category?.id), it.total, col(it.category?.id), "${it.percentOfExpense.toPlainString().replace('.', ',')}%") })
            }
        }
        AnalysisType.BARS -> SectionCard(title = "Entrate e spese per mese") {
            BarLineChart(labels, listOf(ChartSeries("Entrate", SpeseTheme.finance.positive, months.map { s.byMonth[it]?.income ?: BigDecimal.ZERO }), ChartSeries("Spese", SpeseTheme.finance.negative, months.map { s.byMonth[it]?.expense ?: BigDecimal.ZERO })),
                lines = listOf(ChartSeries("Saldo", MaterialTheme.colorScheme.primary, months.map { s.byMonth[it]?.net ?: BigDecimal.ZERO })), height = 280.dp)
            Text("Pizzica per ingrandire, trascina per scorrere, tocca per i valori.", style = MaterialTheme.typography.bodySmall)
        }
        AnalysisType.STACKED -> SectionCard(title = "Spese per categoria e mese (impilate)") {
            val ids = if (st.selected.isEmpty()) m.categories.take(9).map { it.category?.id } else st.selected.toList()
            val series = ids.map { ChartSeries(name(it), col(it), monthly(it)) } +
                if (st.selected.isEmpty()) listOf(ChartSeries("Altre", Color(0xFFAAAABB), months.map { mo -> s.byMonth[mo]?.byCategory?.filterKeys { it !in ids }?.values?.fold(BigDecimal.ZERO) { a, b -> a + b } ?: BigDecimal.ZERO })) else emptyList()
            BarLineChart(labels, series, stacked = true, height = 300.dp)
        }
        AnalysisType.LINES -> SectionCard(title = "Andamento per categoria") { LineChart(labels, chosen.map { ChartSeries(name(it), col(it), monthly(it)) }, height = 280.dp) }
        AnalysisType.CUMULATIVE -> SectionCard(title = "Andamento cumulativo") {
            var run = BigDecimal.ZERO
            val cumExp = months.map { run += (s.byMonth[it]?.expense ?: BigDecimal.ZERO); run }
            LineChart(labels, listOf(ChartSeries("Saldo cumulato", MaterialTheme.colorScheme.primary, m.cumulative.map { it.second }), ChartSeries("Spese cumulate", SpeseTheme.finance.negative, cumExp)), height = 280.dp)
        }
        AnalysisType.PERIODS -> {
            val c = st.compare
            if (c == null) Text("Dati insufficienti per il confronto.") else SectionCard(title = "Periodo corrente e precedente") {
                Text("Corrente: ${c.currentLabel}", style = MaterialTheme.typography.bodySmall); Text("Precedente: ${c.previousLabel}", style = MaterialTheme.typography.bodySmall)
                listOf("Entrate" to (c.previous.income to c.current.income), "Spese" to (c.previous.expense to c.current.expense), "Saldo" to (c.previous.net to c.current.net)).forEach { (l, p) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(l, fontWeight = FontWeight.Medium); Text("${Money.eur0(p.first)} → ${Money.eur0(p.second)}" + (Trends.changePercent(p.first, p.second)?.let { "  (${if (it.signum() > 0) "+" else ""}${it.toPlainString().replace('.', ',')}%)" } ?: ""))
                    }
                }
                val deltas = Trends.categoryVariation(c.previous.byCategory, c.current.byCategory).take(12)
                Text("Variazioni di spesa per categoria", style = MaterialTheme.typography.titleSmall)
                deltas.forEach { x ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name(x.categoryId), Modifier.weight(1f), maxLines = 1)
                        Text("${Money.eur0(x.before)} → ${Money.eur0(x.after)}", style = MaterialTheme.typography.bodySmall)
                        Text("  " + (if (x.delta.signum() > 0) "+" else "") + Money.eur0(x.delta), color = if (x.delta.signum() > 0) SpeseTheme.finance.negative else SpeseTheme.finance.positive, fontWeight = FontWeight.Medium)
                    }
                }
                BarLineChart(deltas.take(8).map { name(it.categoryId).take(10) }, listOf(ChartSeries("Precedente", MaterialTheme.colorScheme.outline, deltas.take(8).map { it.before }), ChartSeries("Corrente", MaterialTheme.colorScheme.primary, deltas.take(8).map { it.after })), height = 240.dp)
            }
        }
        AnalysisType.CATEGORIES -> SectionCard(title = "Confronto tra categorie") {
            BarLineChart(labels, chosen.map { ChartSeries(name(it), col(it), monthly(it)) }, height = 260.dp)
            chosen.forEach { id ->
                val total = s.byCategory[id] ?: BigDecimal.ZERO
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name(id)); Text("${Money.eur0(total)} · ${Money.eur0(total.divide(m.months, 2, RoundingMode.HALF_UP))}/mese") }
            }
        }
        AnalysisType.TREND -> {
            val series = if (st.selected.isEmpty()) months.map { s.byMonth[it]?.expense ?: BigDecimal.ZERO } else months.map { mo -> st.selected.fold(BigDecimal.ZERO) { a, id -> a + (s.byMonth[mo]?.byCategory?.get(id) ?: BigDecimal.ZERO) } }
            val lin = Trends.linear(series)
            SectionCard(title = "Tendenza della spesa mensile") {
                if (series.size < 3) Text("Servono almeno 3 mesi di dati.") else {
                    LineChart(labels, listOf(ChartSeries("Spesa", MaterialTheme.colorScheme.primary, series), ChartSeries("Media mobile (3 mesi)", SpeseTheme.finance.warning, Trends.movingAverage(series, 3)),
                        ChartSeries("Tendenza", SpeseTheme.finance.negative, series.indices.map { lin?.at(it) ?: BigDecimal.ZERO })), height = 280.dp)
                    lin?.let { Text("Pendenza: ${if (it.slope.signum() >= 0) "+" else ""}${Money.eur0(it.slope)} al mese " + if (it.slope.signum() > 0) "(spesa in aumento)" else "(spesa in calo)", fontWeight = FontWeight.Medium) }
                    Trends.changePercent(series.first(), series.last())?.let { Text("Variazione primo → ultimo mese: ${it.toPlainString().replace('.', ',')}%") }
                }
            }
        }
        AnalysisType.DISTRIBUTION -> {
            val exp = m.filtered.filter { it.kind == TxKind.SPESA }.map { it.amount.negate() }
            val edges = listOf(0, 10, 25, 50, 100, 250, 500, Int.MAX_VALUE)
            val buckets = edges.zipWithNext().map { (a, b) -> exp.filter { it >= BigDecimal(a) && it < (if (b == Int.MAX_VALUE) BigDecimal("1E9") else BigDecimal(b)) } }
            SectionCard(title = "Distribuzione delle spese per importo") {
                BarLineChart(edges.zipWithNext().map { (a, b) -> if (b == Int.MAX_VALUE) "$a+" else "$a-$b" }, listOf(ChartSeries("N. spese", MaterialTheme.colorScheme.primary, buckets.map { BigDecimal(it.size) })), height = 240.dp)
                edges.zipWithNext().forEachIndexed { i, (a, b) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (b == Int.MAX_VALUE) "oltre $a €" else "$a – $b €"); Text("${buckets[i].size} spese · ${Money.eur0(buckets[i].fold(BigDecimal.ZERO) { x, y -> x + y })}") } }
                if (exp.isNotEmpty()) { val sorted = exp.sorted(); Text("Spesa mediana: ${Money.eur(sorted[sorted.size / 2])} · media: ${Money.eur(exp.fold(BigDecimal.ZERO) { a, b -> a + b }.divide(BigDecimal(exp.size), 2, RoundingMode.HALF_UP))}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        AnalysisType.FORECAST -> {
            val hist = months.takeLast(12)
            if (hist.size < 3) { Text("Servono almeno 3 mesi di dati per una previsione."); return }
            val inc = hist.map { s.byMonth[it]?.income ?: BigDecimal.ZERO }; val exp = hist.map { s.byMonth[it]?.expense ?: BigDecimal.ZERO }
            val ahead = 6; val fInc = Trends.forecast(inc, ahead); val fExp = Trends.forecast(exp, ahead)
            val future = (1..ahead).map { hist.last().plusMonths(it.toLong()) }
            val all = (hist + future).map { Dates.monthLabel(it) }
            var bal = m.totalBalance
            val fBal = (0 until ahead).map { bal += fInc[it] - fExp[it]; bal }
            SectionCard(title = "Previsione a 6 mesi (tendenza lineare)") {
                LineChart(all, listOf(
                    ChartSeries("Entrate (storico)", SpeseTheme.finance.positive, inc), ChartSeries("Spese (storico)", SpeseTheme.finance.negative, exp),
                    ChartSeries("Entrate (previsione)", SpeseTheme.finance.positive.copy(alpha = 0.5f), listOf(inc.last()) + fInc, hist.size - 1),
                    ChartSeries("Spese (previsione)", SpeseTheme.finance.negative.copy(alpha = 0.5f), listOf(exp.last()) + fExp, hist.size - 1)), height = 280.dp)
                Text("Saldo previsto: ${future.mapIndexed { i, mo -> "${Dates.monthLabel(mo)} ${Money.eur0(fBal[i])}" }.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                Text("Stima statistica semplice: non sostituisce il simulatore del Piano.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
