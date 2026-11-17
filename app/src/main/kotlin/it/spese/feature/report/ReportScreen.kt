package it.spese.feature.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.*
import it.spese.feature.dashboard.InsightRow
import java.math.BigDecimal

@Composable
fun ReportScreen(onBack: () -> Unit, onOpenSetup: () -> Unit, vm: ReportViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Il tuo mese", onBack)
        if (!st.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        val m = st.model
        if (m == null || st.report == null && m.insights.isEmpty()) {
            EmptyState(Icons.Default.Insights, "Ancora pochi dati", "Con qualche settimana di movimenti qui trovi il riepilogo del mese, il fondo di emergenza e i consigli.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("report_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.months.size > 1) item { ChipChoice(st.months.map { it to Dates.monthLabel(it) }, st.selected ?: st.months.last(), vm::select) }
            st.report?.let { r -> item { MonthCard(r) }; if (r.history.size >= 2) item { HistoryCard(r.history) } }
            m.surplusPlan?.let { sp -> item { FundCard(sp) } }
            if (m.accruals.items.isNotEmpty()) item { AnnualCard(m.accruals) }
            if (m.insights.isNotEmpty()) item { SectionCard(title = "Consigli per te") { m.insights.forEach { InsightRow(it) } } }
            m.lesson?.let { l -> item { SectionCard(title = "Da sapere") { Text(l.title, style = MaterialTheme.typography.titleSmall); Text(l.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            item { TextButton(onClick = onOpenSetup) { Text("Rivedi entrate, obiettivo e budget") } }
        }
    }
}

@Composable
private fun MonthCard(r: MonthlyReport) {
    val fin = SpeseTheme.finance
    SectionCard(title = Dates.monthLabelLong(r.month)) {
        Text(r.headline, style = MaterialTheme.typography.titleMedium, color = if (r.net.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { Eyebrow("Entrate"); Text(Money.eur0(r.income), color = fin.positive, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) { Eyebrow("Spese"); Text(Money.eur0(r.expense), color = fin.negative, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) { Eyebrow("Risparmio"); Text(r.savingsRate?.let { "$it%" } ?: "–", fontWeight = FontWeight.SemiBold) }
        }
        r.highlights.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (r.topCategories.isNotEmpty()) {
            HorizontalDivider()
            Text("Dove sono andati i soldi", style = MaterialTheme.typography.titleSmall)
            r.topCategories.forEach { (n, v) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(n, Modifier.weight(1f), maxLines = 1); Text(Money.eur0(v)) } }
        }
    }
}

/** Andamento del risparmio mese per mese: una barra per mese (verde se hai messo da parte, rossa se hai speso più di quanto incassato). */
@Composable
private fun HistoryCard(h: List<MonthPoint>) {
    val fin = SpeseTheme.finance
    val maxAbs = h.maxOf { it.net.abs() }.max(BigDecimal.ONE)
    SectionCard(title = "Quanto hai messo da parte") {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            h.forEach { p ->
                val frac = (p.net.abs().toFloat() / maxAbs.toFloat()).coerceIn(0.04f, 1f)
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(Money.eur0(p.net), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().fillMaxHeight(frac * 0.75f).clip(RoundedCornerShape(6.dp)).background(if (p.net.signum() >= 0) fin.positive else fin.negative))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            h.forEach { p -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(Dates.monthLabel(p.month).take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(p.savingsRate?.let { "$it%" } ?: "", style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

@Composable
private fun FundCard(sp: SurplusPlan) {
    SectionCard(title = "Fondo di emergenza") {
        UsageBar(sp.fundProgress, Modifier.fillMaxWidth())
        Text("La tua liquidità copre ${sp.monthsCovered.toPlainString().replace('.', ',')} mesi di spese essenziali (${Money.eur0(sp.essentialMonthly)} al mese). Obiettivo: 3–6 mesi, cioè ${Money.eur0(sp.fundMin)}–${Money.eur0(sp.fundMax)}.", style = MaterialTheme.typography.bodyMedium)
        sp.monthsToMinFund?.let { Text("Mettendo da parte ${Money.eur0(sp.surplus)} al mese arrivi ai 3 mesi in circa $it mesi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!sp.balanceReliable) Text("Il saldo è la sola somma dei movimenti importati: imposta il saldo iniziale dei conti (menu ⋮ → Conti) per un dato più preciso.", style = MaterialTheme.typography.bodySmall, color = SpeseTheme.finance.warning)
        if (sp.surplus.signum() > 0) {
            HorizontalDivider()
            Text("Ciò che è avanzato lo scorso mese (${Money.eur0(sp.surplus)}), in quest'ordine:", style = MaterialTheme.typography.titleSmall)
            sp.steps.forEach { s ->
                Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(s.label, fontWeight = FontWeight.Medium); Text(Money.eur0(s.amount), fontWeight = FontWeight.Medium) }
                    Text(s.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("È un percorso educativo, non una consulenza finanziaria. Se hai debiti con interessi alti, estinguerli viene prima.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnnualCard(a: AccrualPlan) {
    SectionCard(title = "Spese annuali da spalmare") {
        Text("Mettere da parte ${Money.eur0(a.monthlyTotal)} al mese evita che queste spese arrivino come una sorpresa. Sono già conteggiate nel \"quanto posso spendere oggi\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        a.items.forEach { i ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(i.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("${Money.eur0(i.amount)} il ${Dates.fmt(i.next)}" + (if (i.manual) "" else " · rilevata dai movimenti") + " · accantonati finora circa ${Money.eur0(i.shouldHaveSaved)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.eur0(i.monthly) + "/mese", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
