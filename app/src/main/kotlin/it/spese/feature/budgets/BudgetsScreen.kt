package it.spese.feature.budgets

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
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.BudgetRow
import java.math.BigDecimal

private fun parsed(t: String): BigDecimal? = parseAmountOrNull(t)?.takeIf { it.signum() > 0 }
private fun plain(v: BigDecimal?) = v?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: ""

/**
 * Budget per categoria, uno per uno: scrivi l'importo che vuoi (vuoto = nessun budget) e premi «Salva». Nulla cambia da solo.
 * Con «Proponi dagli obiettivi» ottieni una proposta da rivedere riga per riga; le spese fisse non si toccano se non lo chiedi.
 */
@Composable
fun BudgetsScreen(onBack: () -> Unit, vm: BudgetsViewModel = hiltViewModel()) {
    val o by vm.overview.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    var proposal by remember { mutableStateOf(false) }
    val fin = SpeseTheme.finance
    Column(Modifier.fillMaxSize()) {
        BackHeader("Budget per categoria", onBack)
        val ov = o
        if (ov == null || ov.rows.isEmpty()) {
            EmptyState(Icons.Default.Tune, "Nessuna categoria di spesa", "Servono dei movimenti importati per vedere la spesa media di ogni categoria.")
            return@Column
        }
        val edits = remember(ov.rows) { mutableStateMapOf<Long, String>().also { m -> ov.rows.forEach { m[it.category.id] = plain(it.current) } } }
        val changed = ov.rows.filter { parsed(edits[it.category.id].orEmpty()) != it.current }
        val total = ov.rows.fold(BigDecimal.ZERO) { a, r -> a + (parsed(edits[r.category.id].orEmpty()) ?: BigDecimal.ZERO) }
        val room = ov.income - ov.savings
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Eyebrow("Budget impostati")
                        Text(Money.eur0(total) + " al mese", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (ov.income.signum() > 0) Text("Entrate medie ${Money.eur0(ov.income)} − da mettere da parte ${Money.eur0(ov.savings)} = ${Money.eur0(room)} per le spese.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (total > room && ov.income.signum() > 0) Text("I budget superano di ${Money.eur0(total - room)} ciò che resta dopo il risparmio.", style = MaterialTheme.typography.bodySmall, color = fin.warning)
                        if (plan != null) OutlinedButton(onClick = { proposal = true }, Modifier.testTag("propose_budgets")) { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Proponi dagli obiettivi") }
                    }
                }
                items(ov.rows, key = { it.category.id }) { r -> BudgetLine(r, edits[r.category.id].orEmpty()) { edits[r.category.id] = it } }
            }
            if (changed.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${changed.size} ${if (changed.size == 1) "modifica" else "modifiche"} non salvate", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ov.rows.forEach { edits[it.category.id] = plain(it.current) } }) { Text("Annulla") }
                    Button(onClick = { vm.save(changed.associate { it.category.id to parsed(edits[it.category.id].orEmpty()) }) }, Modifier.testTag("save_budgets")) { Text("Salva") }
                }
            }
        }
    }
    if (proposal) plan?.let { p -> BudgetProposalDialog(p, { proposal = false }) { list -> vm.save(list.toMap()); proposal = false } }
}

@Composable
private fun BudgetLine(r: BudgetRow, text: String, onChange: (String) -> Unit) {
    val fin = SpeseTheme.finance
    val b = parsed(text)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.category.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text((r.category.nature?.label ?: "Natura non indicata") + " · media ${Money.eur0(r.average)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (b != null && r.average > b) Text("La media è sopra il budget", style = MaterialTheme.typography.labelSmall, color = fin.warning)
        }
        AmountField(text, onChange, "€/mese", Modifier.width(130.dp))
    }
}
