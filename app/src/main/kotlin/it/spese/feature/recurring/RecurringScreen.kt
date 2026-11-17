package it.spese.feature.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.app.common.RoundIconButton
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.Category
import it.spese.core.model.RecurringRule
import it.spese.core.model.TxKind
import it.spese.domain.recurring.RecurringEngine
import java.math.BigDecimal
import java.time.LocalDate

/** Entrate e spese ricorrenti (stipendio, affitto incassato, abbonamenti…): l'app crea da sola il movimento alla data giusta. */
@Composable
fun RecurringScreen(onBack: () -> Unit, vm: RecurringViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<RecurringRule?>(null) }
    val today = remember { LocalDate.now() }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Ricorrenti", onBack, actions = {
            RoundIconButton(Icons.Default.Add, "Nuovo movimento ricorrente", { edit = RecurringRule(0, "", TxKind.ENTRATA, BigDecimal.ZERO, null, null, 1, today.dayOfMonth, today) }, Modifier.testTag("recurring_add"), primary = true)
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("Entrate o spese che si ripetono (stipendio, affitto incassato, abbonamenti): l'app crea il movimento alla data giusta, senza che tu lo inserisca. Il movimento nasce provvisorio e compare in Movimenti › Verifica finché non lo confermi; quando importi l'estratto conto la riga della banca lo sostituisce.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rules.isEmpty()) item { EmptyState(Icons.Default.Autorenew, "Nessun movimento ricorrente", "Tocca + per aggiungere, ad esempio, uno stipendio da 2.000 € ogni 27 del mese.") }
            items(rules, key = { it.id }) { r ->
                val cat = data.categoriesById[r.categoryId]
                Row(Modifier.fillMaxWidth().clickable { edit = r }.padding(horizontal = 24.dp).heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategoryAvatar(cat, size = 48)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text((if (r.kind == TxKind.ENTRATA) "+" else "−") + Money.eur0(r.amount) + " · " + RecurringEngine.describe(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        val next = RecurringEngine.next(r, today.minusDays(1))
                        Text(when { !r.enabled -> "Spento"; next == null -> "Terminato"; else -> "Prossimo: " + Dates.fmt(next) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.enabled, { on -> vm.setEnabled(r, on) })
                }
            }
        }
    }
    edit?.let { r -> RecurringDialog(r, data.categories.filter { it.active && it.kind != TxKind.TRASFERIMENTO }, data.accounts.filter { !it.archived }, { edit = null }, vm) }
}

@Composable
private fun RecurringDialog(r: RecurringRule, categories: List<Category>, accounts: List<it.spese.core.model.Account>, onDismiss: () -> Unit, vm: RecurringViewModel) {
    var kind by remember { mutableStateOf(r.kind) }
    var name by remember { mutableStateOf(r.name) }
    var amount by remember { mutableStateOf(if (r.amount.signum() > 0) r.amount.toPlainString().replace('.', ',') else "") }
    var acc by remember { mutableStateOf(r.accountId) }
    var cat by remember { mutableStateOf(r.categoryId) }
    var every by remember { mutableStateOf(r.everyMonths) }
    var day by remember { mutableStateOf(r.dayOfMonth.toString()) }
    var start by remember { mutableStateOf<LocalDate?>(r.startDate) }
    var end by remember { mutableStateOf(r.endDate) }
    var auto by remember { mutableStateOf(r.autoConfirm) }
    val cats = categories.filter { it.kind == kind }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (r.id == 0L) "Nuovo movimento ricorrente" else "Modifica") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(kind == TxKind.ENTRATA, { kind = TxKind.ENTRATA; cat = null }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Entrata") }
                    SegmentedButton(kind == TxKind.SPESA, { kind = TxKind.SPESA; cat = null }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Spesa") }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nome (es. Stipendio)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(amount, { amount = it }, "Importo (€)", Modifier.fillMaxWidth())
                if (accounts.size > 1) DropdownField("Conto", accounts.firstOrNull { it.id == acc }, accounts, { it.name }, { a -> acc = a.id }, nullLabel = "Il primo conto", onNull = { acc = null })
                it.spese.app.common.CategoryField("Categoria", cats.firstOrNull { it.id == cat }, cats, { c -> cat = c.id }, nullLabel = "Scelta dalle regole", onNull = { cat = null })
                Text("Ripetizione", style = MaterialTheme.typography.labelLarge)
                ChipChoice(listOf(1 to "Ogni mese", 2 to "Ogni 2 mesi", 3 to "Ogni 3 mesi", 6 to "Ogni 6 mesi", 12 to "Ogni anno"), every, { every = it })
                OutlinedTextField(day, { day = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Giorno del mese") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Se il mese è più corto si usa l'ultimo giorno (31 → 28, 30…)") })
                DateField(start, { start = it }, "Dal", Modifier.fillMaxWidth())
                DateField(end, { end = it }, "Fino al (facoltativo)", Modifier.fillMaxWidth(), clearable = true, onClear = { end = null })
                LabeledSwitch("Registra senza verifica", auto, { auto = it }, supporting = "Il movimento nasce già confermato e non passa da Verifica")
                if (r.id != 0L) TextButton(onClick = { vm.delete(r.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && (parseAmountOrNull(amount)?.signum() ?: 0) > 0 && start != null && (day.toIntOrNull() ?: 0) in 1..31, onClick = {
            vm.save(r.copy(name = name, kind = kind, amount = parseAmountOrNull(amount) ?: BigDecimal.ZERO, accountId = acc, categoryId = cat, everyMonths = every, dayOfMonth = day.toIntOrNull() ?: 1, startDate = start ?: LocalDate.now(), endDate = end, autoConfirm = auto)); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
