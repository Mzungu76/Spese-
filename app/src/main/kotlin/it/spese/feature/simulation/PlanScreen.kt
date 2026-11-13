package it.spese.feature.simulation

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
import it.spese.core.model.*
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal

@Composable
fun PlanScreen(menu: DataMenu, bottomPadding: PaddingValues = PaddingValues(), vm: PlanViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TabTopBar("Piano e simulazioni", menu) }, contentWindowInsets = WindowInsets(0)) { pad ->
        val sc = st.scenario; val ctx = st.context; val r = st.result
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (sc == null || ctx == null || r == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (!st.data.loaded) CircularProgressIndicator() else Text("Importa dei movimenti per iniziare a pianificare") }; return@Scaffold }
            FilterHeader(Modifier.padding(bottom = 4.dp))
            val base = ctx.base
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Medie mensili calcolate sul periodo filtrato (${"%.1f".format(java.util.Locale.ITALY, base.months.toDouble())} mesi). Giroconti esclusi. Ogni modifica aggiorna subito i risultati.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { ScenarioPicker(st, vm) }
                item { SummaryCard(st) }
                item { GoalCard(st, vm) }
                item { WhatIfCard(st, vm) }
                item { CutsCard(st, vm) }
                item { ProjectionCard(st) }
                item { ComparisonCard(st, vm) }
                item { BudgetCard(st, vm) }
            }
        }
    }
}

@Composable
private fun ScenarioPicker(st: PlanState, vm: PlanViewModel) {
    var saveDialog by remember { mutableStateOf(false) }
    SectionCard(title = "Scenario", action = { TextButton(onClick = { saveDialog = true }) { Icon(Icons.Default.Add, null); Text("Salva come…") } }) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.data.scenarios.forEach { s -> FilterChip(st.scenario?.id == s.id, { vm.select(s.id) }, { Text(s.name) }) }
        }
        val cur = st.scenario
        if (cur != null && !cur.isBase) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var rename by remember(cur.id) { mutableStateOf(false) }
                OutlinedButton(onClick = { vm.delete(cur.id) }) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Elimina scenario") }
            }
        }
        Text("Lo scenario \"Piano attuale\" è il tuo piano reale (obiettivo e tagli salvati). Gli altri servono per provare ipotesi e confrontarle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("Salva come nuovo scenario") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nome (es. Taglio svago −30%)") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.saveAsNew(name); saveDialog = false }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Annulla") } })
    }
}

@Composable
private fun SummaryCard(st: PlanState) {
    val r = st.result!!; val goal = st.scenario!!.goal
    SectionCard(title = "Risultato della simulazione") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Risparmio / mese", style = MaterialTheme.typography.labelMedium); MoneyText(r.simulatedMonthlySaving, signed = true, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.End) { Text("Prima", style = MaterialTheme.typography.labelMedium); MoneyText(r.baselineMonthlySaving, signed = true, colored = false) }
        }
        UsageBar(if (r.goalMonthly.signum() > 0) (r.simulatedMonthlySaving.toFloat() / r.goalMonthly.toFloat()) else 1f)
        Text(if (r.goalReached) "Obiettivo di ${Money.eur0(r.goalMonthly)}/mese raggiunto ✓ (+${Money.eur0(r.vsGoal)})" else "Mancano ${Money.eur0(r.vsGoal.negate())}/mese all'obiettivo di ${Money.eur0(r.goalMonthly)}/mese",
            color = if (r.goalReached) SpeseTheme.finance.positive else SpeseTheme.finance.negative, fontWeight = FontWeight.Medium)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Tra 6 mesi", r.balanceAt(6)); Stat("Tra 12 mesi", r.balanceAt(12)); Stat("Risparmio annuo", r.yearlySaving())
        }
        Text("Saldo di partenza ${Money.eur0(st.context!!.startingBalance)} (saldi iniziali + movimenti); proiezione dal mese successivo all'ultimo movimento.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (goal.mode == GoalMode.TOTAL) Text(r.monthsToGoal?.let { if (it == 0) "Obiettivo di ${Money.eur0(goal.value)} già raggiunto" else "Per raggiungere ${Money.eur0(goal.value)} servono circa $it mesi" } ?: "Con questo risparmio l'obiettivo di ${Money.eur0(goal.value)} non viene raggiunto", fontWeight = FontWeight.Medium)
        if (r.savingsRatePercent != null) Text("Tasso di risparmio simulato: ${r.savingsRatePercent}%", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Stat(label: String, v: BigDecimal?) = Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); if (v != null) MoneyText(v, decimals = false, signed = false, colored = false) }

