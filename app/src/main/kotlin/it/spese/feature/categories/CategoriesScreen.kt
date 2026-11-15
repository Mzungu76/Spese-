package it.spese.feature.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.DataMenu
import it.spese.app.common.LargeHeader
import it.spese.app.common.RoundIconButton
import it.spese.app.common.SearchField
import it.spese.domain.finance.SearchMatcher
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.TxKind
import it.spese.feature.dashboard.DashboardViewModel
import java.math.BigDecimal

/**
 * Categorie come analisi: classifica per peso con variazioni sul periodo precedente. Un tocco apre il dettaglio (esercenti, andamento,
 * sottocategorie, budget). Modifica di categorie, regole e movimenti "Da fare" sono in Gestione.
 */
@Composable
fun CategoriesScreen(menu: DataMenu, onOpenCategory: (Long) -> Unit, onManage: (Int) -> Unit, onOpenTrends: () -> Unit = {}, bottomPadding: PaddingValues = PaddingValues(), vm: DashboardViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val m = st.model
    var kind by rememberSaveable { mutableStateOf(TxKind.SPESA) }
    var autoNature by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LargeHeader("Categorie", menu = menu, actions = { RoundIconButton(Icons.Default.Tune, "Gestione: categorie, regole e da fare", { onManage(0) }) })
        if (!st.data.loaded || m == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        val rows = (if (kind == TxKind.SPESA) m.categories else m.incomeCategories).filter { r -> SearchMatcher.matches(q, r.category?.name, r.category?.parentId?.let { st.data.categoriesById[it]?.name }) }
        val shownIds = rows.mapNotNull { it.category?.id }.toSet()
        val idle = st.data.categories.filter { it.active && it.kind == kind && it.parentId == null && it.id !in shownIds && it.systemKey == null && SearchMatcher.matches(q, it.name) }
        val maxTotal = rows.maxOfOrNull { it.total } ?: BigDecimal.ONE
        val total = if (kind == TxKind.SPESA) m.stats.expense else m.stats.income
        val change = if (kind == TxKind.SPESA) m.expenseChange else m.incomeChange
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp)) {
            item { SearchField(q, { q = it }, "Cerca categoria…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item { PeriodPills(st.filter.period, vm::setPeriod, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item { PillSegmented(listOf(TxKind.SPESA to "Spese", TxKind.ENTRATA to "Entrate"), kind, { kind = it }, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpenTrends, label = { Text("Tendenze") }, leadingIcon = { Icon(Icons.Default.Timeline, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onManage(2) }, label = { Text(if (m.unclassified > 0) "Da fare · ${m.unclassified}" else "Da fare") }, leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onManage(1) }, label = { Text("Regole · ${st.data.rules.size}") }, leadingIcon = { Icon(Icons.Default.Rule, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onManage(0) }, label = { Text("Modifica categorie") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Eyebrow(if (kind == TxKind.SPESA) "Speso nel periodo" else "Incassato nel periodo")
                    MoneyText(total, colored = false, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    if (change != null && m.previousLabel != null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeltaBadge(change, upIsGood = kind == TxKind.ENTRATA)
                        Text(m.previousLabel!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (kind == TxKind.SPESA) {
                val pending = st.data.categories.filter { it.kind == TxKind.SPESA && it.active && it.nature == null && it.systemKey == null && it.parentId == null && m.natureSuggestions.containsKey(it.id) }
                item { NatureCard(m.natureTotals, pending.size, { autoNature = true }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (rows.isNotEmpty()) item(key = "chart-$kind") { CategoriesChart(rows, kind) }
            if (rows.isEmpty()) item { Text("Nessun movimento di questo tipo nel periodo.", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { "r-$kind-${it.category?.id}" }) { r ->
                var open by rememberSaveable(r.category?.id, kind) { mutableStateOf(false) }
                Column {
                    CategoryRankRow(r, kind, maxTotal, { r.category?.id?.let(onOpenCategory) }, showNature = kind == TxKind.SPESA,
                        budget = if (kind == TxKind.SPESA && st.filter.period == it.spese.domain.finance.Period.CurrentMonth) m.budgets.firstOrNull { it.category.id == r.category?.id } else null)
                    if (r.children.isNotEmpty()) {
                        TextButton(onClick = { open = !open }, Modifier.padding(start = 72.dp)) {
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                            Text(if (open) "  Nascondi il dettaglio" else "  Dettaglio: ${r.children.count { !it.own }} sottocategorie")
                        }
                        if (open) ChildRows(r, kind, onOpenCategory)
                    }
                }
            }
            if (idle.isNotEmpty()) {
                item { Text("SENZA MOVIMENTI NEL PERIODO", Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp) }
                items(idle, key = { "i-${it.id}" }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenCategory(c.id) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CategoryAvatar(c, size = 40)
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (autoNature && m != null) {
        val pending = st.data.categories.filter { it.kind == TxKind.SPESA && it.active && it.nature == null && it.systemKey == null && it.parentId == null }
            .mapNotNull { c -> m.natureSuggestions[c.id]?.let { c to it } }
        AlertDialog(onDismissRequest = { autoNature = false }, title = { Text("Natura delle spese") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Proposta dai tuoi dati: Fissa se si ripete ogni mese, Necessaria per le spese di base, Discrezionale per il resto. Potrai correggerla categoria per categoria.", style = MaterialTheme.typography.bodySmall)
                    pending.forEach { (c, sug) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CategoryAvatar(c, size = 32)
                            Column(Modifier.weight(1f)) { Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1); Text(sug.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(sug.nature.label, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyNatures(pending.associate { it.first.id to it.second.nature }); autoNature = false }) { Text("Imposta tutte") } },
            dismissButton = { TextButton(onClick = { autoNature = false }) { Text("Annulla") } })
    }
}


/** Grafico del periodo: per categoria o per sottocategoria, a torta, a barre o entrambi. Le categorie piccole confluiscono in «Altre». */
@Composable
private fun CategoriesChart(rows: List<it.spese.domain.finance.CategoryRow>, kind: TxKind) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf(false) }
    val hasChildren = rows.any { it.children.isNotEmpty() }
    val palette = it.spese.domain.classification.DefaultData.PALETTE
    val parts: List<Pair<String, BigDecimal>> = rows.flatMap { r ->
        val name = r.category?.name ?: "Senza categoria"
        if (detail && r.children.isNotEmpty()) r.children.map { ch -> (if (ch.own) "$name (altro)" else "$name › ${ch.category.name}") to ch.total } else listOf(name to r.total)
    }.sortedByDescending { it.second }
    val top = parts.take(9); val rest = parts.drop(9).fold(BigDecimal.ZERO) { a, b -> a + b.second }
    val shown = top + (if (rest.signum() > 0) listOf("Altre" to rest) else emptyList())
    val slices = shown.mapIndexed { i, (n, v) -> it.spese.core.designsystem.chart.Slice(n, v, palette[i % palette.size].toColor()) }
    val total = parts.fold(BigDecimal.ZERO) { a, b -> a + b.second }
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), title = if (detail) "Per sottocategoria" else "Per categoria") {
        PillSegmented(listOf(0 to "Torta", 1 to "Barre", 2 to "Entrambi"), mode, { mode = it })
        if (hasChildren) LabeledSwitch("Dettaglio per sottocategoria", detail, { detail = it })
        if (mode == 0 || mode == 2) it.spese.core.designsystem.chart.DonutChart(slices, Modifier.fillMaxWidth(), centerTitle = if (kind == TxKind.SPESA) "Speso" else "Incassato")
        if (mode == 1 || mode == 2) it.spese.core.designsystem.chart.HorizontalBars(slices.map { sl ->
            it.spese.core.designsystem.chart.BarItem(sl.label, sl.value, sl.color, Money.percent(sl.value, total).toPlainString().replace('.', ',') + "%")
        }, Modifier.padding(top = 12.dp))
    }
}
