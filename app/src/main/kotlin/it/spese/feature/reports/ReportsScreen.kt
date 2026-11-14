package it.spese.feature.reports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.app.common.SearchField
import it.spese.app.common.Share
import it.spese.core.designsystem.component.*
import it.spese.core.model.TxKind
import it.spese.domain.report.*
import it.spese.domain.finance.SearchMatcher
import java.time.YearMonth

private fun iconFor(id: String?) = when (id) {
    "monthly" -> Icons.Default.CalendarMonth; "yearly" -> Icons.Default.EventNote; "categories" -> Icons.Default.PieChart; "trends" -> Icons.Default.Timeline
    "budgets" -> Icons.Default.Savings; "transactions" -> Icons.Default.ReceiptLong; "cash" -> Icons.Default.Payments; "accountant" -> Icons.Default.Work; else -> Icons.Default.Tune
}

/** Report PDF: modelli pronti e personalizzabili (periodo, conto, categorie, sezioni e opzioni), anteprima, salvataggio e condivisione. */
@Composable
fun ReportsScreen(onBack: () -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = st.editing) { vm.backToList() }
    if (st.editing) ReportEditor(st, vm) else ReportList(onBack, vm)
}

@Composable
private fun ReportList(onBack: () -> Unit, vm: ReportsViewModel) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Report PDF", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Scegli un modello e, se vuoi, personalizzalo prima di creare il PDF: periodo, conto, categorie, sezioni e opzioni. I modelli personalizzati si possono salvare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)) }
            item { SectionHeader("Modelli pronti", Modifier.padding(horizontal = 8.dp)) }
            items(ReportPresets.BUILT_IN, key = { it.id }) { p -> PresetCard(iconFor(p.id), p.name, p.description, Modifier.testTag("preset_${p.id}")) { vm.startPreset(p) } }
            if (saved.isNotEmpty()) {
                item { SectionHeader("I tuoi modelli", Modifier.padding(horizontal = 8.dp)) }
                items(saved, key = { it.id }) { r -> PresetCard(Icons.Default.Bookmark, r.name, r.spec.sections.joinToString(" · ") { it.label }, trailing = { IconButton(onClick = { vm.deleteSaved(r.id) }) { Icon(Icons.Default.Delete, "Elimina modello") } }) { vm.startSaved(r) } }
            }
            item { SectionHeader("Da zero", Modifier.padding(horizontal = 8.dp)) }
            item { PresetCard(Icons.Default.Tune, "Report personalizzato", "Scegli tu le sezioni, il periodo e le categorie.", Modifier.testTag("preset_blank")) { vm.startBlank() } }
        }
    }
}

