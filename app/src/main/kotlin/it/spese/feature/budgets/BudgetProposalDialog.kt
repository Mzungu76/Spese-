package it.spese.feature.budgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spese.core.common.Money
import it.spese.core.designsystem.component.AmountField
import it.spese.core.designsystem.component.parseAmountOrNull
import it.spese.domain.finance.BudgetChanges
import it.spese.domain.finance.BudgetPlan
import it.spese.domain.finance.BudgetProposal
import java.math.BigDecimal

/**
 * Proposta di budget dagli obiettivi, da rivedere riga per riga: ogni importo si può cambiare e ogni riga si può spuntare o no.
 * Le spese fisse (affitto, rate…) sono in una sezione a parte, NON spuntate: non le tocco se non lo chiedi tu.
 */
@Composable
fun BudgetProposalDialog(p: BudgetPlan, onDismiss: () -> Unit, onApply: (List<Pair<Long, BigDecimal>>) -> Unit) {
    val byId = p.proposals.associateBy { it.category.id }
    val checked = remember(p) { mutableStateMapOf<Long, Boolean>().also { m -> BudgetChanges.defaultSelection(p).forEach { m[it.category.id] = true } } }
    val amounts = remember(p) { mutableStateMapOf<Long, String>().also { m -> p.proposals.forEach { m[it.category.id] = it.proposed.stripTrailingZeros().toPlainString().replace('.', ',') } } }
    val chosen = p.proposals.filter { checked[it.category.id] == true }.mapNotNull { r -> parseAmountOrNull(amounts[r.category.id].orEmpty())?.takeIf { it.signum() > 0 }?.let { r.category.id to it } }
    val (fixed, others) = p.proposals.partition { it.fixed }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Budget mensili proposti") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Calcolati su ${p.months} ${if (p.months == 1) "mese" else "mesi"} di storico: entrate ${Money.eur0(p.income)}, da mettere da parte ${Money.eur0(p.savings)}. Cambia gli importi come vuoi e spunta solo le righe da applicare.", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    p.shortfall.signum() > 0 -> "Attenzione: anche azzerando le spese discrezionali mancano ${Money.eur0(p.shortfall)} al mese. Conviene abbassare le quote degli obiettivi."
                    p.cutPercent > 0 -> "Per rispettare gli obiettivi le spese discrezionali scendono del ${p.cutPercent}%; le altre restano alla tua media."
                    else -> "Le tue spese di sempre stanno già dentro gli obiettivi: i budget sono la tua media."
                }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                others.forEach { r -> ProposalRow(r, checked, amounts) }
                if (fixed.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Spese fisse: le cambio solo se lo chiedi", style = MaterialTheme.typography.titleSmall)
                    Text("Affitto, rate e simili non si riducono in automatico. Spunta una riga solo se vuoi davvero impostare quel budget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    fixed.forEach { r -> ProposalRow(r, checked, amounts) }
                }
                Text("Le righe spuntate sostituiscono il budget che hai ora su quelle categorie; le altre restano come sono.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onApply(chosen) }, modifier = Modifier.testTag("apply_budgets")) { Text("Imposta ${chosen.size} ${if (chosen.size == 1) "budget" else "budget"}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun ProposalRow(r: BudgetProposal, checked: MutableMap<Long, Boolean>, amounts: MutableMap<Long, String>) {
    val id = r.category.id
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked[id] == true, { checked[id] = it })
        Column(Modifier.weight(1f)) {
            Text(r.category.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text("media ${Money.eur0(r.average)}" + (r.current?.let { " · ora ${Money.eur0(it)}" } ?: "") + if (r.cutPercent > 0) " · −${r.cutPercent}%" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AmountField(amounts[id].orEmpty(), { amounts[id] = it; checked[id] = true }, "€/mese", Modifier.width(120.dp))
    }
}
