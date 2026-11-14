package it.spese.feature.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.CashQuota
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.domain.finance.SplitExpander
import it.spese.domain.usecase.SplitPartInput
import java.math.BigDecimal

private class PartRow(val categoryId: Long?, val amount: String, val quotaId: Long? = null)

/**
 * Divide un movimento su più categorie (es. un prelievo da 800 € = 500 € Affitto + 300 € Contanti).
 * Le parti si scrivono in valore positivo; la somma deve fare l'importo del movimento.
 */
@Composable
fun SplitDialog(tx: Transaction, categories: List<Category>, existing: List<TransactionSplit>, quotas: List<CashQuota>, onSave: (List<SplitPartInput>) -> Unit, onUnsplit: () -> Unit, onDismiss: () -> Unit) {
    val total = tx.amount.abs()
    val negative = tx.amount.signum() < 0
    val rows = remember {
        mutableStateListOf<PartRow>().apply {
            if (existing.isNotEmpty()) existing.sortedBy { it.position }.forEach { add(PartRow(it.categoryId, it.amount.abs().toPlainString().replace('.', ','), it.quotaId)) }
            else { add(PartRow(null, "")); add(PartRow(tx.categoryId, total.toPlainString().replace('.', ','))) }
        }
    }
    val amounts = rows.map { parseAmountOrNull(it.amount)?.abs() ?: BigDecimal.ZERO }
    val sum = amounts.fold(BigDecimal.ZERO) { a, b -> a + b }
    val left = total - sum
    val signed = amounts.map { if (negative) it.negate() else it }
    val error = SplitExpander.validate(tx.amount, signed)
    val valid = error == null && rows.all { it.categoryId != null }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Dividi ${Money.eur(total)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                // applica una quota fissa: la quota va alla sua categoria, il resto alla categoria del movimento
                val usable = quotas.filter { it.enabled && it.amount <= total }
                if (usable.isNotEmpty()) {
                    Text("Applica una quota", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        usable.forEach { q ->
                            AssistChip(onClick = {
                                rows.clear()
                                rows.add(PartRow(q.categoryId, q.amount.toPlainString().replace('.', ','), q.id))
                                if (total > q.amount) rows.add(PartRow(tx.categoryId, (total - q.amount).toPlainString().replace('.', ','))) else rows.add(PartRow(null, ""))
                            }, label = { Text("${q.name} · ${Money.eur0(q.amount)}") })
                        }
                    }
                }
                rows.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1.3f)) { DropdownField("Categoria", categories.firstOrNull { it.id == r.categoryId }, categories, { it.name }, { c -> rows[i] = PartRow(c.id, r.amount, r.quotaId) }) }
                        AmountField(r.amount, { v -> rows[i] = PartRow(r.categoryId, v, r.quotaId) }, "€", Modifier.weight(1f))
                        if (rows.size > 2) IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Default.Close, "Rimuovi parte") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { rows.add(PartRow(null, if (left.signum() > 0) left.toPlainString().replace('.', ',') else "")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Aggiungi parte") }
                    Spacer(Modifier.weight(1f))
                    Text(if (left.signum() == 0) "Quadra" else if (left.signum() > 0) "Resta ${Money.eur(left)}" else "Eccede di ${Money.eur(left.abs())}", style = MaterialTheme.typography.titleSmall,
                        color = if (left.signum() == 0) it.spese.core.designsystem.theme.SpeseTheme.finance.positive else MaterialTheme.colorScheme.error)
                }
                if (existing.isNotEmpty()) TextButton(onClick = { onUnsplit(); onDismiss() }) { Text("Ricomponi in un solo movimento") }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(rows.mapIndexed { i, r -> SplitPartInput(r.categoryId, signed[i], r.quotaId) }); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
