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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.*
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.Category
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    menu: DataMenu, onOpen: (Long) -> Unit, onAdd: () -> Unit, bottomPadding: PaddingValues = PaddingValues(),
    vm: TransactionsViewModel = hiltViewModel(),
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

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                title = { Text("${st.selection.size} selezionati") },
                navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Chiudi selezione") } },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Seleziona tutti") }
                    IconButton(onClick = { pickCategory = { vm.assignToSelection(it) } }) { Icon(Icons.AutoMirrored.Filled.Label, "Cambia categoria") }
                    IconButton(onClick = vm::duplicateSelection) { Icon(Icons.Default.ContentCopy, "Duplica") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
                },
            ) else TabTopBar("Movimenti", menu)
        },
        floatingActionButton = { if (!selecting) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Movimento") }, modifier = Modifier.padding(bottom = bottomPadding.calculateBottomPadding())) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(st.filter.query, vm::setQuery, Modifier.weight(1f), singleLine = true, placeholder = { Text("Cerca movimenti…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (st.filter.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, "Cancella ricerca") } })
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordina") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortKey.entries.forEach { k ->
                            DropdownMenuItem({ Text(k.label + if (st.sort == k) (if (st.descending) "  ▼" else "  ▲") else "") }, { vm.setSort(k); sortMenu = false })
                        }
                    }
                }
            }
            FilterHeader(Modifier.padding(bottom = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${st.items.size} movimenti", fontWeight = FontWeight.Medium)
                MoneyText(st.income, signed = true, decimals = false, style = MaterialTheme.typography.bodyMedium)
                MoneyText(st.expense, decimals = false, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                if (st.items.isNotEmpty()) {
                    TextButton(onClick = { bulkAll = true; pickCategory = { confirmBulk = it } }) { Text("Assegna a tutti") }
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Share, "Esporta") }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            DropdownMenuItem({ Text("Esporta CSV") }, { exportMenu = false; Share.shareBytes(ctx, "movimenti-${stamp()}.csv", "text/csv", vm.csvBytes(), "Esporta CSV") })
                            DropdownMenuItem({ Text("Esporta Excel") }, { exportMenu = false; Share.shareBytes(ctx, "movimenti-${stamp()}.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", vm.xlsxBytes(), "Esporta Excel") })
                        }
                    }
                }
            }
            if (st.items.isEmpty()) {
                EmptyState(Icons.Default.ReceiptLong, if (st.data.transactions.isEmpty()) "Nessun movimento" else "Nessun risultato", if (st.data.transactions.isEmpty()) "Aggiungi un movimento o importa un estratto conto." else "Nessun movimento con questi filtri.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 88.dp)) {
                    val byDay = st.sort == SortKey.DATE
                    var lastDay: java.time.LocalDate? = null
                    st.items.forEach { x ->
                        if (byDay && x.bookingDate != lastDay) {
                            lastDay = x.bookingDate
                            val dayTotal = st.items.asSequence().filter { it.bookingDate == x.bookingDate && it.kind != TxKind.TRASFERIMENTO }.fold(java.math.BigDecimal.ZERO) { a, t -> a + t.amount }
                            stickyHeader(key = "d${x.bookingDate}") {
                                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Text(x.bookingDate.dayLabel(st.today).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    if (dayTotal.signum() != 0) MoneyText(dayTotal, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        item(key = x.id) {
                            val cat = st.data.categoriesById[x.categoryId]
                            ListRow(
                                title = x.merchant, selected = x.id in st.selection,
                                subtitle = buildString {
                                    append(cat?.name ?: "Senza categoria"); if (x.manualCategory) append(" · ✎")
                                    if (st.data.accounts.size > 1) append(" · ").append(st.data.accountName(x.accountId))
                                    if (!byDay) append(" · ").append(it.spese.core.common.Dates.fmt(x.bookingDate))
                                    if (x.documentId != null) append(" · 📎")
                                },
                                leading = { if (x.id in st.selection) Icon(Icons.Default.CheckCircle, "Selezionato", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) else CategoryAvatar(cat?.name ?: "?", cat?.color?.toColor() ?: Color.Gray) },
                                trailing = { MoneyText(x.amount, signed = true, muted = x.kind == TxKind.TRASFERIMENTO) },
                                onClick = { if (selecting) vm.toggle(x.id) else onOpen(x.id) }, onLongClick = { vm.toggle(x.id) },
                            )
                        }
                    }
                }
            }
        }
    }

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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Scegli la categoria") }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(categories.filter { it.active }, key = { it.id }) { c ->
                    ListRow(c.name, subtitle = c.kind.label, leading = { CategoryAvatar(c.name, c.color.toColor(), size = 32) }, onClick = { onPick(c) })
                }
            }
        })
}
