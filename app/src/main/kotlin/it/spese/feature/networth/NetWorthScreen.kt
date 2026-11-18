package it.spese.feature.networth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.ChartSeries
import it.spese.core.designsystem.chart.LineChart
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.networth.AccountBalance
import it.spese.domain.networth.FundStatus
import it.spese.domain.networth.NetWorthReport
import it.spese.domain.networth.NetWorthText
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Patrimonio: quanto hai, come è cambiato, se il fondo di emergenza è al sicuro e quanto potresti investire. */
@Composable
fun NetWorthScreen(onBack: (() -> Unit)? = null, menu: it.spese.app.common.DataMenu? = null, bottomPadding: PaddingValues = PaddingValues(), onCustomize: () -> Unit = {}, vm: NetWorthViewModel = hiltViewModel()) {
    val r by vm.report.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<AccountBalance?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) BackHeader("Patrimonio", onBack) else it.spese.app.common.LargeHeader("Patrimonio", menu = menu)
        val rep = r
        if (rep == null) {
            EmptyState(Icons.Default.AccountBalance, "Nessun conto", "Importa un estratto conto o crea un conto in Impostazioni › Conti per vedere il patrimonio.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp)) {
            layout.forEach { inst ->
                if (inst.enabled) when (inst.widgetId) {
                    "section" -> { item(key = inst.uid) { SectionHeader(inst.options["title"].orEmpty(), Modifier.padding(horizontal = 24.dp)) } }
                    "nw_total" -> { item(key = inst.uid) { Hero(rep) } }
                    "nw_trend" -> { if (rep.series.size >= 2) item(key = inst.uid) { Trend(rep) } }
                    "nw_fund" -> { item(key = inst.uid) { FundCard(rep, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                    "nw_margin" -> { item(key = inst.uid) { MarginCard(rep, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                    "nw_accounts" -> { items(rep.accounts, key = { b -> "${inst.uid}-${b.account.id}" }) { b -> AccountRow(b) { edit = b } } }
                    else -> Unit
                }
            }
            item(key = "customize") { TextButton(onClick = onCustomize, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("customize_networth")) { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Personalizza la pagina") } }
        }
    }
    edit?.let { b -> ValuationDialog(b, { edit = null }) { date, v -> vm.saveValuation(b.account.id, date, v) } }
}

@Composable
private fun Hero(r: NetWorthReport) {
    val fin = SpeseTheme.finance
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("Patrimonio totale")
        MoneyText(r.total, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, colored = false, decimals = false)
        r.change?.let { c ->
            Text((if (c.signum() >= 0) "+" else "−") + Money.eur0(c.abs()) + " da " + Dates.monthLabel(YearMonth.from(r.series.first().date)), style = MaterialTheme.typography.bodyMedium, color = if (c.signum() >= 0) fin.positive else fin.negative)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column { Eyebrow("Liquidità"); MoneyText(r.liquidity, decimals = false, colored = false) }
            Column { Eyebrow("Investimenti"); MoneyText(r.investments, decimals = false, colored = false) }
            if (r.managed.signum() != 0) Column { Eyebrow("Gestito da altri"); MoneyText(r.managed, decimals = false, colored = false) }
        }
    }
}

@Composable
private fun Trend(r: NetWorthReport) {
    val fin = SpeseTheme.finance
    val labels = r.series.map { Dates.monthLabel(YearMonth.from(it.date)).substringBefore(' ') }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        LineChart(labels, listOf(
            ChartSeries("Totale", MaterialTheme.colorScheme.primary, r.series.map { it.total }),
            ChartSeries("Liquidità", fin.positive, r.series.map { it.liquidity }),
        ), height = 220.dp)
    }
}

@Composable
private fun FundCard(r: NetWorthReport, modifier: Modifier) {
    val fin = SpeseTheme.finance
    val f = r.fund
    val color = when (f.status) { FundStatus.OK -> fin.positive; FundStatus.BUILDING -> fin.warning; FundStatus.LOW -> fin.negative; FundStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant }
    SectionCard(modifier, title = "Fondo di emergenza") {
        Text(f.months?.let { it.toPlainString().replace('.', ',') + " mesi" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        if (f.months != null) LinearProgressIndicator(progress = { (f.months!!.toFloat() / f.target.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(8.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(NetWorthText.fund(f, r.liquidity), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MarginCard(r: NetWorthReport, modifier: Modifier) {
    val m = r.margin
    SectionCard(modifier, title = "Margine da investire") {
        MoneyText(m.margin, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, colored = false, decimals = false)
        Text(NetWorthText.margin(m, r.fund), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.fund.months != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Line("Liquidità", m.liquidity)
            Line("Fondo di emergenza (${it.spese.domain.networth.NetWorthEngine.FUND_TARGET_MONTHS} mesi)", m.emergencyTarget.negate())
            Line("Già destinati agli obiettivi", m.earmarked.negate())
            Line("Scadenze dei prossimi 6 mesi", m.upcomingDues.negate())
        }
    }
}

@Composable
private fun Line(label: String, v: BigDecimal) {
    Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(Money.eur0(v), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun AccountRow(b: AccountBalance, onUpdate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(b.account.type.label + if (b.isInvestment) (b.valuationDate?.let { " · valore al " + Dates.fmt(it) } ?: " · valore dai movimenti") else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MoneyText(b.balance, decimals = false, colored = false)
        if (b.isInvestment) IconButton(onClick = onUpdate) { Icon(Icons.Default.Edit, "Aggiorna il valore di ${b.account.name}") }
    }
}

@Composable
private fun ValuationDialog(b: AccountBalance, onDismiss: () -> Unit, onSave: (LocalDate, BigDecimal) -> Unit) {
    var value by remember { mutableStateOf(b.balance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',')) }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Valore di «${b.account.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Inserisci quanto vale oggi (dall'estratto della banca o del broker): da questa data in poi si sommano i movimenti.", style = MaterialTheme.typography.bodySmall)
                AmountField(value, { value = it }, "Valore", Modifier.fillMaxWidth())
                DateField(date, { date = it }, "Alla data", Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = parseAmountOrNull(value) != null && date != null, onClick = { onSave(date ?: LocalDate.now(), parseAmountOrNull(value) ?: BigDecimal.ZERO); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
