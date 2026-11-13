package it.spese.feature.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import it.spese.core.designsystem.component.*
import it.spese.domain.importing.ColumnMapping

private val MIME = arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

@Composable
fun ImportScreen(onBack: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.pick(uris) }
    Scaffold(topBar = { SubTopBar("Importa estratto conto", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                st.busy && st.queue.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                st.queue.isEmpty() && !st.finished -> EmptyState(Icons.Default.UploadFile, "Scegli i file da importare", "Formati supportati: Excel (.xlsx), CSV e TXT, anche più file insieme. Riconosco le intestazioni delle principali banche; prima di importare vedrai un'anteprima e potrai correggere le colonne. I movimenti già presenti non vengono duplicati.") {
                    Button(onClick = { picker.launch(MIME) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Scegli file…") }
                }
                st.finished -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Riepilogo", style = MaterialTheme.typography.titleLarge)
                    st.log.forEach { Text("• $it") }
                    Text("Puoi annullare l'ultima importazione dal menu Dati.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onBack) { Text("Fine") }; OutlinedButton(onClick = { vm.reset(); picker.launch(MIME) }) { Text("Importa altri file") } }
                }
                else -> Configure(st, data, vm)
            }
        }
    }
}

@Composable
private fun Configure(st: ImportState, data: it.spese.domain.repository.AppData, vm: ImportViewModel) {
    val table = st.table ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("File ${st.index + 1} di ${st.queue.size}: ${st.fileName}", style = MaterialTheme.typography.titleMedium) }
        if (st.queue[st.index].size > 1) item { DropdownField("Foglio", table, st.queue[st.index], { it.sheetName }, { t -> vm.selectSheet(st.queue[st.index].indexOf(t)) }) }
        item {
            SectionCard(title = "Conto di destinazione") {
                DropdownField("Conto esistente", data.accounts.firstOrNull { it.name.equals(st.accountName, true) }, data.accounts, { it.name }, { vm.setAccount(it.name) })
                OutlinedTextField(st.accountName, vm::setAccount, label = { Text("…oppure nome di un nuovo conto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            SectionCard(title = if (st.autoDetected) "Colonne riconosciute ✓" else "Associa le colonne") {
                val maxRow = minOf(table.rows.size - 1, 59)
                DropdownField("Riga delle intestazioni", st.headerRow, (0..maxRow).toList(), { r -> "Riga ${r + 1}: " + table.rows[r].filterNotNull().joinToString(" | ").take(40) }, vm::setHeaderRow)
                val cols = (0 until st.columns).toList()
                @Composable fun pick(label: String, cur: Int, set: (Int) -> ColumnMapping, optional: Boolean) =
                    DropdownField(label, cur.takeIf { it >= 0 }, cols, { st.columnTitle(it) }, { vm.setMapping(set(it)) }, nullLabel = if (optional) "— nessuna —" else "— scegli —", onNull = { vm.setMapping(set(-1)) })
                val m = st.mapping
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pick("Data contabile *", m.date, { m.copy(date = it) }, false)
                    pick("Data valuta", m.valueDate, { m.copy(valueDate = it) }, true)
                    pick("Descrizione *", m.description, { m.copy(description = it) }, false)
                    pick("Importo (con segno)", m.amount, { m.copy(amount = it, debit = if (it >= 0) -1 else m.debit, credit = if (it >= 0) -1 else m.credit) }, true)
                    if (m.amount < 0) { pick("Dare / Uscite", m.debit, { m.copy(debit = it) }, true); pick("Avere / Entrate", m.credit, { m.copy(credit = it) }, true) }
                }
            }
        }
        st.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        st.preview?.let { p ->
            item {
                SectionCard(title = "Anteprima") {
                    Text("${p.newCount} nuovi · ${p.duplicateCount} già presenti" + (if (p.skipped.isNotEmpty()) " · ${p.skipped.size} righe scartate" else ""), fontWeight = FontWeight.Medium)
                    p.rows.take(30).forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.row.text.ifBlank { "(senza descrizione)" }, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text(Dates.fmt(r.row.date) + if (r.duplicate) " · già presente" else "", style = MaterialTheme.typography.labelSmall, color = if (r.duplicate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(r.row.amount, muted = r.duplicate, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (p.rows.size > 30) Text("… e altri ${p.rows.size - 30} movimenti", style = MaterialTheme.typography.bodySmall)
                    if (p.skipped.isNotEmpty()) {
                        Text("Righe scartate:", style = MaterialTheme.typography.labelLarge)
                        p.skipped.take(5).forEach { Text("Riga ${it.rowNumber}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::confirm, enabled = !st.busy && (st.preview?.newCount ?: 0) > 0 && st.accountName.isNotBlank()) { Text("Importa ${st.preview?.newCount ?: 0} movimenti") }
                OutlinedButton(onClick = vm::skipFile, enabled = !st.busy) { Text("Salta file") }
            }
            if (st.preview != null && st.preview.newCount == 0 && st.preview.duplicateCount > 0) Text("Tutti i movimenti sono già presenti: nulla da importare.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
