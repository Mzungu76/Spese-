package it.spese.feature.subscriptions

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.PatternKind
import it.spese.domain.finance.RecurringPattern

/** Abbonamenti, rate e spese periodiche riconosciute dai movimenti: costo mensile e annuo, novità (aumenti, addebiti mancanti), rate residue. */
@Composable
fun SubscriptionsScreen(onBack: () -> Unit, vm: SubscriptionsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Abbonamenti e rate", onBack)
        if (!st.data.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Eyebrow("Costo fisso ricorrente")
                    MoneyText(st.summary.monthly, colored = false, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("al mese · ${Money.eur0(st.summary.annual)} all'anno · ${st.summary.count} voci", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Riconosciute dai tuoi movimenti: stesso esercente, ripetuto con cadenza regolare circa nello stesso giorno. Sono stime: ignora quelle che non c'entrano.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (st.active.isEmpty() && st.stale.isEmpty() && st.finished.isEmpty()) item {
                EmptyState(Icons.Default.Autorenew, "Ancora nessuna ricorrenza", "Servono almeno 3 addebiti regolari dello stesso esercente (2 per le spese annuali o semestrali).")
            }
            if (st.news.isNotEmpty()) {
                item { Header("Novità · ${st.news.size}") }
                items(st.news, key = { "n-${it.key}" }) { p -> NewsCard(p) }
            }
            section("Abbonamenti", st.active.filter { it.kind == PatternKind.SUBSCRIPTION }, st, vm)
            section("Rate", st.active.filter { it.kind == PatternKind.INSTALLMENT }, st, vm)
            section("Spese periodiche variabili", st.active.filter { it.kind == PatternKind.VARIABLE }, st, vm)
            section("Probabilmente disdetti", st.stale, st, vm, hint = "Da 3 cicli senza addebito: non contano nei totali.")
            section("Concluse", st.finished, st, vm)
            if (st.ignored.isNotEmpty()) {
                item { Header("Ignorati · ${st.ignored.size}") }
                items(st.ignored, key = { "i-${it.key}" }) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.restore(r.key) }) { Text("Ripristina") }
                    }
                }
            }
        }
    }
}

@Composable private fun Header(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<RecurringPattern>, st: SubscriptionsState, vm: SubscriptionsViewModel, hint: String? = null) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Column { Header("$title · ${list.size}"); hint?.let { Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    items(list, key = { "p-${it.key}" }) { p -> PatternRow(p, st, vm) }
}

@Composable
private fun NewsCard(p: RecurringPattern) {
    val fin = SpeseTheme.finance
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.priceChanged) {
                val up = (p.priceDelta?.signum() ?: 0) > 0
                Text("${p.merchant}: ${if (up) "aumento" else "calo"} di prezzo", style = MaterialTheme.typography.titleSmall, color = if (up) fin.negative else fin.positive)
                Text("da ${Money.eur(p.previousAmount!!)} a ${Money.eur(p.lastAmount)} (${Money.eurSigned(p.priceDelta!!)} ${p.cadence.label}, ${Money.eurSigned(p.priceDelta!!.multiply(java.math.BigDecimal(12)).divide(java.math.BigDecimal(p.cadence.months), 0, java.math.RoundingMode.HALF_UP))} l'anno)", style = MaterialTheme.typography.bodyMedium)
            }
            if (p.missing) {
                Text("${p.merchant}: addebito atteso non arrivato", style = MaterialTheme.typography.titleSmall, color = fin.warning)
                Text("Era previsto verso il ${Dates.fmt(p.next)}. Può essere stato disdetto, spostato o addebitato su un altro conto.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PatternRow(p: RecurringPattern, st: SubscriptionsState, vm: SubscriptionsViewModel) {
    var menu by remember { mutableStateOf(false) }
    val cat = st.data.categoriesById[p.categoryId]
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CategoryAvatar(cat, size = 44)
        Column(Modifier.weight(1f)) {
            Text(p.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(buildString {
                append(p.cadence.label.replaceFirstChar { it.uppercase() })
                append(" · prossimo ").append(Dates.fmt(p.next))
                p.remainingInstallments?.let { append(" · ").append(if (it == 0) "ultima rata pagata" else "$it rate rimaste, fino a ${Dates.fmt(p.endDate!!)}") }
                if (p.tracked) append(" · già in Ricorrenti")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.cadence.months > 1) Text("${Money.eur0(p.perMonth)} al mese equivalenti · ${Money.eur0(p.perYear)} all'anno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(if (p.kind == PatternKind.VARIABLE) p.meanAmount else p.lastAmount, colored = false, style = MaterialTheme.typography.titleMedium)
            if (p.kind == PatternKind.VARIABLE) Text("in media", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Azioni per ${p.merchant}") }
            DropdownMenu(menu, { menu = false }) {
                if (!p.tracked) DropdownMenuItem(text = { Text("Crea ricorrenza") }, onClick = { menu = false; vm.track(p) })
                DropdownMenuItem(text = { Text("Ignora: non è un abbonamento") }, onClick = { menu = false; vm.ignore(p) })
            }
        }
    }
}
