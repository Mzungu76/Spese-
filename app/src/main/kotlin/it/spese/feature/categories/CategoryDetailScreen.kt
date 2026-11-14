package it.spese.feature.categories

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.BarLineChart
import it.spese.core.designsystem.chart.ChartSeries
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.finance.CategoryDetail
import it.spese.domain.finance.MerchantGroup
import java.math.BigDecimal

/** Dettaglio di una categoria: quanto pesa, come cambia nel tempo, da quali esercenti e voci è fatta. */
@Composable
fun CategoryDetailScreen(onBack: () -> Unit, onOpenTransaction: (Long) -> Unit, onOpenMovements: () -> Unit, onOpenCategory: (Long) -> Unit, onOpenPlan: () -> Unit = {}, vm: CategoryDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val d = st.detail
    Column(Modifier.fillMaxSize()) {
        BackHeader(d?.category?.name ?: "Categoria", onBack)
        if (!st.data.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        if (d == null) { EmptyState(Icons.Default.SearchOff, "Categoria non trovata", "Potrebbe essere stata eliminata."); return@Column }
        val color = d.category.color.toColor()
        val spese = d.category.kind != TxKind.ENTRATA
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PeriodPills(st.filter.period, vm::setPeriod, Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            item(key = "hero") {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategoryAvatar(d.category, size = 64)
                    Column {
                        Eyebrow(if (spese) "Speso nel periodo" else "Incassato nel periodo")
                        MoneyText(d.total, colored = false, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    d.changePercent?.let { pct ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeltaBadge(pct, upIsGood = !spese)
                            Text(Money.eurSigned(d.delta!!).replace(",00", "") + " " + (d.previousLabel ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } ?: if (d.previousTotal != null && d.previousTotal!!.signum() == 0 && d.total.signum() > 0) Text("Nuova rispetto al periodo precedente", style = MaterialTheme.typography.bodyMedium, color = SpeseTheme.finance.warning) else Unit
                    Text("${d.count} ${if (d.count == 1) "movimento" else "movimenti"} · ${d.share.toPlainString().replace('.', ',')}% ${if (spese) "delle spese" else "delle entrate"} · media ${Money.eur0(d.monthlyAvg)}/mese",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (d.months.size >= 2) item(key = "chart") {
                SectionCard(Modifier.padding(horizontal = 16.dp), title = "Mese per mese") {
                    BarLineChart(d.months.map { Dates.monthLabel(it.first) }, listOf(ChartSeries(d.category.name, color, d.months.map { it.second })),
                        lines = listOf(ChartSeries("Media", MaterialTheme.colorScheme.onSurfaceVariant, d.months.map { d.monthlyAvg })), height = 200.dp, interactive = false)
                }
            }
            if (d.subcategories.isNotEmpty()) item(key = "subs") {
                SectionCard(Modifier.padding(horizontal = 16.dp), title = "Sottocategorie") {
                    d.subcategories.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenCategory(s.category.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryAvatar(s.category, size = 40)
                            Column(Modifier.weight(1f)) {
                                Text(s.category.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${s.percent.toPlainString().replace('.', ',')}% · ${s.count} mov.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(s.total, decimals = false, colored = false)
                        }
                    }
                }
            }
            if (d.drivers.isNotEmpty()) item(key = "drivers") {
                SectionCard(Modifier.padding(horizontal = 16.dp), title = "Cosa è cambiato", ) {
                    Text(d.previousLabel?.replaceFirstChar { it.uppercase() } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.drivers.forEach { g ->
                        val up = g.delta.signum() > 0
                        val c = if (up == spese) SpeseTheme.finance.negative else SpeseTheme.finance.positive
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(if (g.count == 0) "non compare più (prima ${Money.eur0(g.previousTotal)})" else if (g.previousTotal.signum() == 0) "nuova voce" else "da ${Money.eur0(g.previousTotal)} a ${Money.eur0(g.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Money.eurSigned(g.delta).replace(",00", ""), color = c, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (d.category.kind == TxKind.SPESA) {
                item(key = "budget") { BudgetCard(d, vm::setBudget, Modifier.padding(horizontal = 16.dp)) }
                item(key = "cut") { CutSimulationCard(d, vm::saveCutToPlan, onOpenPlan, Modifier.padding(horizontal = 16.dp)) }
                item(key = "nature") { NatureChoiceCard(d, vm::setNature, Modifier.padding(horizontal = 16.dp)) }
            }
            item(key = "merchants-head") { SectionHeader("Voci di spesa", Modifier.padding(horizontal = 24.dp), action = "Tutti i movimenti", onAction = onOpenMovements) }
            if (d.merchants.isEmpty()) item(key = "merchants-empty") { Text("Nessun movimento nel periodo.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(d.merchants, key = { "m-${it.merchant}" }) { g -> MerchantRow(g, spese, onOpenTransaction) }
        }
    }
}

@Composable
private fun MerchantRow(g: MerchantGroup, spese: Boolean, onOpenTransaction: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(g.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${g.count} ${if (g.count == 1) "movimento" else "movimenti"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyText(g.total, colored = false, decimals = false)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Comprimi" else "Espandi", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) g.transactions.take(8).forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { onOpenTransaction(t.id) }.padding(start = 40.dp, end = 24.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Dates.fmt(t.bookingDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.description, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                MoneyText(if (spese) t.amount.negate() else t.amount, colored = false, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (open && g.transactions.size > 8) Text("… e altri ${g.transactions.size - 8}: vedi tutti in Movimenti", Modifier.padding(start = 40.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
