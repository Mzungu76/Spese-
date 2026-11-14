package it.spese.feature.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.DataMenu
import it.spese.app.common.BackHeader
import it.spese.app.common.SearchField
import it.spese.domain.finance.SearchMatcher
import it.spese.app.common.RoundIconButton
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.*
import it.spese.domain.classification.DefaultData
import it.spese.domain.classification.RuleEngine
import java.math.BigDecimal

/** Gestione: elenco e modifica delle categorie, regole di classificazione e movimenti ancora senza regola ("Da fare"). */
@Composable
fun ManageScreen(initialTab: Int, onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var q by remember { mutableStateOf("") }
    var editCat by remember { mutableStateOf<Category?>(null) }
    var editRule by remember { mutableStateOf<Rule?>(null) }
    var assign by remember { mutableStateOf<UnclassifiedRow?>(null) }
    var editQuota by remember { mutableStateOf<CashQuota?>(null) }
    val d = st.data
    val spendMax = remember(st.txTotal, d.categories) { d.categories.filter { it.kind == TxKind.SPESA }.maxOfOrNull { (st.txTotal[it.id] ?: BigDecimal.ZERO).abs() }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Gestione", onBack, actions = {
            if (tab != 2) RoundIconButton(Icons.Default.Add, when (tab) { 0 -> "Nuova categoria"; 1 -> "Nuova regola"; else -> "Nuova quota" }, {
                if (tab == 0) editCat = Category(0, "", TxKind.SPESA, true, vm.nextColor(d.categories.size), null, true, d.categories.size)
                else if (tab == 3) editQuota = CashQuota(0, "Affitto", d.categories.firstOrNull { it.kind == TxKind.SPESA && it.active && it.systemKey == null }?.id ?: 0, BigDecimal.ZERO)
                else editRule = Rule(0, "", d.categories.firstOrNull { it.active }?.id ?: 0, AmountSign.ANY, BigDecimal.ZERO, BigDecimal.ZERO, 0)
            })
        })
        PillSegmented(listOf(0 to "Categorie", 1 to "Regole · ${d.rules.size}", 2 to if (st.unclassified.isNotEmpty()) "Da fare · ${st.unclassified.size}" else "Da fare", 3 to "Quote"), tab, { tab = it; q = "" }, Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tab in 0..2) item(key = "search-$tab") { SearchField(q, { q = it }, when (tab) { 0 -> "Cerca categoria…"; 1 -> "Cerca regola o categoria…"; else -> "Cerca esercente…" }, Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            when (tab) {
                0 -> {
                    listOf(TxKind.SPESA to "Spese", TxKind.ENTRATA to "Entrate", TxKind.TRASFERIMENTO to "Giroconti (esclusi dai totali)").forEach { (kind, title) ->
                        val ofKind = d.categories.filter { it.kind == kind && SearchMatcher.matches(q, it.name) }
                        // le sottocategorie stanno sotto la loro principale
                        val list = ofKind.filter { it.parentId == null || ofKind.none { p -> p.id == it.parentId } }.flatMap { p -> listOf(p) + ofKind.filter { it.parentId == p.id } }
                        if (list.isEmpty()) return@forEach
                        item(key = "h$kind") { Text(title.uppercase(), Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(list, key = { it.id }) { c ->
                            val total = st.txTotal[c.id] ?: BigDecimal.ZERO
                            Row(Modifier.fillMaxWidth().clickable { editCat = c }.padding(start = if (c.parentId != null) 56.dp else 24.dp, end = 24.dp).heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CategoryAvatar(c, size = if (c.parentId != null) 40 else 48)
                                Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        MoneyText(total, decimals = false, colored = c.kind == TxKind.ENTRATA, muted = c.kind == TxKind.TRASFERIMENTO, style = MaterialTheme.typography.titleMedium)
                                    }
                                    if (c.kind == TxKind.SPESA) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        Box(Modifier.fillMaxWidth((total.abs().toFloat() / spendMax.toFloat()).coerceIn(0.02f, 1f)).height(4.dp).background(c.color.toColor()))
                                    }
                                    Text(buildString { append("${st.txCount[c.id] ?: 0} movimenti"); c.budgetMonthly?.let { append(" · budget ").append(Money.eur0(it)).append(" al mese") }; if (c.flexible) append(" · comprimibile"); if (!c.active) append(" · disattivata") },
                                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item { Text("Vince la prima regola della lista che corrisponde. Testo semplice = \"contiene\"; con prefisso re: è un'espressione regolare. Le assegnazioni manuali prevalgono sempre.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(d.rules.filter { r -> SearchMatcher.matches(q, r.keyword, d.categoryName(r.categoryId)) }, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { editRule = r }.padding(horizontal = 24.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CategoryAvatar(d.categoriesById[r.categoryId], size = 44)
                            Column(Modifier.weight(1f)) {
                                Text(if (r.keyword.isBlank()) "Qualsiasi descrizione" else "“${r.keyword}”", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(buildString {
                                    append("→ ").append(d.categoryName(r.categoryId))
                                    if (r.sign != AmountSign.ANY) append(if (r.sign == AmountSign.NEGATIVE) " · solo uscite" else " · solo entrate")
                                    if (r.min.signum() != 0) append(" · ≥ ").append(Money.eur0(r.min)); if (r.max.signum() != 0) append(" · ≤ ").append(Money.eur0(r.max))
                                    append(" · ${st.ruleHits[r.id] ?: 0} mov.")
                                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                2 -> {
                    if (st.unclassified.isEmpty()) item { EmptyState(Icons.Default.CheckCircle, "Tutto classificato", "Ogni movimento ha una categoria da una regola o assegnata a mano.") }
                    else {
                        item { Text("Movimenti senza regola: finiscono in \"Altro\" / \"Altre entrate\". Tocca un esercente per creare una regola.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(st.unclassified.filter { SearchMatcher.matches(q, it.merchant, it.sampleDescription) }, key = { it.merchant }) { u ->
                            Row(Modifier.fillMaxWidth().clickable { assign = u }.padding(horizontal = 24.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) { Text(u.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1); Text("${u.count} movimenti", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                MoneyText(u.total, decimals = false, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                else -> {
                    item { Text("Quote fisse pagate in contanti, come l'affitto. Una volta al mese il primo prelievo che le copre viene diviso: la quota va alla categoria scelta, il resto resta com'è. Il prelievo compare in Movimenti › Verifica per un controllo.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (d.quotas.isEmpty()) item { EmptyState(Icons.Default.Payments, "Nessuna quota", "Tocca + per aggiungere, ad esempio, un affitto da 500 € pagato in contanti.") }
                    items(d.quotas, key = { it.id }) { q ->
                        val c = d.categoriesById[q.categoryId]
                        Row(Modifier.fillMaxWidth().clickable { editQuota = q }.padding(horizontal = 24.dp).heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CategoryAvatar(c, size = 48)
                            Column(Modifier.weight(1f)) {
                                Text(q.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("${Money.eur0(q.amount)} al mese → ${c?.name ?: "?"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Switch(q.enabled, { on -> vm.saveQuota(q.copy(enabled = on)) })
                        }
                    }
                    if (d.quotas.any { it.enabled }) item { OutlinedButton(onClick = vm::applyQuotasNow, Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { Text("Applica ai prelievi esistenti") } }
                }
            }
        }
    }
    editCat?.let { c -> CategoryDialog(c, d.categories.indexOfFirst { it.id == c.id }, d.categories.size, d.categories, { editCat = null }, vm) }
    editRule?.let { r -> RuleDialog(r, d, { editRule = null }, vm) }
    editQuota?.let { q -> QuotaDialog(q, d.categories.filter { it.kind == TxKind.SPESA && it.active }, { editQuota = null }, vm) }
    assign?.let { u ->
        var kw by remember(u) { mutableStateOf(u.keyword) }
        var cat by remember(u) { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { assign = null }, title = { Text(u.merchant) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(u.sampleDescription, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(kw, { kw = it }, label = { Text("Parola chiave") }, singleLine = true)
                DropdownField("Assegna a", d.categories.firstOrNull { it.id == cat }, d.categories.filter { it.active }, { it.name }, { cat = it.id })
            } },
            confirmButton = { TextButton(enabled = kw.isNotBlank() && cat != null, onClick = { vm.assignUnclassified(kw.trim(), cat!!); assign = null }) { Text("Crea regola e assegna") } },
            dismissButton = { TextButton(onClick = { assign = null }) { Text("Annulla") } })
    }
}

@Composable
private fun CategoryDialog(c: Category, index: Int, total: Int, all: List<Category>, onDismiss: () -> Unit, vm: CategoriesViewModel) {
    var name by remember { mutableStateOf(c.name) }
    var kind by remember { mutableStateOf(c.kind) }
    var color by remember { mutableStateOf(c.color) }
    var icon by remember { mutableStateOf(c.icon) }
    var parentId by remember { mutableStateOf(c.parentId) }
    var nature by remember { mutableStateOf(c.nature) }
    var flexible by remember { mutableStateOf(c.flexible) }
    var active by remember { mutableStateOf(c.active) }
    var budget by remember { mutableStateOf(c.budgetMonthly?.toPlainString()?.replace('.', ',') ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (c.id == 0L) "Nuova categoria" else "Modifica categoria") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TxKind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(if (k == TxKind.TRASFERIMENTO) "Giroconto" else k.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) } }
                }
                // sottocategoria di…: solo categorie principali dello stesso tipo, e solo se questa non ha già figlie
                val parents = all.filter { it.parentId == null && it.kind == kind && it.id != c.id && it.systemKey == null }
                if (all.none { it.parentId == c.id && c.id != 0L } && parents.isNotEmpty())
                    DropdownField("Sottocategoria di", parents.firstOrNull { it.id == parentId }, parents, { it.name }, { parentId = it.id }, nullLabel = "Nessuna (categoria principale)", onNull = { parentId = null })
                if (kind == TxKind.SPESA) {
                    Text("Natura della spesa", style = MaterialTheme.typography.labelLarge)
                    ChipChoice(listOf<Pair<SpendNature?, String>>(null to "Non definita") + SpendNature.entries.map { it to it.label }, nature, { nature = it })
                    Text(nature?.hint ?: "Aiuta a capire dove si può risparmiare: le spese discrezionali sono le prime da guardare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Icona", style = MaterialTheme.typography.labelLarge)
                val autoKey = CategoryIcons.defaultFor(name, kind, c.systemKey)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryIcons.KEYS.forEach { k ->
                        val on = (icon ?: autoKey) == k
                        Box(Modifier.size(40.dp).background(if (on) color.toColor().copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                            .then(if (on) Modifier.border(2.dp, color.toColor(), CircleShape) else Modifier)
                            .clickable { icon = k }, contentAlignment = Alignment.Center) {
                            Icon(CategoryIconSet.vector(k), CategoryIcons.LABELS[k] ?: k, Modifier.size(22.dp), tint = if (on) color.toColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (icon != null) TextButton(onClick = { icon = null }) { Text("Ripristina icona automatica") }
                Text("Colore", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultData.PALETTE.forEach { p ->
                        Box(Modifier.size(32.dp).background(p.toColor(), CircleShape).then(if (p == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).clickable { color = p })
                    }
                }
                AmountField(budget, { budget = it }, "Budget mensile (facoltativo)", Modifier.fillMaxWidth())
                LabeledSwitch("Voce comprimibile", flexible, { flexible = it }, supporting = "Il simulatore può proporre tagli")
                LabeledSwitch("Attiva", active, { active = it }, supporting = "Le categorie disattivate non compaiono nelle scelte")
                if (c.id != 0L) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.moveCategory(c.id, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, null); Text("Su") }
                        OutlinedButton(onClick = { vm.moveCategory(c.id, 1) }, enabled = index < total - 1) { Icon(Icons.Default.ArrowDownward, null); Text("Giù") }
                    }
                    if (c.systemKey == null) TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina categoria") }
                    else Text("Categoria di sistema: usata quando nessuna regola corrisponde. Non si può eliminare.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            vm.save(c.copy(name = name, kind = kind, color = color, icon = icon, nature = if (kind == TxKind.SPESA) nature else null, parentId = parentId?.takeIf { p -> all.any { it.id == p && it.kind == kind } }, flexible = flexible, active = active, budgetMonthly = parseAmountOrNull(budget)?.takeIf { it.signum() > 0 })); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
    if (confirmDelete) ConfirmDialog("Eliminare \"${c.name}\"?", "Le regole e le assegnazioni manuali collegate vengono rimosse; i movimenti tornano alla classificazione automatica (\"Altro\" se nessuna regola corrisponde).", "Elimina", { vm.delete(c.id); onDismiss() }, { confirmDelete = false }, destructive = true)
}

@Composable
private fun RuleDialog(r: Rule, d: it.spese.domain.repository.AppData, onDismiss: () -> Unit, vm: CategoriesViewModel) {
    var kw by remember { mutableStateOf(r.keyword) }
    var sign by remember { mutableStateOf(r.sign) }
    var min by remember { mutableStateOf(if (r.min.signum() != 0) r.min.toPlainString().replace('.', ',') else "") }
    var max by remember { mutableStateOf(if (r.max.signum() != 0) r.max.toPlainString().replace('.', ',') else "") }
    var cat by remember { mutableStateOf(r.categoryId) }
    val valid = RuleEngine.isValidKeyword(kw)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (r.id == 0L) "Nuova regola" else "Modifica regola") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(kw, { kw = it }, label = { Text("Parola chiave (facoltativa)") }, placeholder = { Text("es. coop  oppure  re:coop|conad") }, isError = !valid,
                    supportingText = { Text(if (valid) "Prefisso re: = espressione regolare" else "Espressione regolare non valida") }, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(AmountSign.ANY to "Tutti", AmountSign.NEGATIVE to "Uscite", AmountSign.POSITIVE to "Entrate").forEachIndexed { i, (s, l) -> SegmentedButton(sign == s, { sign = s }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(min, { min = it }, "Importo ≥", Modifier.weight(1f)); AmountField(max, { max = it }, "Importo ≤", Modifier.weight(1f))
                }
                Text("Importi in valore assoluto. La parola chiave può restare vuota se usi solo l'importo.", style = MaterialTheme.typography.bodySmall)
                DropdownField("Categoria", d.categoriesById[cat], d.categories.filter { it.active }, { it.name }, { cat = it.id })
                if (r.id != 0L) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.moveRule(r.id, -1) }) { Icon(Icons.Default.ArrowUpward, null); Text("Su") }
                    OutlinedButton(onClick = { vm.moveRule(r.id, 1) }) { Icon(Icons.Default.ArrowDownward, null); Text("Giù") }
                    TextButton(onClick = { vm.deleteRule(r.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina") }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid && cat != 0L, onClick = {
            vm.saveRule(r.copy(keyword = kw.trim(), sign = sign, min = parseAmountOrNull(min)?.abs() ?: BigDecimal.ZERO, max = parseAmountOrNull(max)?.abs() ?: BigDecimal.ZERO, categoryId = cat)); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun QuotaDialog(q: CashQuota, categories: List<Category>, onDismiss: () -> Unit, vm: CategoriesViewModel) {
    var name by remember { mutableStateOf(q.name) }
    var amount by remember { mutableStateOf(if (q.amount.signum() > 0) q.amount.toPlainString().replace('.', ',') else "") }
    var cat by remember { mutableStateOf(q.categoryId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (q.id == 0L) "Nuova quota in contanti" else "Modifica quota") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(amount, { amount = it }, "Importo mensile (€)", Modifier.fillMaxWidth(), supporting = "Si applica al primo prelievo del mese di importo pari o superiore.")
                DropdownField("Categoria della quota", categories.firstOrNull { it.id == cat }, categories, { it.name }, { cat = it.id })
                if (q.id != 0L) TextButton(onClick = { vm.deleteQuota(q.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina quota") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && cat != 0L && (parseAmountOrNull(amount)?.signum() ?: 0) > 0, onClick = { vm.saveQuota(q.copy(name = name, amount = parseAmountOrNull(amount) ?: BigDecimal.ZERO, categoryId = cat)); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
