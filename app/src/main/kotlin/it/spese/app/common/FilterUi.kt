package it.spese.app.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.core.common.Dates
import it.spese.core.designsystem.component.DateField
import it.spese.core.designsystem.component.DropdownField
import it.spese.core.model.TxKind
import it.spese.domain.finance.Period
import it.spese.domain.finance.PeriodResolver
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FilterViewModel @Inject constructor(private val store: FilterStore, val data: StateFlow<AppData>) : ViewModel() {
    val filter = store.filter
    fun update(t: (TxFilter) -> TxFilter) = store.update(t)
    fun reset() = store.reset()
}

/** Barra periodo (stessi periodi della versione HTML + mesi singoli) con accesso ai filtri avanzati. */
@Composable
fun FilterHeader(modifier: Modifier = Modifier, vm: FilterViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val f by vm.filter.collectAsState()
    val data by vm.data.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    val months = remember(data.transactions.size) { data.transactions.map { data.calendar.monthOf(it.bookingDate) }.distinct().sortedDescending().take(18) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { sheet = true }, label = { Text(if (f.activeCount > 0) "Filtri (${f.activeCount})" else "Filtri") },
                leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)) })
            listOf(Period.All to "Tutto", Period.YearToDate to "Anno", Period.Last3Months to "3 mesi", Period.Last6Months to "6 mesi", Period.CurrentMonth to "Mese corrente").forEach { (p, l) ->
                FilterChip(f.period == p, { vm.update { it.copy(period = p) } }, { Text(l) })
            }
            months.forEach { m -> FilterChip(f.period == Period.Month(m), { vm.update { it.copy(period = Period.Month(m)) } }, { Text(Dates.monthLabel(m)) }) }
        }
    }
    if (sheet) FilterSheet(f, data, vm::update, vm::reset) { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(f: TxFilter, data: AppData, onChange: ((TxFilter) -> TxFilter) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filtri", style = MaterialTheme.typography.titleLarge)
            val dr = if (data.transactions.isEmpty()) null else PeriodResolver.resolve(f.period, data.transactions.minOf { it.bookingDate }, data.transactions.maxOf { it.bookingDate }, f.calendar)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(dr?.from, { d -> onChange { it.copy(period = Period.Custom(d, dr?.to)) } }, "Dal", Modifier.weight(1f))
                DateField(dr?.to, { d -> onChange { it.copy(period = Period.Custom(dr?.from, d)) } }, "Al", Modifier.weight(1f))
            }
            DropdownField("Conto", data.accounts.firstOrNull { it.id == f.accountId }, data.accounts, { it.name }, { a -> onChange { it.copy(accountId = a.id) } }, nullLabel = "Tutti (aggregato)", onNull = { onChange { it.copy(accountId = null) } })
            CategoryField("Categoria", data.categories.firstOrNull { it.id == f.categoryId }, data.categories, { c -> onChange { it.copy(categoryId = c.id) } }, nullLabel = "Tutte", onNull = { onChange { it.copy(categoryId = null) } }, allowNew = false)
            Text("Tipo", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(f.kind == null, { onChange { it.copy(kind = null) } }, { Text("Tutti") })
                TxKind.entries.forEach { k -> FilterChip(f.kind == k, { onChange { it.copy(kind = k) } }, { Text(k.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var min by remember { mutableStateOf(f.minAmount?.toPlainString() ?: "") }
                var max by remember { mutableStateOf(f.maxAmount?.toPlainString() ?: "") }
                OutlinedTextField(min, { min = it; onChange { f2 -> f2.copy(minAmount = it.replace(',', '.').toBigDecimalOrNull()) } }, label = { Text("Importo min €") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(max, { max = it; onChange { f2 -> f2.copy(maxAmount = it.replace(',', '.').toBigDecimalOrNull()) } }, label = { Text("Importo max €") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Mostra risultati") }
                OutlinedButton(onClick = { onReset(); onDismiss() }) { Text("Reimposta") }
            }
        }
    }
}

@Composable
fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/** Due pillole (periodo e conto) con menu a tendina: il filtro quotidiano senza righe di chip. */
@Composable
fun FilterPills(modifier: Modifier = Modifier, vm: FilterViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val f by vm.filter.collectAsState()
    val data by vm.data.collectAsState()
    val months = remember(data.transactions.size) { data.transactions.map { data.calendar.monthOf(it.bookingDate) }.distinct().sortedDescending().take(12) }
    val periodLabel = when (val p = f.period) {
        Period.All -> "Tutto il periodo"; Period.YearToDate -> "Quest'anno"; Period.Last3Months -> "Ultimi 3 mesi"; Period.Last6Months -> "Ultimi 6 mesi"
        Period.CurrentMonth -> "Mese corrente"; Period.CurrentWeek -> "Settimana corrente"; is Period.Month -> Dates.monthLabelLong(p.month); is Period.Custom -> "Intervallo"
    }
    Row(modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        var pm by remember { mutableStateOf(false) }
        var am by remember { mutableStateOf(false) }
        Box {
            Pill(periodLabel) { pm = true }
            DropdownMenu(pm, { pm = false }) {
                listOf(Period.All to "Tutto il periodo", Period.YearToDate to "Quest'anno", Period.Last6Months to "Ultimi 6 mesi", Period.Last3Months to "Ultimi 3 mesi", Period.CurrentMonth to "Mese corrente", Period.CurrentWeek to "Settimana corrente").forEach { (p, l) ->
                    DropdownMenuItem({ Text(l) }, { vm.update { it.copy(period = p) }; pm = false })
                }
                HorizontalDivider()
                months.forEach { m -> DropdownMenuItem({ Text(Dates.monthLabelLong(m)) }, { vm.update { it.copy(period = Period.Month(m)) }; pm = false }) }
            }
        }
        if (data.accounts.size > 1) Box {
            Pill(data.accounts.firstOrNull { it.id == f.accountId }?.name ?: "Tutti i conti") { am = true }
            DropdownMenu(am, { am = false }) {
                DropdownMenuItem({ Text("Tutti i conti") }, { vm.update { it.copy(accountId = null) }; am = false })
                data.accounts.forEach { a -> DropdownMenuItem({ Text(a.name) }, { vm.update { it.copy(accountId = a.id) }; am = false }) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Row(Modifier.height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
