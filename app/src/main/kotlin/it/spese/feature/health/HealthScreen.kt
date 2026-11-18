package it.spese.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import it.spese.domain.health.*
import java.math.BigDecimal

@Composable
fun verdictColor(v: Verdict): Color {
    val fin = SpeseTheme.finance
    return when (v) { Verdict.HEALTHY -> fin.positive; Verdict.ATTENTION -> fin.warning; Verdict.FRAGILE -> fin.warning; Verdict.DIFFICULTY -> fin.negative }
}

@Composable
private fun levelColor(l: Level): Color {
    val fin = SpeseTheme.finance
    return when (l) { Level.GOOD -> fin.positive; Level.WARN -> fin.warning; Level.BAD -> fin.negative; Level.NA -> MaterialTheme.colorScheme.outline }
}

/**
 * Salute del bilancio: giudizio e punteggio da 0 a 100, indicatori con il loro perché, entrate e spese separate in fisse e variabili,
 * dove agire (con il livello che hai già mantenuto tu in ogni categoria) e quanto è affidabile il parere. Tutto dal tuo storico, sul telefono.
 */
@Composable
fun HealthScreen(onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val r by vm.report.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Salute del bilancio", onBack)
        val rep = r
        if (rep == null) {
            EmptyState(Icons.Default.MonitorHeart, "Parere non ancora disponibile", "Servono almeno un mese concluso con delle entrate e delle spese registrate.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Hero(rep) }
            if (history.size >= 2) item { Trend(history) }
            item { SectionHeader("Gli indicatori", Modifier.padding(horizontal = 24.dp)) }
            items(rep.indicators.size) { i -> IndicatorRow(rep.indicators[i]) }
            item { IncomeCard(rep, vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { ExpenseCard(rep, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { PlanCard(rep, vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (rep.watch.isNotEmpty()) item { WatchCard(rep, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (rep.notes.isNotEmpty()) item { NotesCard(rep, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
private fun Hero(r: HealthReport) {
    val color = verdictColor(r.verdict)
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(if (r.provisional) "Parere provvisorio" else "Il tuo bilancio, in chiaro")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(r.verdict.label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = color)
            Text("${r.score}/100", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        LinearProgressIndicator(progress = { r.score / 100f }, Modifier.fillMaxWidth().height(8.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(r.headline, style = MaterialTheme.typography.bodyLarge)
        Text("Calcolato sugli ultimi ${r.monthCount} ${if (r.monthCount == 1) "mese concluso" else "mesi conclusi"}, con mediane (non medie) e ragionando in prudenza sulle entrate variabili.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Trend(history: List<Pair<java.time.YearMonth, Int>>) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Come è cambiato il punteggio", style = MaterialTheme.typography.titleSmall)
        LineChart(history.map { Dates.monthLabel(it.first).substringBefore(' ') }, listOf(ChartSeries("Punteggio", MaterialTheme.colorScheme.primary, history.map { BigDecimal(it.second) })), height = 160.dp)
        val d = history.last().second - history.first().second
        Text(if (d > 2) "Negli ultimi ${history.size} mesi sei migliorato di $d punti." else if (d < -2) "Negli ultimi ${history.size} mesi sei calato di ${-d} punti." else "Il punteggio è stabile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IndicatorRow(i: Indicator) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(12.dp).background(levelColor(i.level), CircleShape))
        Column(Modifier.weight(1f)) {
            Row { Text(i.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(i.value, style = MaterialTheme.typography.titleSmall, color = levelColor(i.level)) }
            Text(i.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IncomeCard(r: HealthReport, vm: HealthViewModel, modifier: Modifier) {
    SectionCard(modifier, title = "Le tue entrate") {
        Text("Fisse ${Money.eur0(r.incomeFixed)} · variabili ${Money.eur0(r.incomeVariableTypical)} nei mesi tipici, ${Money.eur0(r.incomeVariablePrudent)} nei mesi magri. In prudenza conto su ${Money.eur0(r.prudentIncome)} al mese.", style = MaterialTheme.typography.bodyMedium)
        r.incomeSources.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text("${Money.eur0(s.monthlyMedian)} di solito · presente in ${s.monthsPresent} ${if (s.monthsPresent == 1) "mese" else "mesi"} su ${r.monthCount}" + if (s.overridden) " · scelta da te" else " · riconosciuta in automatico", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val id = s.categoryId
                if (id != null) {
                    AssistChip(onClick = { vm.setIncomeNature(id, if (s.nature == IncomeNature.FIXED) IncomeNature.VARIABLE else IncomeNature.FIXED) }, label = { Text(if (s.nature == IncomeNature.FIXED) "Fissa" else "Variabile") }, modifier = Modifier.testTag("income_nature"))
                    if (s.overridden) TextButton(onClick = { vm.setIncomeNature(id, null) }) { Text("Auto") }
                } else Text(if (s.nature == IncomeNature.FIXED) "Fissa" else "Variabile", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text("Tocca «Fissa/Variabile» per correggere: una entrata fissa è regolare nell'importo e nei mesi (stipendio, pensione, affitto incassato).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpenseCard(r: HealthReport, modifier: Modifier) {
    SectionCard(modifier, title = "Le tue spese, per natura") {
        listOf(ExpenseClass.FIXED to r.expenseFixed, ExpenseClass.NECESSARY to r.expenseNecessary, ExpenseClass.DISCRETIONARY to r.expenseDiscretionary, ExpenseClass.UNCLASSIFIED to r.expenseUnclassified).filter { it.second.signum() > 0 }.forEach { (cls, v) ->
            Row(Modifier.fillMaxWidth()) { Text(cls.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(Money.eur0(v) + " al mese", style = MaterialTheme.typography.titleSmall) }
            r.expenseFlows.filter { it.cls == cls }.take(4).forEach { f ->
                Text("   ${f.name}: ${Money.eur0(f.median)}" + if (f.inferred) " (natura dedotta)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Valori mediani mensili: un mese eccezionale non li sposta.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanCard(r: HealthReport, vm: HealthViewModel, modifier: Modifier) {
    val fin = SpeseTheme.finance
    SectionCard(modifier, title = "Cosa fare") {
        if (r.gap.signum() <= 0) {
            Text("Non serve tagliare: in prudenza metti da parte ${Money.eur0(r.prudentSaving)} al mese, più dell'obiettivo del 20% (${Money.eur0(r.targetSaving)}). Se vuoi usare il margine, dai priorità al fondo di emergenza e agli obiettivi.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text("Per arrivare al 20% delle entrate prudenti (${Money.eur0(r.targetSaving)} al mese) mancano ${Money.eur0(r.gap)} al mese. Non propongo percentuali uguali per tutti: indico, categoria per categoria, il livello che hai già mantenuto nei tuoi mesi più sobri. Le spese fisse non si toccano.", style = MaterialTheme.typography.bodyMedium)
        if (r.levers.isEmpty()) Text("Nessuna categoria variabile mostra margini misurati sul tuo storico (spendi in modo regolare ovunque): il resto va cercato nelle entrate o nelle spese fisse, qui sotto.", style = MaterialTheme.typography.bodyMedium, color = fin.warning)
        r.levers.forEach { l ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${l.category.name}: da ${Money.eur0(l.current)} a ${Money.eur0(l.target)} al mese", style = MaterialTheme.typography.titleSmall)
                    Text("−${Money.eur0(l.saving)} · ${l.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { vm.setBudget(l.category.id, l.category.name, l.budgetSuggestion) }, Modifier.testTag("lever_budget")) { Text("Imposta budget a ${Money.eur0(l.budgetSuggestion)}") }
        }
        if (r.levers.isNotEmpty()) Text("Con questi interventi il risparmio prudente passa da ${Money.eur0(r.prudentSaving)} a ${Money.eur0(r.prudentSaving + r.leversSaving)} al mese.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (r.residualGap.signum() > 0) Text("Restano ${Money.eur0(r.residualGap)} al mese che le sole spese variabili non coprono senza sacrifici oltre ciò che hai già mostrato di poter fare: servono entrate maggiori o la revisione di una spesa fissa.", style = MaterialTheme.typography.bodyMedium, color = fin.warning)
    }
}

@Composable
private fun WatchCard(r: HealthReport, modifier: Modifier) {
    SectionCard(modifier, title = "Spese fisse da rivedere") {
        Text("Non si tagliano a forza, ma vale la pena riguardarle:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.watch.forEach { w ->
            Column { Text(w.title, style = MaterialTheme.typography.titleSmall); Text(w.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun NotesCard(r: HealthReport, modifier: Modifier) {
    SectionCard(modifier, title = "Quanto fidarti di questo parere") {
        r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Text("È un'analisi sui tuoi numeri, non una consulenza finanziaria.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