@Composable
private fun GoalCard(st: PlanState, vm: PlanViewModel) {
    val sc = st.scenario!!; val g = sc.goal; val base = st.context!!.base
    var value by remember(sc.id, g.mode) { mutableStateOf(g.value.stripTrailingZeros().toPlainString().replace('.', ',')) }
    var months by remember(sc.id) { mutableStateOf(g.months.toString()) }
    var income by remember(sc.id) { mutableStateOf(g.expectedIncome?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "") }
    SectionCard(title = "1 · Obiettivo di risparmio") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(g.mode == GoalMode.MONTHLY, { vm.setGoal(g.copy(mode = GoalMode.MONTHLY)) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("€ al mese") }
            SegmentedButton(g.mode == GoalMode.TOTAL, { vm.setGoal(g.copy(mode = GoalMode.TOTAL)) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Importo totale") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(value, { value = it; parseAmountOrNull(it)?.let { v -> vm.setGoal(g.copy(value = v)) } }, if (g.mode == GoalMode.TOTAL) "Importo da raggiungere" else "Risparmio mensile", Modifier.weight(1f))
            if (g.mode == GoalMode.TOTAL) OutlinedTextField(months, { months = it.filter { c -> c.isDigit() }; it.toIntOrNull()?.takeIf { m -> m > 0 }?.let { m -> vm.setGoal(g.copy(months = m)) } }, label = { Text("In mesi") }, singleLine = true, modifier = Modifier.weight(0.6f))
        }
        AmountField(income, { income = it; vm.setGoal(g.copy(expectedIncome = parseAmountOrNull(it))) }, "Entrate mensili attese (vuoto = media ${Money.eur0(base.incomeAuto)})", Modifier.fillMaxWidth())
        Text("Suggerimenti (in base a entrate di ${Money.eur0(base.income)}/mese):", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanEngine.suggestions(base.income).forEach { (p, v) -> AssistChip(onClick = { value = v.toPlainString(); vm.setGoal(g.copy(mode = GoalMode.MONTHLY, value = v)) }, label = { Text("$p% · ${Money.eur0(v)}") }) }
        }
        val (lo, hi) = PlanEngine.emergencyFund(base.expense)
        Text("Regola 50/30/20: 50% bisogni, 30% desideri, 20% risparmio. Fondo di emergenza consigliato: ${Money.eur0(lo)}–${Money.eur0(hi)} (3–6 mesi di spese). Tagliando il 20% delle voci comprimibili risparmieresti circa ${Money.eur0(PlanEngine.potentialSavings(base))}/mese.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Entrate medie / mese"); MoneyText(base.income) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Spese medie / mese"); MoneyText(base.expense.negate()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Risparmio attuale / mese", fontWeight = FontWeight.Bold); MoneyText(base.currentSaving, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun WhatIfCard(st: PlanState, vm: PlanViewModel) {
    val sc = st.scenario!!; val ctx = st.context!!
    var delta by remember(sc.id) { mutableStateOf(if (sc.incomeDelta.signum() != 0) sc.incomeDelta.stripTrailingZeros().toPlainString().replace('.', ',') else "") }
    SectionCard(title = "2 · E se…?") {
        AmountField(delta, { delta = it; vm.setIncomeDelta(parseAmountOrNull(it) ?: BigDecimal.ZERO) }, "Variazione entrate mensili (es. 200 oppure -150)", Modifier.fillMaxWidth(), supporting = "Quanto posso risparmiare aumentando le entrate?")
        if (ctx.commitments.isNotEmpty() || ctx.scheduled.isNotEmpty()) {
            Text("Elimina una spesa ricorrente o una scadenza dalla simulazione:", style = MaterialTheme.typography.labelLarge)
            ctx.commitments.forEach { c -> RemovableRow("${c.name} (~${Money.eur0(c.monthlyAmount)}/mese)", c.key in sc.removedRecurring) { vm.toggleRemoved(c.key) } }
            ctx.scheduled.forEach { c -> RemovableRow("${c.name} (${Money.eur0(c.amount)}, ${c.frequency.label}) · scadenza", c.key in sc.removedRecurring) { vm.toggleRemoved(c.key) } }
            Text("Le ricorrenze rilevate sono già nella media storica: eliminarle aumenta il risparmio. Le scadenze manuali si sommano come uscite nei mesi in cui cadono.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val r = st.result!!
        if (r.recurringRemovedSavings.signum() != 0) Text("Risparmio da ricorrenze eliminate: ${Money.eur0(r.recurringRemovedSavings)}/mese", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RemovableRow(label: String, removed: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textDecoration = if (removed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
        Switch(removed, { onToggle() }); Text(if (removed) " tolta" else " inclusa", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CutsCard(st: PlanState, vm: PlanViewModel) {
    val sc = st.scenario!!; val base = st.context!!.base; val r = st.result!!
    SectionCard(title = "3 · Simulatore di tagli") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::autoCut, enabled = base.flexibleAvg.signum() > 0 && r.vsGoal.signum() < 0) { Text("Distribuisci il taglio") }
            OutlinedButton(onClick = vm::resetCuts) { Text("Azzera tagli") }
        }
        Text("Risparmio con i tagli: ${Money.eur(r.cutSavings)}/mese · nuovo risparmio ${Money.eur(r.simulatedMonthlySaving)}/mese", fontWeight = FontWeight.Medium)
        base.categories.forEach { c ->
            val pct = sc.cuts[c.categoryId] ?: 0
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name + if (c.flexible) "  ·  comprimibile" else "", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Text("${Money.eur0(c.monthlyAvg)}/mese", style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(pct.toFloat(), { vm.setCut(c.categoryId, it.toInt()) }, Modifier.weight(1f), valueRange = -50f..100f, steps = 29)
                    Text(if (pct == 0) "0%" else "$pct%", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                    MoneyText(Money.pctOf(c.monthlyAvg, pct), decimals = false, colored = false, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
                }
            }
        }
        Text("Valori negativi = aumento di spesa. Taglio e risparmio sono stime mensili.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProjectionCard(st: PlanState) {
    val r = st.result!!; val goalM = r.goalMonthly
    val labels = r.projection.mapIndexed { i, p -> if (i == 0) "oggi" else Dates.monthLabel(p.month) }
    SectionCard(title = "Proiezione del saldo a 12 mesi") {
        LineChart(labels, listOf(
            ChartSeries("Senza modifiche", MaterialTheme.colorScheme.outline, r.baselineProjection.map { it.balance }),
            ChartSeries("Simulazione", SpeseTheme.finance.positive, r.projection.map { it.balance }),
            ChartSeries("Solo obiettivo", MaterialTheme.colorScheme.tertiary, r.projection.indices.map { st.context!!.startingBalance + goalM.multiply(BigDecimal(it)) }),
        ), height = 240.dp)
        Text("Entrate ${Money.eur0(r.simulatedIncome)}/mese · spese ${Money.eur0(r.simulatedExpense)}/mese. Tocca il grafico per i valori.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComparisonCard(st: PlanState, vm: PlanViewModel) {
    SectionCard(title = "Confronto tra scenari") {
        Text("Seleziona gli scenari da confrontare:", style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.data.scenarios.forEach { s -> FilterChip(s.id in st.compareIds, { vm.toggleCompare(s.id) }, { Text(s.name) }) }
        }
        if (st.comparison.size >= 2) {
            st.comparison.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Risparmio/mese"); MoneyText(c.monthlySaving, decimals = false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Differenza dal primo"); MoneyText(c.deltaVsFirst, decimals = false, signed = true) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Saldo tra 6 / 12 mesi"); Text("${c.balance6?.let { Money.eur0(it) } ?: "–"} / ${c.balance12?.let { Money.eur0(it) } ?: "–"}") }
                    if (c.monthsToGoal != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Mesi all'obiettivo"); Text("${c.monthsToGoal}") }
                    Text(if (c.goalReached) "Obiettivo mensile raggiunto" else "Obiettivo mensile non raggiunto", style = MaterialTheme.typography.labelSmall, color = if (c.goalReached) SpeseTheme.finance.positive else SpeseTheme.finance.negative)
                }
                HorizontalDivider()
            }
            val labels = st.compareResults.first().result.projection.mapIndexed { i, p -> if (i == 0) "oggi" else Dates.monthLabel(p.month) }
            val colors = listOf(MaterialTheme.colorScheme.primary, SpeseTheme.finance.positive, MaterialTheme.colorScheme.tertiary, SpeseTheme.finance.negative, MaterialTheme.colorScheme.secondary)
            LineChart(labels, st.compareResults.mapIndexed { i, o -> ChartSeries(o.name, colors[i % colors.size], o.result.projection.map { it.balance }) })
        } else Text("Scegli almeno due scenari.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BudgetCard(st: PlanState, vm: PlanViewModel) {
    val base = st.context!!.base; val data = st.data
    val total = base.categories.fold(BigDecimal.ZERO) { a, c -> a + (data.categoriesById[c.categoryId]?.budgetMonthly ?: BigDecimal.ZERO) }
    SectionCard(title = "4 · Budget per categoria") {
        Text("Totale budget ${Money.eur0(total)}/mese" + if (total.signum() > 0) " · risparmio previsto ${Money.eur0(base.income - total)}" else "", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.setBudgets("avg") }, label = { Text("= media attuale") })
            AssistChip(onClick = { vm.setBudgets("cut") }, label = { Text("= media dopo tagli") })
            AssistChip(onClick = { vm.setBudgets("clear") }, label = { Text("Cancella") })
        }
        base.categories.forEach { c ->
            val cat = data.categoriesById[c.categoryId] ?: return@forEach
            var text by remember(cat.id, cat.budgetMonthly) { mutableStateOf(cat.budgetMonthly?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "") }
            val b = cat.budgetMonthly
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, maxLines = 1); Text("media ${Money.eur0(c.monthlyAvg)}" + (b?.let { " · scostamento ${Money.eur0(c.monthlyAvg - it)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(text, { text = it; vm.setBudget(cat.id, parseAmountOrNull(it)?.takeIf { v -> v.signum() > 0 }) }, Modifier.width(110.dp), singleLine = true, label = { Text("€/mese") })
                }
                if (b != null && b.signum() > 0) UsageBar((c.monthlyAvg.toFloat() / b.toFloat()), over = c.monthlyAvg > b)
            }
        }
    }
}
