package it.spese.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.SubTopBar
import it.spese.app.common.UiMessenger
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.Account
import it.spese.core.model.AccountType
import it.spese.domain.repository.AccountRepository
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(val data: StateFlow<AppData>, private val repo: AccountRepository, private val messenger: UiMessenger, private val deleteUc: it.spese.domain.usecase.DeleteAccountUseCase) : ViewModel() {
    fun save(a: Account, newName: String) = viewModelScope.launch {
        try {
            if (a.id == 0L) repo.getOrCreate(newName).let { repo.update(it.copy(type = a.type, openingBalance = a.openingBalance)) }
            else { repo.update(a.copy(name = a.name)); if (newName.isNotBlank() && newName != a.name) repo.renameOrMerge(a.id, newName) }
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }

    /** Cosa tocca l'eliminazione di un conto (movimenti, ricorrenze, fonti di notifica, valori). */
    suspend fun impact(id: Long) = deleteUc.impact(id)

    /** Elimina il conto dopo che l'utente ha scelto cosa fare dei suoi movimenti; prima si salva uno snapshot per «Annulla». */
    fun deleteAccount(a: Account, mode: it.spese.domain.usecase.AccountDeletion, target: Long?) = viewModelScope.launch {
        try {
            val n = deleteUc(a.id, mode, target)
            messenger.show(when {
                n == 0 -> "Conto «${a.name}» eliminato"
                mode == it.spese.domain.usecase.AccountDeletion.MOVE_TO_ACCOUNT -> "Conto «${a.name}» eliminato: $n movimenti spostati"
                mode == it.spese.domain.usecase.AccountDeletion.TO_REVIEW -> "Conto «${a.name}» eliminato: $n movimenti senza conto, in Verifica"
                else -> "Conto «${a.name}» eliminato con $n movimenti. Per tornare indietro: menu ⋮ › Annulla l'ultima operazione"
            })
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}

@Composable
fun AccountsScreen(onBack: () -> Unit, vm: AccountsViewModel = hiltViewModel()) {
    val d by vm.data.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }
    Scaffold(topBar = { SubTopBar("Conti", onBack) }, floatingActionButton = { ExtendedFloatingActionButton(onClick = { edit = Account(0, "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Conto") }) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Text("Rinomina un conto per cambiarne il nome; dando lo stesso nome a due conti li unisci. Il saldo iniziale serve a calcolare il saldo reale e le proiezioni.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (d.unassigned.isNotEmpty()) item { Text("${d.unassigned.size} ${if (d.unassigned.size == 1) "movimento è" else "movimenti sono"} senza conto: li trovi in Movimenti › Verifica e restano fuori da saldi, entrate e spese finché non scegli il conto.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            items(d.accounts, key = { it.id }) { a ->
                val txs = d.transactions.filter { it.accountId == a.id }
                val balance = a.openingBalance + txs.fold(BigDecimal.ZERO) { s, t -> s + t.amount }
                ListRow(a.name + if (a.archived) " (archiviato)" else "", subtitle = "${a.type.label} · ${txs.size} movimenti" + (if (txs.isNotEmpty()) " · ${Dates.fmt(txs.minOf { it.bookingDate })} → ${Dates.fmt(txs.maxOf { it.bookingDate })}" else ""),
                    trailing = { MoneyText(balance, decimals = false) }, onClick = { edit = a })
            }
        }
    }
    edit?.let { a ->
        var name by remember { mutableStateOf(a.name) }; var type by remember { mutableStateOf(a.type) }
        var opening by remember { mutableStateOf(if (a.openingBalance.signum() != 0) a.openingBalance.toPlainString().replace('.', ',') else "") }
        var archived by remember { mutableStateOf(a.archived) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (a.id == 0L) "Nuovo conto" else "Modifica conto") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                DropdownField("Tipo", type, AccountType.entries, { it.label }, { type = it })
                AmountField(opening, { opening = it }, "Saldo iniziale")
                LabeledSwitch("Archiviato", archived, { archived = it }, supporting = "Nascosto nelle nuove scelte")
                if (a.id != 0L) TextButton(onClick = { deleting = a; edit = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina il conto…") }
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.save(a.copy(type = type, openingBalance = parseAmountOrNull(opening) ?: BigDecimal.ZERO, archived = archived), name.trim()); edit = null }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Annulla") } })
    }
    deleting?.let { a -> DeleteAccountDialog(a, d.accounts.filter { it.id != a.id && !it.archived }, vm, { deleting = null }) }
}

/**
 * Eliminazione guidata di un conto. Nessuna scelta è preselezionata: se il conto ha movimenti, si decide esplicitamente se spostarli,
 * lasciarli senza conto (in Verifica, fuori dalla contabilità) o cancellarli (solo scrivendo il nome del conto).
 */
@Composable
private fun DeleteAccountDialog(a: Account, others: List<Account>, vm: AccountsViewModel, onDismiss: () -> Unit) {
    var impact by remember { mutableStateOf<it.spese.domain.usecase.AccountImpact?>(null) }
    LaunchedEffect(a.id) { impact = vm.impact(a.id) }
    var choice by remember { mutableStateOf<it.spese.domain.usecase.AccountDeletion?>(null) }
    var target by remember { mutableStateOf<Account?>(null) }
    var typed by remember { mutableStateOf("") }
    val i = impact
    val hasMovements = (i?.movements ?: 0) > 0
    val ok = i != null && (!hasMovements ||
        (choice == it.spese.domain.usecase.AccountDeletion.MOVE_TO_ACCOUNT && target != null) ||
        choice == it.spese.domain.usecase.AccountDeletion.TO_REVIEW ||
        (choice == it.spese.domain.usecase.AccountDeletion.DELETE_MOVEMENTS && typed.trim().equals(a.name, ignoreCase = true)))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Eliminare «${a.name}»?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i == null) { Text("Controllo cosa c'è nel conto…"); return@Column }
                if (!hasMovements) Text("Il conto non ha movimenti.")
                else {
                    Text("Il conto ha ${i.movements} movimenti (entrate ${Money.eur0(i.income)}, uscite ${Money.eur0(i.expense)}). Cosa ne facciamo? Nessuno viene parcheggiato in silenzio: scegli tu.", style = MaterialTheme.typography.bodyMedium)
                    DeletionOption(choice == it.spese.domain.usecase.AccountDeletion.MOVE_TO_ACCOUNT, "Sposta i movimenti su un altro conto", if (others.isEmpty()) "Non c'è un altro conto su cui spostarli" else "Restano nella contabilità, sul conto scelto", others.isNotEmpty()) { choice = it.spese.domain.usecase.AccountDeletion.MOVE_TO_ACCOUNT }
                    if (choice == it.spese.domain.usecase.AccountDeletion.MOVE_TO_ACCOUNT) DropdownField("Sposta su", target, others, { it.name }, { t -> target = t }, nullLabel = "Scegli il conto")
                    DeletionOption(choice == it.spese.domain.usecase.AccountDeletion.TO_REVIEW, "Lasciali senza conto, in Verifica", "Escono da saldi, entrate e spese finché non scegli un conto per ciascuno", true) { choice = it.spese.domain.usecase.AccountDeletion.TO_REVIEW }
                    DeletionOption(choice == it.spese.domain.usecase.AccountDeletion.DELETE_MOVEMENTS, "Elimina anche i movimenti", "Si cancellano per sempre (salvo «Annulla l'ultima operazione»)", true) { choice = it.spese.domain.usecase.AccountDeletion.DELETE_MOVEMENTS }
                    if (choice == it.spese.domain.usecase.AccountDeletion.DELETE_MOVEMENTS) OutlinedTextField(typed, { t -> typed = t }, label = { Text("Per confermare scrivi: ${a.name}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val notes = listOfNotNull(
                    i.recurringRules.takeIf { n -> n > 0 }?.let { n -> "$n ricorrenze" }, i.captureSources.takeIf { n -> n > 0 }?.let { n -> "$n impostazioni di notifica" }, i.valuations.takeIf { n -> n > 0 }?.let { n -> "$n valori inseriti" },
                )
                if (notes.isNotEmpty()) Text("Restano senza conto: ${notes.joinToString(", ")}. I nuovi movimenti automatici andranno in Verifica finché non scegli un conto.", style = MaterialTheme.typography.bodySmall)
                Text("Prima di procedere salvo una copia: puoi annullare dal menu ⋮ › Annulla l'ultima operazione.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { vm.deleteAccount(a, choice ?: it.spese.domain.usecase.AccountDeletion.TO_REVIEW, target?.id); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (choice == it.spese.domain.usecase.AccountDeletion.DELETE_MOVEMENTS) "Elimina conto e movimenti" else "Elimina il conto") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun DeletionOption(selected: Boolean, title: String, subtitle: String, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onSelect, enabled = enabled)
        Column(Modifier.padding(top = 12.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
