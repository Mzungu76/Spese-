package it.spese.feature.transactions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.FileImage
import it.spese.app.common.SubTopBar
import it.spese.core.designsystem.component.*
import it.spese.core.model.TxKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditScreen(onBack: () -> Unit, vm: TransactionEditViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val docPath by vm.documentPath.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var ruleDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.done.collect { onBack() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.attachImage(it.toString()) } }

    Scaffold(topBar = { SubTopBar(if (vm.txId == 0L) "Nuovo movimento" else "Movimento", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        if (!f.loaded) { Box(Modifier.fillMaxSize().padding(pad)) { CircularProgressIndicator(Modifier.padding(32.dp)) }; return@Scaffold }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!f.isIncome, { vm.edit { it.copy(isIncome = false) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Uscita") }
                SegmentedButton(f.isIncome, { vm.edit { it.copy(isIncome = true) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Entrata") }
            }
            AmountField(f.amountText, { v -> vm.edit { it.copy(amountText = v.removePrefix("-")) } }, "Importo", Modifier.fillMaxWidth(), isError = f.error?.contains("importo") == true)
            OutlinedTextField(f.description, { v -> vm.edit { it.copy(description = v) } }, Modifier.fillMaxWidth(), label = { Text("Descrizione") }, isError = f.error?.contains("descrizione") == true)
            DropdownField("Conto", data.accounts.firstOrNull { it.id == f.accountId }, data.accounts.filter { !it.archived || it.id == f.accountId }, { it.name }, { a -> vm.edit { it.copy(accountId = a.id) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(f.date, { d -> vm.edit { it.copy(date = d, valueDate = if (it.valueDate == it.date) d else it.valueDate) } }, "Data contabile", Modifier.weight(1f))
                DateField(f.valueDate, { d -> vm.edit { it.copy(valueDate = d) } }, "Data valuta", Modifier.weight(1f))
            }
            it.spese.app.common.CategoryField("Categoria", data.categories.firstOrNull { it.id == f.categoryId }.takeIf { f.manualCategory }, data.categories,
                { c -> vm.edit { it.copy(categoryId = c.id, manualCategory = true) } }, nullLabel = if (vm.txId == 0L || !f.manualCategory) "Automatica (regole)" + (data.categoriesById[f.categoryId]?.let { " → ${it.name}" } ?: "") else "Automatica (regole)",
                onNull = { vm.edit { it.copy(manualCategory = false, categoryId = null) } })
            Text("La categoria scelta a mano prevale sempre sulle regole automatiche.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(f.merchant, { v -> vm.edit { it.copy(merchant = v) } }, Modifier.fillMaxWidth(), label = { Text("Esercente (facoltativo)") }, singleLine = true)
            OutlinedTextField(f.notes, { v -> vm.edit { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 2)
            if (f.origin.isNotEmpty()) Text("Origine: " + when (f.origin) { "IMPORT_FILE" -> "importato da file"; "OCR" -> "scansione documento"; "BACKUP" -> "backup"; else -> "inserimento manuale" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionCard(title = "Documento") {
                if (docPath != null) {
                    FileImage(docPath!!, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)))
                    OutlinedButton(onClick = vm::removeDocument) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Elimina foto") }
                } else OutlinedButton(onClick = { pick.launch("image/*") }) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text("Allega foto dalla galleria") }
            }
            f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, Modifier.fillMaxWidth()) { Text("Salva") }
            if (vm.txId != 0L) {
                OutlinedButton(onClick = { ruleDialog = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("Crea regola da questo movimento") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::duplicateIt, Modifier.weight(1f)) { Text("Duplica") }
                    OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina") }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Eliminare il movimento?", "L'operazione non può essere annullata.", "Elimina", vm::delete, { confirmDelete = false }, destructive = true)
    if (ruleDialog) {
        var kw by remember { mutableStateOf(vm.suggestedKeyword()) }
        var cat by remember { mutableStateOf(f.categoryId) }
        AlertDialog(onDismissRequest = { ruleDialog = false }, title = { Text("Crea regola") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tutti i movimenti che contengono la parola chiave andranno nella categoria scelta (la regola va in cima alla lista).", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(kw, { kw = it }, label = { Text("Parola chiave") }, singleLine = true)
                    it.spese.app.common.CategoryField("Categoria", data.categories.firstOrNull { it.id == cat }, data.categories, { cat = it.id })
                }
            },
            confirmButton = { TextButton(enabled = kw.isNotBlank() && cat != null, onClick = { vm.createRule(kw.trim(), cat!!); ruleDialog = false }) { Text("Crea regola") } },
            dismissButton = { TextButton(onClick = { ruleDialog = false }) { Text("Annulla") } })
    }
}
