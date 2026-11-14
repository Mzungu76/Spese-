package it.spese.feature.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.SubTopBar
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.DueFrequency
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun AlertsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenSubscriptions: () -> Unit = {}, vm: AlertsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf(false) }
    Scaffold(topBar = { SubTopBar("Avvisi e scadenze", onBack) }, contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { add = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Scadenza") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Avvisi") {
                    if (st.alerts.isEmpty()) Text("Nessun avviso. Imposta dei budget nel Piano per ricevere avvisi di superamento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    st.alerts.forEach { a -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) { Box(Modifier.padding(top = 6.dp)) { SeverityDot(a.level) }; Text(a.message, style = MaterialTheme.typography.bodyMedium) } }
                    Text("Calcolati sull'ultimo mese (${Dates.monthLabel(st.data.calendar.monthOf(st.today))})${st.accountName?.let { " del conto $it" } ?: " di tutti i conti"}, riferimento al ${Dates.fmt(st.today)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenSettings) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("Configura le notifiche") }
                }
            }
            item {
                SectionCard(title = "Budget del mese in corso") {
                    if (st.usage.isEmpty()) Text("Nessun budget impostato.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    st.usage.forEach { u ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(u.category.name, fontWeight = FontWeight.Medium); MoneyText(u.spent, decimals = false, colored = false, style = MaterialTheme.typography.bodyMedium)
                            }
                            UsageBar(u.percent.toFloat() / 100f, over = u.exceeded)
                            Text("di ${Money.eur0(u.budget)} · ${u.percent.setScale(0, java.math.RoundingMode.HALF_UP)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Scadenze e spese ricorrenti") {
                    OutlinedButton(onClick = onOpenSubscriptions, Modifier.fillMaxWidth()) { Icon(Icons.Default.Autorenew, null); Spacer(Modifier.width(8.dp)); Text("Abbonamenti e rate: costi, aumenti, rate residue") }
                    Text("Ricorrenze rilevate in automatico (stesso esercente, ogni mese, importo simile) più le tue scadenze. Prossimi 30 giorni: ${Money.eur0(st.next30)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.schedule.isEmpty()) Text("Nessuna scadenza.")
                    st.schedule.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("${Dates.fmt(r.date)} · ${when { r.daysLeft < 0 -> "${-r.daysLeft} g fa"; r.daysLeft == 0 -> "oggi"; else -> "tra ${r.daysLeft} g" }} · ${if (r.dueId != null) r.frequency!!.label else "rilevata"}",
                                    style = MaterialTheme.typography.bodySmall, color = if (r.daysLeft < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(r.amount, decimals = false, colored = false)
                            if (r.dueId != null) {
                                IconButton(onClick = { vm.markPaid(r.dueId) }) { Icon(Icons.Default.CheckCircle, "Segna come pagata") }
                                IconButton(onClick = { vm.delete(r.dueId) }) { Icon(Icons.Default.Delete, "Elimina scadenza") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (add) {
        var name by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(st.today) }; var freq by remember { mutableStateOf(DueFrequency.MONTHLY) }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("Nuova scadenza") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome scadenza") }, singleLine = true)
                AmountField(amt, { amt = it }, "Importo")
                DateField(date, { date = it }, "Prossima data")
                DropdownField("Frequenza", freq, DueFrequency.entries, { it.label }, { freq = it })
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank() && (parseAmountOrNull(amt)?.signum() ?: 0) > 0, onClick = { vm.addDue(name, parseAmountOrNull(amt)!!, date, freq); add = false }) { Text("Aggiungi") } },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Annulla") } })
    }
}
