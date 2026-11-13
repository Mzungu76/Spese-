package it.spese.feature.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.SubTopBar
import it.spese.core.common.DecimalStyle
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.importing.ColumnRole
import it.spese.domain.importing.DateOrder
import java.math.BigDecimal
import java.time.LocalDate

private val MIME = arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

private fun roleColor(r: ColumnRole): Color = when (r) {
    ColumnRole.DATE, ColumnRole.VALUE_DATE -> Color(0xFF4C8DFF)
    ColumnRole.DESCRIPTION, ColumnRole.EXTRA -> Color(0xFF34D399)
    ColumnRole.AMOUNT, ColumnRole.DEBIT, ColumnRole.CREDIT -> Color(0xFFFFB020)
    ColumnRole.SIGN -> Color(0xFFC792F0)
    ColumnRole.IGNORE -> Color(0xFF6B7280)
}

private fun fmtCell(v: Any?): String = when (v) { null -> ""; is LocalDate -> Dates.fmt(v); is BigDecimal -> v.stripTrailingZeros().toPlainString(); else -> v.toString().trim() }

@Composable
fun ImportScreen(onBack: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.pick(uris) }
    Scaffold(topBar = { SubTopBar("Importa estratto conto", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (st.step == ImportStep.COLUMNS || st.step == ImportStep.REVIEW) Stepper(st.step, st.index, st.queue.size, st.fileName)
            Box(Modifier.fillMaxSize()) {
                when (st.step) {
                    ImportStep.PICK -> if (st.busy) CircularProgressIndicator(Modifier.align(Alignment.Center)) else PickStep { picker.launch(MIME) }
                    ImportStep.COLUMNS -> ColumnsStep(st, vm)
                    ImportStep.REVIEW -> ReviewStep(st, vm)
                    ImportStep.DONE -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Riepilogo", style = MaterialTheme.typography.headlineSmall)
                        st.log.forEach { Text("• $it") }
                        Text("Puoi annullare l'ultima importazione dal menu ⋮ della Home.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onBack) { Text("Fine") }; OutlinedButton(onClick = { vm.reset(); picker.launch(MIME) }) { Text("Importa altri file") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(step: ImportStep, index: Int, total: Int, file: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1 File", "2 Colonne", "3 Controllo").forEachIndexed { i, l ->
                val on = (step == ImportStep.COLUMNS && i == 1) || (step == ImportStep.REVIEW && i == 2)
                val done = (i == 0) || (step == ImportStep.REVIEW && i == 1)
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(if (on || done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Text((if (total > 1) "File ${index + 1} di $total · " else "") + file + " · " + if (step == ImportStep.COLUMNS) "Controlla le colonne" else "Controlla e importa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickStep(onPick: () -> Unit) = EmptyState(Icons.Default.UploadFile, "Scegli i file da importare",
    "Excel (.xlsx), CSV o TXT, anche più file insieme. Guardo il contenuto per capire quali colonne sono date, importi e descrizioni, e te lo faccio verificare prima di importare. I formati che confermi vengono ricordati.") {
    Button(onClick = onPick) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Scegli file…") }
}

// ---------- Passo 2: colonne ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnsStep(st: ImportState, vm: ImportViewModel) {
    val table = st.table ?: return
    val a = st.analysis
    val m = st.mapping
    val fin = SpeseTheme.finance
    var advanced by remember(st.index, st.sheet) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (st.queue[st.index].size > 1) item { DropdownField("Foglio", table, st.queue[st.index], { it.sheetName }, { t -> vm.selectSheet(st.queue[st.index].indexOf(t)) }, Modifier.padding(horizontal = 24.dp)) }
        item {
            val conf = a?.confidence ?: 0.0
            val (label, color) = when {
                st.profileName != null -> "Formato ricordato: ${st.profileName}" to fin.positive
                !m.isUsable -> "Non ho riconosciuto tutto: scegli le colonne qui sotto" to fin.negative
                conf >= 0.75 -> "Formato riconosciuto" to fin.positive
                else -> "Riconosciuto, ma controlla" to fin.warning
            }
            Column(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (m.isUsable) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = color)
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                val amountCol = if (m.amount >= 0) m.amount else if (m.debit >= 0) m.debit else m.credit
                listOf(Triple("Data", m.date, m.date >= 0), Triple("Descrizione", m.description, m.description >= 0),
                    Triple(if (m.amount < 0 && (m.debit >= 0 || m.credit >= 0)) "Importo (dare/avere)" else "Importo", amountCol, amountCol >= 0)).forEach { (n, col, ok) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (ok) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(18.dp), tint = if (ok) fin.positive else fin.negative)
                        Text(n, Modifier.weight(1f)); Text(if (ok) "colonna " + colLetter(col) else "da scegliere", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (a != null && st.profileName == null) a.warnings.forEach { w ->
            item { Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer); Text(w, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer) } }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tocca il nome sopra ogni colonna per cambiarne il significato", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleSmall)
                DataPreview(st, vm)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("I dati iniziano dalla riga ${st.firstDataRow + 1}", style = MaterialTheme.typography.bodyLarge); Text(if (st.headerRow >= 0) "intestazioni alla riga ${st.headerRow + 1}" else "nessuna riga di intestazioni", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledTonalIconButton(onClick = { vm.setFirstDataRow(st.firstDataRow) }) { Icon(Icons.Default.Remove, "Riga precedente") }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { vm.setFirstDataRow(st.firstDataRow + 2) }) { Icon(Icons.Default.Add, "Riga successiva") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) { Text(if (advanced) "Nascondi le opzioni avanzate" else "Opzioni avanzate (date, decimali, segno)"); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
                if (advanced) {
                    DropdownField("Formato delle date", st.options.dateOrder, DateOrder.entries, { when (it) { DateOrder.AUTO -> "Automatico"; DateOrder.DMY -> "Giorno/Mese/Anno (italiano)"; DateOrder.MDY -> "Mese/Giorno/Anno (americano)" } }, { vm.setOptions(st.options.copy(dateOrder = it)) })
                    DropdownField("Separatore decimale", st.options.decimal, DecimalStyle.entries, { when (it) { DecimalStyle.AUTO -> "Automatico"; DecimalStyle.COMMA -> "Virgola (1.234,56)"; DecimalStyle.DOT -> "Punto (1,234.56)" } }, { vm.setOptions(st.options.copy(decimal = it)) })
                    LabeledSwitch("Inverti il segno degli importi", st.options.invertSign, { vm.setOptions(st.options.copy(invertSign = it)) }, supporting = "Per le carte di credito in cui le spese sono numeri positivi")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ignora le ultime ${st.options.skipLast} righe (totali, note)", Modifier.weight(1f))
                        FilledTonalIconButton(onClick = { vm.setOptions(st.options.copy(skipLast = (st.options.skipLast - 1).coerceAtLeast(0))) }) { Icon(Icons.Default.Remove, "Meno") }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(onClick = { vm.setOptions(st.options.copy(skipLast = st.options.skipLast + 1)) }) { Icon(Icons.Default.Add, "Più") }
                    }
                    OutlinedButton(onClick = vm::reanalyze) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Rifai il riconoscimento automatico") }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.preview?.let { p -> Text("Con queste colonne leggo ${p.rows.size} movimenti validi" + if (p.skipped.isNotEmpty()) " (${p.skipped.size} righe scartate)" else "", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::toReview, Modifier.fillMaxWidth().height(52.dp), enabled = m.isUsable && (st.preview?.rows?.isNotEmpty() == true)) { Text("Avanti: controlla i movimenti") }
                OutlinedButton(onClick = vm::skipFile, Modifier.fillMaxWidth()) { Text("Salta questo file") }
            }
        }
    }
}

private fun colLetter(i: Int): String = if (i < 0) "?" else ('A' + i % 26).toString().let { if (i >= 26) "A$it" else it }

/** Tabella dei primi dati del file: sopra ogni colonna un selettore del ruolo, colorato per tipo. */
@Composable
private fun DataPreview(st: ImportState, vm: ImportViewModel) {
    val table = st.table ?: return
    val rows = table.rows.drop(st.firstDataRow).take(6)
    val titleRow = table.rows.getOrNull(st.headerRow)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        st.roles.forEachIndexed { c, role ->
            val color = roleColor(role)
            var open by remember { mutableStateOf(false) }
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Box {
                    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.22f)).clickableNoRipple { open = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(colLetter(c) + (fmtCell(titleRow?.getOrNull(c)).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(role.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                        }
                        Icon(Icons.Default.ArrowDropDown, null, tint = color)
                    }
                    DropdownMenu(open, { open = false }) { ColumnRole.entries.forEach { r -> DropdownMenuItem({ Text(r.label, color = roleColor(r)) }, { vm.setRole(c, r); open = false }) } }
                }
                rows.forEach { r -> Text(fmtCell(r.getOrNull(c)).ifEmpty { "—" }, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (role == ColumnRole.IGNORE) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

// ---------- Passo 3: controllo e importazione ----------
@Composable
private fun ReviewStep(st: ImportState, vm: ImportViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val p = st.preview
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conto di destinazione", style = MaterialTheme.typography.titleMedium)
                DropdownField("Conto esistente", data.accounts.firstOrNull { it.name.equals(st.accountName, true) }, data.accounts, { it.name }, { vm.setAccount(it.name) })
                OutlinedTextField(st.accountName, vm::setAccount, label = { Text("…oppure nome di un nuovo conto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        if (p != null) {
            item {
                val fresh = p.rows.filter { !it.duplicate }
                val inc = fresh.filter { it.row.amount.signum() > 0 }.fold(BigDecimal.ZERO) { a, r -> a + r.row.amount }
                val exp = fresh.filter { it.row.amount.signum() < 0 }.fold(BigDecimal.ZERO) { a, r -> a + r.row.amount }
                Column(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Cosa importerò")
                    Text("${p.newCount} movimenti nuovi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (p.duplicateCount > 0) Text("${p.duplicateCount} già presenti (non li duplico)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fresh.isNotEmpty()) {
                        Text("Dal ${Dates.fmt(fresh.minOf { it.row.date })} al ${Dates.fmt(fresh.maxOf { it.row.date })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { Column { Eyebrow("Entrate"); MoneyText(inc) }; Column { Eyebrow("Uscite"); MoneyText(exp) } }
                        Text("Confronta questi totali con quelli del tuo estratto conto: se non tornano, torna indietro e correggi le colonne.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("Anteprima", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium) }
            items(p.rows.take(25).size) { i ->
                val r = p.rows[i]
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.row.text.ifBlank { "(senza descrizione)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(Dates.fmt(r.row.date) + if (r.duplicate) " · già presente" else "", style = MaterialTheme.typography.bodySmall, color = if (r.duplicate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoneyText(r.row.amount, muted = r.duplicate)
                }
            }
            if (p.rows.size > 25) item { Text("… e altri ${p.rows.size - 25} movimenti", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall) }
            if (p.skipped.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${p.skipped.size} righe scartate", style = MaterialTheme.typography.titleSmall)
                    p.skipped.take(6).forEach { Text("Riga ${it.rowNumber}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledSwitch("Ricorda questo formato", st.remember, vm::setRemember, supporting = "La prossima volta riconosco subito le colonne di questa banca")
                if (st.remember) OutlinedTextField(st.rememberName, vm::setRememberName, label = { Text("Nome del formato (es. Intesa, Revolut)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::confirm, Modifier.fillMaxWidth().height(52.dp), enabled = !st.busy && (p?.newCount ?: 0) > 0 && st.accountName.isNotBlank()) { Text("Importa ${p?.newCount ?: 0} movimenti") }
                OutlinedButton(onClick = vm::toColumns, Modifier.fillMaxWidth()) { Text("Indietro: correggi le colonne") }
            }
        }
    }
}
