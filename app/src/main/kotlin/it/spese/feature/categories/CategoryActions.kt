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
