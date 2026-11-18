package it.spese.feature.reminders

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
import it.spese.core.designsystem.component.*
import it.spese.core.model.Account
import it.spese.domain.reminders.*
import java.time.LocalDate
import java.time.LocalDateTime

/** Sveglie: promemoria a ora fissa per importare gli estratti conto (con le date da coprire) o per qualsiasi altra cosa. */
@Composable
fun RemindersScreen(onBack: () -> Unit, vm: RemindersViewModel = hiltViewModel()) {
    val list by vm.reminders.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Reminder?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Sveglie", onBack, actions = {
            RoundIconButton(Icons.Default.Add, "Nuova sveglia", { edit = Reminder(0, ReminderKind.IMPORT, "") }, Modifier.testTag("reminder_add"), primary = true)
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("Una notifica a ora fissa. Per l'estratto conto ti dice anche da che data a oggi importare, conto per conto; con «Sveglia personale» scrivi tu il promemoria.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!vm.canNotify) Text("Le notifiche dell'app sono disattivate: attivale dalle impostazioni del telefono, altrimenti le sveglie non suonano.",
                    Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (list.isEmpty()) item { EmptyState(Icons.Default.Alarm, "Nessuna sveglia", "Tocca + per aggiungerne una, ad esempio «Importa estratto conto» il 1° di ogni mese.") }
            items(list, key = { it.id }) { r ->
                val acc = data.accounts.firstOrNull { it.id == r.accountId }
                val next = if (r.enabled) ReminderEngine.next(r, LocalDateTime.now()) else null
                Row(Modifier.fillMaxWidth().clickable { edit = r }.padding(horizontal = 24.dp).heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (r.kind == ReminderKind.IMPORT) Icons.Default.UploadFile else Icons.Default.Alarm, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(r.title.ifBlank { if (r.kind == ReminderKind.IMPORT) "Importa estratto · " + (acc?.name ?: "tutti i conti") else "Promemoria" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(ReminderEngine.describe(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(when { !r.enabled -> "Spenta"; next == null -> "Già passata"; else -> "Prossima: " + Dates.fmt(next.toLocalDate()) + " alle " + "%02d:%02d".format(next.hour, next.minute) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.enabled, { on -> vm.setEnabled(r, on) })
                }
            }
        }
    }
    edit?.let { r -> ReminderDialog(r, data.accounts.filter { !it.archived }, { edit = null }, vm) }
}

@Composable
private fun ReminderDialog(r: Reminder, accounts: List<Account>, onDismiss: () -> Unit, vm: RemindersViewModel) {
    var kind by remember { mutableStateOf(r.kind) }
    var title by remember { mutableStateOf(r.title) }
    var note by remember { mutableStateOf(r.note) }
    var acc by remember { mutableStateOf(r.accountId) }
    var repeat by remember { mutableStateOf(r.repeat) }
    var dow by remember { mutableStateOf(r.dayOfWeek) }
    var dom by remember { mutableStateOf(r.dayOfMonth.toString()) }
    var date by remember { mutableStateOf(r.date ?: LocalDate.now().plusDays(1)) }
    var hour by remember { mutableStateOf("%02d".format(r.hour)) }
    var minute by remember { mutableStateOf("%02d".format(r.minute)) }
    val valid = (kind == ReminderKind.IMPORT || title.isNotBlank()) && (hour.toIntOrNull() ?: -1) in 0..23 && (minute.toIntOrNull() ?: -1) in 0..59 &&
        (repeat != ReminderRepeat.MONTHLY || (dom.toIntOrNull() ?: 0) in 1..31)
    fun build() = r.copy(kind = kind, title = title.trim(), note = note.trim(), accountId = if (kind == ReminderKind.IMPORT) acc else null, repeat = repeat, dayOfWeek = dow,
        dayOfMonth = dom.toIntOrNull() ?: 1, date = if (repeat == ReminderRepeat.ONCE) date else null, hour = hour.toIntOrNull() ?: 9, minute = minute.toIntOrNull() ?: 0, enabled = true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (r.id == 0L) "Nuova sveglia" else "Modifica sveglia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipChoice(listOf(ReminderKind.IMPORT to "Estratto conto", ReminderKind.CUSTOM to "Sveglia personale"), kind, { kind = it })
                if (kind == ReminderKind.IMPORT) {
                    DropdownField("Conto", accounts.firstOrNull { it.id == acc }, accounts, { it.name }, { a -> acc = a.id }, Modifier.fillMaxWidth(), nullLabel = "Tutti i conti", onNull = { acc = null })
                    Text("La notifica dirà da quale data (dall'ultimo estratto importato) a oggi scaricare e importare i movimenti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(title, { title = it }, label = { Text(if (kind == ReminderKind.IMPORT) "Titolo (facoltativo)" else "Titolo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Nota (facoltativa)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Quando", style = MaterialTheme.typography.labelLarge)
                ChipChoice(ReminderRepeat.entries.map { it to it.label }, repeat, { repeat = it })
                when (repeat) {
                    ReminderRepeat.ONCE -> DateField(date, { date = it }, "Giorno", Modifier.fillMaxWidth())
                    ReminderRepeat.WEEKLY -> ChipChoice(listOf(1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Gio", 5 to "Ven", 6 to "Sab", 7 to "Dom"), dow, { dow = it })
                    ReminderRepeat.MONTHLY -> OutlinedTextField(dom, { dom = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Giorno del mese") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Se il mese è più corto suona l'ultimo giorno.") })
                    ReminderRepeat.DAILY -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(hour, { hour = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Ora") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(minute, { minute = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Minuti") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (r.id != 0L) Row {
                    TextButton(onClick = { vm.test(build()) }) { Text("Prova ora") }
                    TextButton(onClick = { vm.delete(r.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina") }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { vm.save(build()); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
