package it.spese.feature.categories

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.SpendNature
import it.spese.domain.finance.CategoryDetail
import java.math.BigDecimal

/** Budget mensile della categoria: avanzamento nel mese di riferimento, oppure proposta basata sulla media storica. */
@Composable
fun BudgetCard(d: CategoryDetail, onSet: (BigDecimal?) -> Unit, modifier: Modifier = Modifier) {
    var edit by remember { mutableStateOf(false) }
    val b = d.budget
    SectionCard(modifier, title = "Budget mensile", action = { if (b != null) TextButton(onClick = { edit = true }) { Text("Modifica") } }) {
        if (b != null) {
            UsageBar(b.spent.toFloat() / b.budget.toFloat().coerceAtLeast(1f), over = b.exceeded)
            Text("${Dates.monthLabelLong(d.referenceMonth)}: ${Money.eur0(b.spent)} su ${Money.eur0(b.budget)} (${b.percent.multiply(BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP)}%)", style = MaterialTheme.typography.bodyMedium)
            Text(if (b.exceeded) "Superato di ${Money.eur0(b.spent - b.budget)}" else "Restano ${Money.eur0(b.budget - b.spent)}", style = MaterialTheme.typography.bodyMedium,
                color = if (b.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Nessun budget: con un tetto mensile l'app ti avvisa quando ti avvicini o lo superi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.suggestedBudget?.let { s ->
                Button(onClick = { onSet(s) }) { Text("Imposta ${Money.eur0(s)} al mese") }
                Text("Proposto dalla media degli ultimi mesi (${Money.eur0(d.monthlyAvg)}/mese su 12 mesi).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { edit = true }) { Text("Scegli un importo") }
        }
    }
    if (edit) {
        var v by remember { mutableStateOf(b?.budget?.toPlainString()?.replace('.', ',') ?: d.suggestedBudget?.toPlainString() ?: "") }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text("Budget di ${d.category.name}") },
            text = { AmountField(v, { v = it }, "Importo mensile (€)", Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = parseAmountOrNull(v)?.signum() == 1, onClick = { onSet(parseAmountOrNull(v)); edit = false }) { Text("Salva") } },
            dismissButton = {
                Row { if (b != null) TextButton(onClick = { onSet(null); edit = false }) { Text("Rimuovi") }; TextButton(onClick = { edit = false }) { Text("Annulla") } }
            })
    }
}

/** Natura della spesa (fissa / necessaria / discrezionale), con la proposta dell'app se non è ancora definita. */
@Composable
fun NatureChoiceCard(d: CategoryDetail, onSet: (SpendNature?) -> Unit, modifier: Modifier = Modifier) {
    val cur = d.category.nature
    SectionCard(modifier, title = "Natura della spesa") {
        ChipChoice(listOf<Pair<SpendNature?, String>>(null to "Non definita") + SpendNature.entries.map { it to it.label }, cur, onSet)
        Text(cur?.hint ?: "Dice quanto puoi incidere su questa spesa.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val sug = d.suggestedNature
        if (cur == null && sug != null) TextButton(onClick = { onSet(sug.nature) }, contentPadding = PaddingValues(0.dp)) { Text("Proposta: ${sug.nature.label} (${sug.reason}) · Applica") }
    }
}

/** "E se riducessi questa spesa del …%?": risparmio mensile e annuo, effetto sull'obiettivo del Piano e salvataggio del taglio nel Piano. */
@Composable
fun CutSimulationCard(d: CategoryDetail, onSaveToPlan: (Int) -> Unit, onOpenPlan: () -> Unit, modifier: Modifier = Modifier) {
    if (d.monthlyAvg.signum() <= 0) return
    var pct by remember(d.category.id) { mutableFloatStateOf((d.planCut.takeIf { it > 0 } ?: 20).toFloat()) }
    val e = it.spese.domain.finance.CutEffect.of(d, pct.toInt())
    SectionCard(modifier, title = "E se la riducessi?") {
        Text("Oggi spendi in media ${Money.eur0(d.monthlyAvg)} al mese.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Riduzione", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("${pct.toInt()}%", style = MaterialTheme.typography.titleMedium)
        }
        Slider(pct, { pct = it }, valueRange = 0f..50f, steps = 9)
        if (pct.toInt() > 0) {
            Text("Risparmi ${Money.eur0(e.monthly)} al mese, ${Money.eur0(e.yearly)} all'anno.", style = MaterialTheme.typography.titleSmall)
            e.goalCoverage?.let { c ->
                Text("Con questo risparmio metteresti da parte ${Money.eur0(e.newSavingsPerMonth)} al mese: ${c}% del tuo obiettivo di ${Money.eur0(d.goalMonthly)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSaveToPlan(pct.toInt()) }, enabled = pct.toInt() != d.planCut) { Text(if (pct.toInt() == 0) "Rimuovi dal Piano" else "Salva nel Piano") }
            if (d.planCut > 0) TextButton(onClick = onOpenPlan) { Text("Apri il Piano") }
        }
        if (d.planCut > 0) Text("Nel Piano c'è già un taglio del ${d.planCut}% su questa categoria.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
