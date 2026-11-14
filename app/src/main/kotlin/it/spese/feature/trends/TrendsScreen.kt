package it.spese.feature.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.TxKind
import it.spese.domain.finance.*
import it.spese.feature.categories.PeriodPills
import java.math.BigDecimal

/** Tendenze di ogni categoria rispetto allo storico e proiezione a 1, 3 e 6 mesi se la tendenza continua, con l'impatto sul bilancio. */
@Composable
fun TrendsScreen(onBack: () -> Unit, onOpenCategory: (Long) -> Unit, vm: TrendsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val r = st.report
    Column(Modifier.fillMaxSize()) {
        BackHeader("Tendenze", onBack)
        if (!st.data.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        if (r == null) { EmptyState(Icons.Default.Timeline, "Servono dei movimenti", "Importa un estratto conto: le tendenze si calcolano sui mesi completi."); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PeriodPills(st.filter.period, vm::setPeriod, Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            item {
                Text("Il periodo scelto (${r.periodLabel}) è confrontato con la media storica precedente. Le proiezioni prolungano la tendenza degli ultimi mesi completi: sono stime, non previsioni.",
                    Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { BalanceCard(r, Modifier.padding(horizontal = 16.dp)) }
            item {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ordina per", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TrendSort.entries.forEach { s -> FilterChip(st.sort == s, { vm.setSort(s) }, { Text(s.label) }) }
                }
            }
            r.income?.let { inc -> item(key = "income") { TrendCard(inc, "Entrate", null, Modifier.padding(horizontal = 16.dp)) } }
            items(r.categories, key = { "t-${it.category?.id}" }) { t -> TrendCard(t, t.category?.name ?: "", t.category?.id?.let { id -> { onOpenCategory(id) } }, Modifier.padding(horizontal = 16.dp)) }
            if (r.categories.isEmpty()) item { Text("Nessuna spesa nei mesi completi.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BalanceCard(r: TrendReport, modifier: Modifier = Modifier) {
    val fin = SpeseTheme.finance
    SectionCard(modifier, title = "Bilancio mensile atteso") {
        @Composable
        fun cell(text: String, weight: Float = 1f, bold: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface, rs: RowScope) = with(rs) {
            Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null, color = color, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.1f)); listOf("Oggi", "1 mese", "3 mesi", "6 mesi").forEach { h -> Text(h, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1) }
        }
        fun line(label: String, now: BigDecimal, f: (BalanceProjection) -> BigDecimal, good: ((BigDecimal) -> Boolean)? = null) = Triple(label, now, f)
        listOf(
            line("Entrate", r.currentIncome, { it.income }), line("Spese", r.currentExpense, { it.expense }), line("Risparmio", r.currentNet, { it.net }),
        ).forEach { (label, now, f) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (label == "Risparmio") FontWeight.SemiBold else null)
                cell(Money.eur0(now), rs = this, bold = label == "Risparmio")
                r.balance.forEach { b ->
                    val v = f(b)
                    val c = if (label == "Risparmio") (if (v >= now) fin.positive else fin.negative) else MaterialTheme.colorScheme.onSurface
                    cell(Money.eur0(v), rs = this, bold = label == "Risparmio", color = c)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            Text("Effetto cumulato", Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium)
            cell("–", rs = this)
            r.balance.forEach { b -> cell(Money.eurSigned(b.cumulativeImpact).replace(",00", ""), rs = this, bold = true, color = if (b.cumulativeImpact.signum() >= 0) fin.positive else fin.negative) }
        }
        Text("Effetto cumulato: quanto più (o meno) resta da parte, nel complesso dei mesi, rispetto a restare ai livelli di oggi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendCard(t: CategoryTrend, title: String, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val fin = SpeseTheme.finance
    val income = t.kind == TxKind.ENTRATA
    val dirText = when (t.direction) { TrendDirection.UP -> "In aumento"; TrendDirection.DOWN -> "In calo"; TrendDirection.STABLE -> "Stabile"; TrendDirection.UNKNOWN -> "Pochi dati" }
    val dirColor = when (t.direction) {
        TrendDirection.UP -> if (income) fin.positive else fin.negative
        TrendDirection.DOWN -> if (income) fin.negative else fin.positive
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color = t.category?.color?.toColor() ?: fin.positive
    Card(modifier.fillMaxWidth().let { if (onOpen != null) it.clickable(onClick = onOpen) else it }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (t.category != null) CategoryAvatar(t.category, size = 40) else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Payments, null, tint = fin.positive) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(dirText + if (t.direction == TrendDirection.UP || t.direction == TrendDirection.DOWN) " · ${Money.eurSigned(t.slope).replace(",00", "")} al mese" else "", style = MaterialTheme.typography.bodySmall, color = dirColor, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.eur0(t.periodAvg) + "/mese", style = MaterialTheme.typography.titleSmall)
                    t.changeVsHistory?.let { DeltaBadge(it, upIsGood = income) }
                }
            }
            Text(buildString {
                append("Periodo: ").append(Money.eur0(t.periodAvg)).append("/mese")
                t.historyAvg?.let { append(" · storico: ").append(Money.eur0(it)).append("/mese") } ?: append(" · nessuno storico precedente")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Sparkline(t.months.map { it.second }, color, t.level, Modifier.fillMaxWidth().height(40.dp))
            if (t.months.isNotEmpty()) Text("${Dates.monthLabel(t.months.first().first)} – ${Dates.monthLabel(t.months.last().first)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t.projections.forEach { p ->
                    val good = p.balanceImpact.signum() >= 0
                    Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${p.monthsAhead} ${if (p.monthsAhead == 1) "mese" else "mesi"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.eur0(p.monthly), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(Money.eurSigned(p.balanceImpact).replace(",00", ""), style = MaterialTheme.typography.labelSmall, color = if (p.balanceImpact.signum() == 0) MaterialTheme.colorScheme.onSurfaceVariant else if (good) fin.positive else fin.negative)
                    }
                }
            }
            Text("Importo mensile atteso e, sotto, effetto cumulato sul bilancio rispetto a restare al livello di oggi (${Money.eur0(t.level)}/mese).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.confidence == Confidence.LOW) Text("Stima poco affidabile: pochi mesi di dati o importi molto variabili.", style = MaterialTheme.typography.bodySmall, color = fin.warning)
        }
    }
}

/** Barrette dei mesi con la linea del livello attuale stimato. */
@Composable
private fun Sparkline(values: List<BigDecimal>, color: Color, level: BigDecimal, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: BigDecimal.ONE).max(level).toFloat().coerceAtLeast(1f)
        val gap = 4.dp.toPx(); val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / max * size.height).coerceAtLeast(2f)
            drawRoundRect(color.copy(alpha = 0.85f), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
        }
        val y = size.height - level.toFloat() / max * size.height
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}
