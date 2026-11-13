package it.spese.feature.accounts

import androidx.compose.foundation.layout.*
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
class AccountsViewModel @Inject constructor(val data: StateFlow<AppData>, private val repo: AccountRepository, private val messenger: UiMessenger) : ViewModel() {
    fun save(a: Account, newName: String) = viewModelScope.launch {
        try {
            if (a.id == 0L) repo.getOrCreate(newName).let { repo.update(it.copy(type = a.type, openingBalance = a.openingBalance)) }
            else { repo.update(a.copy(name = a.name)); if (newName.isNotBlank() && newName != a.name) repo.renameOrMerge(a.id, newName) }
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    fun delete(a: Account) = viewModelScope.launch { messenger.show(if (repo.deleteIfEmpty(a.id)) "Conto eliminato" else "Il conto ha movimenti: uniscilo a un altro conto rinominandolo") }
}

@Composable
fun AccountsScreen(onBack: () -> Unit, vm: AccountsViewModel = hiltViewModel()) {
    val d by vm.data.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Account?>(null) }
    Scaffold(topBar = { SubTopBar("Conti", onBack) }, floatingActionButton = { ExtendedFloatingActionButton(onClick = { edit = Account(0, "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Conto") }) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Text("Rinomina un conto per cambiarne il nome; dando lo stesso nome a due conti li unisci. Il saldo iniziale serve a calcolare il saldo reale e le proiezioni.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (a.id == 0L) "Nuovo conto" else "Modifica conto") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                DropdownField("Tipo", type, AccountType.entries, { it.label }, { type = it })
                AmountField(opening, { opening = it }, "Saldo iniziale")
                LabeledSwitch("Archiviato", archived, { archived = it }, supporting = "Nascosto nelle nuove scelte")
                if (a.id != 0L) TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina (solo se vuoto)") }
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.save(a.copy(type = type, openingBalance = parseAmountOrNull(opening) ?: BigDecimal.ZERO, archived = archived), name.trim()); edit = null }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Annulla") } })
        if (confirmDelete) ConfirmDialog("Eliminare \"${a.name}\"?", "Un conto con movimenti non può essere eliminato.", "Elimina", { vm.delete(a); edit = null }, { confirmDelete = false }, destructive = true)
    }
}