@Composable
private fun PresetCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
            if (trailing != null) trailing() else Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportEditor(st: ReportsState, vm: ReportsViewModel) {
    val ctx = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val spec = st.spec
    var pickCategories by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::saveTo) }
    val months = remember(data.ledger.size) { data.ledger.map { YearMonth.from(it.bookingDate) }.distinct().sortedDescending().take(24) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(st.name, { vm.backToList() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Titolo e nota") {
                    OutlinedTextField(spec.title, { v -> vm.edit { it.copy(title = v) } }, label = { Text("Titolo (facoltativo)") }, placeholder = { Text(st.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(spec.note, { v -> vm.edit { it.copy(note = v) } }, label = { Text("Nota in testa al report (facoltativa)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            }
            item {
                SectionCard(title = "Periodo") {
                    ChipChoice(ReportPeriodKind.entries.map { it to it.label }, spec.period.kind, { k -> vm.edit { it.copy(period = it.period.copy(kind = k, month = if (k == ReportPeriodKind.MONTH) (it.period.month ?: months.firstOrNull()) else it.period.month)) } })
                    if (spec.period.kind == ReportPeriodKind.MONTH) ChipChoice(months.map { m -> m to it.spese.core.common.Dates.monthLabelLong(m) }, spec.period.month, { m -> vm.edit { it.copy(period = it.period.copy(month = m)) } })
                    if (spec.period.kind == ReportPeriodKind.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField(spec.period.from, { d -> vm.edit { it.copy(period = it.period.copy(from = d)) } }, "Dal", Modifier.weight(1f))
                        DateField(spec.period.to, { d -> vm.edit { it.copy(period = it.period.copy(to = d)) } }, "Al", Modifier.weight(1f))
                    }
                    Text("«Corrente» e «precedente» sono riferiti all'ultimo movimento registrato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Conto e categorie") {
                    val accs = data.accounts.filter { !it.archived }
                    DropdownField("Conto", accs.firstOrNull { it.id == spec.accountId }, accs, { it.name }, { a -> vm.edit { it.copy(accountId = a.id) } }, nullLabel = "Tutti i conti", onNull = { vm.edit { it.copy(accountId = null) } })
                    Text(if (spec.categoryIds.isEmpty()) "Categorie: tutte" else "Categorie: " + spec.categoryIds.mapNotNull { data.categoriesById[it]?.name }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickCategories = true }) { Text("Scegli categorie…") }
                        if (spec.categoryIds.isNotEmpty()) TextButton(onClick = { vm.edit { it.copy(categoryIds = emptySet()) } }) { Text("Tutte") }
                    }
                    Text("Le categorie scelte restringono categorie, esercenti, spese più alte, budget, tendenze ed elenco movimenti; il riepilogo resta sull'intero periodo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionsCard(spec, vm) }
            item {
                SectionCard(title = "Opzioni") {
                    LabeledSwitch("Grafici", spec.charts, { v -> vm.edit { it.copy(charts = v) } })
                    LabeledSwitch("Sottocategorie nel dettaglio", spec.subcategories, { v -> vm.edit { it.copy(subcategories = v) } })
                    LabeledSwitch("Pagina orizzontale", spec.landscape, { v -> vm.edit { it.copy(landscape = v) } }, supporting = "Utile per le tabelle larghe, come le tendenze")
                    LabeledSwitch("Includi i giroconti nell'elenco", spec.includeTransfers, { v -> vm.edit { it.copy(includeTransfers = v) } })
                    Text("Righe negli elenchi (esercenti, spese più alte, variazioni)", style = MaterialTheme.typography.labelLarge)
                    ChipChoice(listOf(5, 8, 10, 15, 20, 30).map { it to "$it" }, spec.topN, { n -> vm.edit { it.copy(topN = n) } })
                    if (ReportSection.TRANSACTIONS in spec.sections) {
                        Text("Elenco movimenti", style = MaterialTheme.typography.labelLarge)
                        ChipChoice(TxGrouping.entries.map { it to it.label }, spec.grouping, { g -> vm.edit { it.copy(grouping = g) } })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::generate, enabled = !st.busy && spec.sections.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("report_generate")) {
                        if (st.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PictureAsPdf, null)
                        Spacer(Modifier.width(8.dp)); Text(if (st.busy) "Creo il report…" else "Crea anteprima")
                    }
                    OutlinedButton(onClick = { saveDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text("Salva come mio modello…") }
                }
            }
            st.result?.let { r ->
                item {
                    SectionCard(title = if (r.empty) "Nessun movimento nel periodo" else "Il tuo report · ${r.pageCount} ${if (r.pageCount == 1) "pagina" else "pagine"}") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { create.launch(r.fileName) }, modifier = Modifier.weight(1f).testTag("report_save")) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Salva PDF") }
                            OutlinedButton(onClick = { Share.shareBytes(ctx, r.fileName, "application/pdf", r.bytes, "Condividi il report") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Condividi") }
                        }
                        if (r.pages.isEmpty()) Text("L'anteprima non è disponibile su questo telefono, ma il PDF è pronto da salvare o condividere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (r.pageCount > r.pages.size) Text("Anteprima delle prime ${r.pages.size} pagine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(r.pages.size) { i ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                        Image(r.pages[i].asImageBitmap(), "Pagina ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
            }
        }
    }
    if (pickCategories) CategoriesPicker(data.categories.filter { it.parentId == null && it.active && it.kind != TxKind.TRASFERIMENTO }, spec.categoryIds, { pickCategories = false }) { ids -> vm.edit { it.copy(categoryIds = ids) }; pickCategories = false }
    if (saveDialog) {
        var name by remember { mutableStateOf(if (st.sourceId != null && st.sourceId.length > 20) st.name else "") }
        AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("Salva come mio modello") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nome del modello") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.saveAs(name); saveDialog = false }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Annulla") } })
    }
}

@Composable
private fun SectionsCard(spec: ReportSpec, vm: ReportsViewModel) {
    SectionCard(title = "Sezioni (nell'ordine del report)") {
        val on = spec.sections
        on.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(true, { vm.edit { it.copy(sections = it.sections - s) } })
                Column(Modifier.weight(1f)) { Text(s.label, style = MaterialTheme.typography.titleSmall); Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { vm.edit { it.copy(sections = it.sections.toMutableList().also { l -> l.removeAt(i); l.add(i - 1, s) }) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Sposta su") }
                IconButton(onClick = { vm.edit { it.copy(sections = it.sections.toMutableList().also { l -> l.removeAt(i); l.add(i + 1, s) }) } }, enabled = i < on.lastIndex) { Icon(Icons.Default.ArrowDownward, "Sposta giù") }
            }
        }
        val off = ReportSection.entries.filter { it !in on }
        if (off.isNotEmpty()) Text("Aggiungi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        off.forEach { s ->
            Row(Modifier.clickable { vm.edit { it.copy(sections = it.sections + s) } }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(false, { vm.edit { it.copy(sections = it.sections + s) } })
                Column(Modifier.weight(1f)) { Text(s.label, style = MaterialTheme.typography.titleSmall); Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun CategoriesPicker(categories: List<it.spese.core.model.Category>, selected: Set<Long>, onDismiss: () -> Unit, onDone: (Set<Long>) -> Unit) {
    var sel by remember { mutableStateOf(selected) }
    var q by remember { mutableStateOf("") }
    val list = remember(q, categories) { categories.filter { SearchMatcher.matches(q, it.name) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Scegli le categorie") },
        text = {
            Column {
                SearchField(q, { q = it }, "Cerca categoria…", Modifier.padding(bottom = 8.dp), debounceMs = 0)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { sel = if (c.id in sel) sel - c.id else sel + c.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(c.id in sel, null); CategoryAvatar(c, size = 32); Text(c.name, Modifier.weight(1f), maxLines = 1); Text(c.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(sel) }) { Text("Fatto") } },
        dismissButton = { Row { TextButton(onClick = { sel = emptySet() }) { Text("Nessuna") }; TextButton(onClick = onDismiss) { Text("Annulla") } } })
}
