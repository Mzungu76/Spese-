package it.spese.feature.transactions

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.*
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.domain.finance.Period
import it.spese.domain.finance.SearchMatcher
import it.spese.app.common.SearchField
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    menu: DataMenu, onOpen: (Long) -> Unit, onAdd: () -> Unit, onOpenClassify: () -> Unit, bottomPadding: PaddingValues = PaddingValues(),
    vm: TransactionsViewModel = hiltViewModel(), filterVm: FilterViewModel = hiltViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var pickCategory by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf<Long?>(null) }
    var bulkAll by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    val selecting = st.selection.isNotEmpty()

    var searching by remember { mutableStateOf(false) }
    val live by filterVm.filter.collectAsStateWithLifecycle()
    val searchReq by vm.searchRequested.collectAsStateWithLifecycle()
    LaunchedEffect(searchReq) { if (searchReq) { searching = true; vm.consumeSearch() } }
    var moreMenu by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var splitTarget by remember { mutableStateOf<Transaction?>(null) }
    val unclassified = remember(st.data.transactions) { st.data.transactions.count { !it.manualCategory && it.ruleId == null && it.kind != TxKind.TRASFERIMENTO } }

    Column(Modifier.fillMaxSize()) {
        if (selecting) TopAppBar(
            title = { Text("${st.selection.size} selezionati") },
            navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Chiudi selezione") } },
            actions = {
                if (st.selection.size == 1) IconButton(onClick = { st.data.ledger.firstOrNull { it.id == st.selection.first() }?.let { splitTarget = it }; vm.clearSelection() }) { Icon(Icons.Default.CallSplit, "Dividi su più categorie") }
                IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Seleziona tutti") }
                IconButton(onClick = { pickCategory = { vm.assignToSelection(it) } }) { Icon(Icons.AutoMirrored.Filled.Label, "Cambia categoria") }
                IconButton(onClick = vm::duplicateSelection) { Icon(Icons.Default.ContentCopy, "Duplica") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        ) else LargeHeader("Movimenti", actions = {
            RoundIconButton(Icons.Default.Add, "Aggiungi", onAdd, Modifier.testTag("fab_add"), primary = true)
            RoundIconButton(Icons.Default.Search, "Cerca", { searching = !searching }, badge = live.query.isNotBlank())
            RoundIconButton(Icons.Default.Tune, "Filtri", { filterSheet = true }, badge = st.filter.activeCount > 0)
            Box {
                RoundIconButton(Icons.Default.MoreHoriz, "Altre azioni", { moreMenu = true })
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem({ Text("Assegna categoria a tutti i filtrati") }, { moreMenu = false; bulkAll = true; pickCategory = { confirmBulk = it } }, enabled = st.items.isNotEmpty())
                    DropdownMenuItem({ Text("Esporta CSV") }, { moreMenu = false; Share.shareBytes(ctx, "movimenti-${stamp()}.csv", "text/csv", vm.csvBytes(), "Esporta CSV") }, enabled = st.items.isNotEmpty())
                    DropdownMenuItem({ Text("Esporta Excel") }, { moreMenu = false; Share.shareBytes(ctx, "movimenti-${stamp()}.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", vm.xlsxBytes(), "Esporta Excel") }, enabled = st.items.isNotEmpty())
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Analisi e grafici") }, { moreMenu = false; menu.onAnalytics() })
                    DropdownMenuItem({ Text("Backup e impostazioni") }, { moreMenu = false; menu.onSettings() })
                }
            }
        })
        if (searching || live.query.isNotEmpty()) Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
            SearchField(live.query, vm::setQuery, "Cerca esercente, nota, categoria, importo…", autoFocus = searching && live.query.isEmpty())
            if (live.query.isNotBlank()) Text(if (live.period != Period.All) "Risultati su tutto lo storico, non solo sul periodo scelto" else "Risultati su tutto lo storico",
                Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        FilterPills()
        if (!selecting) PillSegmented(listOf(false to "Tutti", true to if (st.review.isNotEmpty()) "Verifica · ${st.review.size}" else "Verifica"), st.reviewMode, vm::setReviewMode, Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("review_toggle"))
        if (!st.reviewMode) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${st.items.size} movimenti", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            MoneyText(st.income, signed = true, decimals = false, style = MaterialTheme.typography.bodyLarge)
            MoneyText(st.expense, decimals = false, colored = false, style = MaterialTheme.typography.bodyLarge)
        }
        if (unclassified > 0 && !selecting && !st.reviewMode) Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onOpenClassify).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Text("$unclassified", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            Text("Da classificare", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Icon(Icons.Default.ChevronRight, null)
        }
        if (st.reviewMode) ReviewSection(st, vm, onOpen, { id -> pickCategory = { cid -> vm.reassign(id, cid) } }, { splitTarget = it }, bottomPadding)
        else if (st.items.isEmpty()) {
            EmptyState(Icons.Default.ReceiptLong, if (st.data.transactions.isEmpty()) "Nessun movimento" else "Nessun risultato", if (st.data.transactions.isEmpty()) "Tocca il + per aggiungere un movimento o importare un estratto conto." else "Nessun movimento con questi filtri.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp)) {
                val byDay = st.sort == SortKey.DATE
                var lastDay: java.time.LocalDate? = null
                st.items.forEach { x ->
                    if (byDay && x.bookingDate != lastDay) {
                        lastDay = x.bookingDate
                        val dayTotal = st.items.asSequence().filter { it.bookingDate == x.bookingDate && it.kind != TxKind.TRASFERIMENTO }.fold(java.math.BigDecimal.ZERO) { a, t -> a + t.amount }
                        stickyHeader(key = "d${x.bookingDate}") {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)) {
                                Text(x.bookingDate.dayLabel(st.today).uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                if (dayTotal.signum() != 0) MoneyText(dayTotal, style = MaterialTheme.typography.labelMedium, colored = false)
                            }
                        }
                    }
                    item(key = x.id) {
                        val cat = st.data.categoriesById[x.categoryId]
                        val picked = x.id in st.selection
                        Row(Modifier.fillMaxWidth().background(if (picked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(onClick = { if (selecting) vm.toggle(x.id) else onOpen(x.id) }, onLongClick = { vm.toggle(x.id) })
                            .padding(horizontal = 24.dp).heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (picked) Icon(Icons.Default.CheckCircle, "Selezionato", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) else CategoryAvatar(cat, size = 48)
                            Column(Modifier.weight(1f)) {
                                Text(x.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(buildString {
                                    val parts = st.data.splits[x.id].orEmpty()
                                    if (parts.isNotEmpty()) append("Diviso in ").append(parts.size).append(": ").append(parts.joinToString(" + ") { (st.data.categoriesById[it.categoryId]?.name ?: "?") }) else append(cat?.name ?: "Senza categoria")
                                    if (x.manualCategory && parts.isEmpty()) append(" · ✎")
                                    if (x.origin == it.spese.core.model.TxOrigin.NOTIFICATION) append(" · da notifica")
                                    if (st.data.accounts.size > 1) append(" · ").append(st.data.accountName(x.accountId))
                                    if (!byDay) append(" · ").append(it.spese.core.common.Dates.fmt(x.bookingDate))
                                    if (x.documentId != null) append(" · 📎")
                                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            MoneyText(x.amount, signed = true, colored = x.amount.signum() > 0 && x.kind == TxKind.ENTRATA, muted = x.kind == TxKind.TRASFERIMENTO, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
    if (filterSheet) FilterSheet(st.filter, st.data, filterVm::update, filterVm::reset) { filterSheet = false }
    splitTarget?.let { t -> SplitDialog(t, st.data.categories.filter { it.active }, st.data.splits[t.id].orEmpty(), { parts -> vm.split(t.id, parts) }, { vm.split(t.id, emptyList()) }, { splitTarget = null }) }

    pickCategory?.let { cb ->
        CategoryPickerDialog(st.data.categories, onDismiss = { pickCategory = null; bulkAll = false }) { cb(it.id); pickCategory = null }
    }
    confirmBulk?.let { id ->
        ConfirmDialog("Assegnare la categoria?", "Assegnare \"${st.data.categoryName(id)}\" a tutti i ${st.items.size} movimenti filtrati? L'assegnazione manuale prevale sulle regole.", "Assegna", { vm.assignToAllFiltered(id) }, { confirmBulk = null; bulkAll = false })
    }
    if (confirmDelete) ConfirmDialog("Eliminare ${st.selection.size} movimenti?", "L'operazione non può essere annullata.", "Elimina", { vm.deleteSelection() }, { confirmDelete = false }, destructive = true)
}

@Composable
fun CategoryPickerDialog(categories: List<Category>, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, categories) { categories.filter { it.active && SearchMatcher.matches(q, it.name, it.kind.label) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Scegli la categoria") }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        text = {
            Column {
                SearchField(q, { q = it }, "Cerca categoria…", Modifier.padding(bottom = 8.dp), debounceMs = 0)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (list.isEmpty()) item { Text("Nessuna categoria trovata.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.id }) { c -> ListRow(c.name, subtitle = c.kind.label, leading = { CategoryAvatar(c, size = 32) }, onClick = { onPick(c) }) }
                }
            }
        })
}

/** Sezione Verifica: i movimenti la cui associazione non è certa, con il motivo e le azioni per chiuderli. */
@Composable
private fun ReviewSection(st: TransactionsState, vm: TransactionsViewModel, onOpen: (Long) -> Unit, onPickCategory: (Long) -> Unit, onSplit: (Transaction) -> Unit, bottomPadding: PaddingValues) {
    if (st.review.isEmpty()) { EmptyState(Icons.Default.CheckCircle, "Tutto a posto", "Nessun movimento da controllare: divisioni automatiche, movimenti da notifica, regole con soglia e importi insoliti compaiono qui."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${st.review.size} da controllare", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::confirmAllReview) { Text("Conferma tutti") }
            }
        }
        items(st.review, key = { "rv-${it.tx.id}" }) { r ->
            val x = r.tx
            val cat = st.data.categoriesById[x.categoryId]
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(x.id) }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CategoryAvatar(if (r.splits.isNotEmpty()) null else cat, size = 44)
                        Column(Modifier.weight(1f)) {
                            Text(x.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(it.spese.core.common.Dates.fmt(x.bookingDate) + " · " + (if (r.splits.isNotEmpty()) "diviso in ${r.splits.size}" else cat?.name ?: "Senza categoria"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        MoneyText(x.amount, signed = true, colored = x.amount.signum() > 0 && x.kind == TxKind.ENTRATA, style = MaterialTheme.typography.titleMedium)
                    }
                    r.notes.forEach { n ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp).padding(top = 2.dp), tint = it.spese.core.designsystem.theme.SpeseTheme.finance.warning)
                            Text(n.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { vm.confirm(listOf(x.id)) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Conferma") }
                        TextButton(onClick = { onPickCategory(x.id) }) { Text("Categoria") }
                        TextButton(onClick = { onSplit(x) }) { Text("Dividi") }
                    }
                }
            }
        }
    }
}
