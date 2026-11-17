package it.spese.app.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.core.designsystem.component.*
import it.spese.core.model.Category
import it.spese.core.model.TxKind
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.SearchMatcher
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.SaveCategoryUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Crea una categoria o sottocategoria al volo, dai selettori. */
@HiltViewModel
class CategoryQuickAddViewModel @Inject constructor(
    private val save: SaveCategoryUseCase, private val messenger: UiMessenger, val data: StateFlow<AppData>,
) : ViewModel() {
    fun create(name: String, kind: TxKind, parent: Category?, onDone: (Category) -> Unit) {
        viewModelScope.launch {
            try {
                val color = parent?.color ?: save.nextColor(data.value.categories.size)
                val c = Category(0, name.trim(), kind, false, color, parentId = parent?.id, nature = parent?.nature, sortOrder = data.value.categories.size)
                onDone(c.copy(id = save(c)))
            } catch (e: Exception) { messenger.show(e.message ?: "Categoria non creata") }
        }
    }
}

/**
 * Selettore di categoria a sottomenu: l'elenco mostra le categorie principali; toccandone una con sottocategorie si entra nel suo
 * sottomenu (con «tutta la categoria» in cima), senza scegliere subito. La ricerca mostra tutte le corrispondenze. Da qui si può anche
 * creare una nuova categoria (nell'elenco principale) o sottocategoria (nel sottomenu).
 * @param kind se indicato, mostra solo categorie di quel tipo (e le nuove nascono di quel tipo).
 */
@Composable
fun CategoryPickerDialog(
    categories: List<Category>, onDismiss: () -> Unit, kind: TxKind? = null, title: String = "Scegli la categoria",
    noneLabel: String? = null, onNone: (() -> Unit)? = null, allowNew: Boolean = true, onPick: (Category) -> Unit,
) {
    val quick: CategoryQuickAddViewModel = hiltViewModel()
    var q by remember { mutableStateOf("") }
    var openParent by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    val active = remember(categories, kind) { categories.filter { it.active && (kind == null || it.kind == kind) } }
    val byId = remember(categories) { categories.associateBy { it.id } }
    val roots = remember(active) { active.filter { it.parentId == null || active.none { p -> p.id == it.parentId } }.sortedBy { it.sortOrder } }
    val parent = openParent?.let { id -> active.firstOrNull { it.id == id } }
    val kids = remember(active, parent) { parent?.let { p -> active.filter { it.parentId == p.id } }.orEmpty() }
    val searching = q.isNotBlank()
    val matches = remember(q, active) { if (!searching) emptyList() else CategoryTree.ordered(active).filter { c -> SearchMatcher.matches(q, CategoryTree.label(c, byId), c.kind.label) } }

    if (creating) {
        NewCategoryDialog(parent = parent, fixedKind = parent?.kind ?: kind, onDismiss = { creating = false }) { name, k ->
            quick.create(name, k, parent) { created -> creating = false; onPick(created) }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (parent != null && !searching) IconButton(onClick = { openParent = null }) { Icon(Icons.Default.ArrowBack, "Torna alle categorie") }
                Text(if (parent != null && !searching) parent.name else title)
            }
        },
        text = {
            Column {
                SearchField(q, { q = it }, "Cerca categoria…", Modifier.padding(bottom = 8.dp), debounceMs = 0)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    when {
                        searching -> {
                            if (matches.isEmpty()) item { Text("Nessuna categoria trovata.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(matches, key = { "m-${it.id}" }) { c -> ListRow(CategoryTree.label(c, byId), subtitle = c.kind.label, leading = { CategoryAvatar(c, size = 32) }, onClick = { onPick(c) }) }
                        }
                        parent != null -> {
                            item { ListRow("${parent.name} (tutta la categoria)", subtitle = "Senza una sottocategoria precisa", leading = { CategoryAvatar(parent, size = 32) }, onClick = { onPick(parent) }) }
                            items(kids, key = { "k-${it.id}" }) { c -> ListRow(c.name, subtitle = c.kind.label, leading = { CategoryAvatar(c, size = 32) }, onClick = { onPick(c) }) }
                            if (allowNew) item { NewRow("Nuova sottocategoria in ${parent.name}") { creating = true } }
                        }
                        else -> {
                            if (noneLabel != null && onNone != null) item { ListRow(noneLabel, leading = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(32.dp)) }, onClick = onNone) }
                            if (roots.isEmpty()) item { Text("Nessuna categoria.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(roots, key = { "r-${it.id}" }) { c ->
                                val n = active.count { it.parentId == c.id }
                                ListRow(c.name, subtitle = if (n > 0) "$n sottocategorie" else c.kind.label, leading = { CategoryAvatar(c, size = 32) },
                                    trailing = { if (n > 0) Icon(Icons.Default.ChevronRight, "Apri le sottocategorie di ${c.name}") },
                                    onClick = { if (n > 0) openParent = c.id else onPick(c) })
                            }
                            if (allowNew) item { NewRow("Nuova categoria") { creating = true } }
                        }
                    }
                }
            }
        })
}

@Composable
private fun NewRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.AddCircleOutline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
}

/** Nome (e, se non è già fissato, tipo) della nuova categoria o sottocategoria. */
@Composable
fun NewCategoryDialog(parent: Category?, fixedKind: TxKind?, onDismiss: () -> Unit, onCreate: (String, TxKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(fixedKind ?: TxKind.SPESA) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (parent != null) "Nuova sottocategoria in ${parent.name}" else "Nuova categoria") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (fixedKind == null && parent == null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TxKind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(if (k == TxKind.TRASFERIMENTO) "Giroconto" else k.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) } }
                }
                if (parent != null) Text("Eredita colore e natura della categoria principale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, kind) }, enabled = name.isNotBlank()) { Text("Crea e scegli") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

/** Campo che mostra la categoria scelta e apre il selettore a sottomenu. Sostituisce il menu a tendina piatto. */
@Composable
fun CategoryField(
    label: String, selected: Category?, categories: List<Category>, onSelect: (Category) -> Unit, modifier: Modifier = Modifier,
    kind: TxKind? = null, nullLabel: String? = null, onNull: (() -> Unit)? = null, allowNew: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val byId = remember(categories) { categories.associateBy { it.id } }
    Box(modifier) {
        OutlinedTextField(value = selected?.let { CategoryTree.label(it, byId) } ?: nullLabel ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) CategoryPickerDialog(categories, { open = false }, kind = kind, title = label, noneLabel = nullLabel.takeIf { onNull != null }, onNone = onNull?.let { f -> { f(); open = false } }, allowNew = allowNew) { c -> onSelect(c); open = false }
}
