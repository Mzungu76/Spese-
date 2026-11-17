package it.spese.feature.transfers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.model.Transaction
import it.spese.domain.classification.TransferDetector
import it.spese.domain.repository.AppData

/** Giroconti probabili: un'uscita da un conto e un'entrata dello stesso importo su un altro, a pochi giorni. Confermandoli escono da entrate e spese. */
@Composable
fun TransfersScreen(onBack: () -> Unit, vm: TransfersViewModel = hiltViewModel()) {
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Giroconti probabili", onBack)
        if (pairs.isEmpty()) {
            EmptyState(Icons.Default.SwapHoriz, "Nessun giroconto da confermare", "Quando un'uscita da un conto e un'entrata dello stesso importo su un altro conto cadono a pochi giorni, te le propongo qui.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Spostare soldi da un conto a un altro non è né una spesa né un'entrata. Se confermi, le due metà passano alla categoria «Trasferimenti» ed escono dai totali; i saldi dei conti restano giusti.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pairs.size > 1) Button(onClick = { vm.confirm(pairs) }, Modifier.testTag("transfers_confirm_all")) { Text("Conferma tutti (${pairs.size})") }
                }
            }
            items(pairs, key = { it.key }) { p -> PairCard(p, data, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), { vm.confirm(listOf(p)) }, { vm.dismiss(p) }) }
        }
    }
}

@Composable
private fun PairCard(p: TransferDetector.Pair, data: AppData, modifier: Modifier, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SectionCard(modifier) {
        Leg("Esce", p.outgoing, data)
        Leg("Entra", p.incoming, data)
        val notes = buildList {
            add(if (p.daysApart == 0) "Stesso giorno" else "A ${p.daysApart} ${if (p.daysApart == 1) "giorno" else "giorni"} di distanza")
            if (p.fee.signum() > 0) add("commissione di ${Money.eur(p.fee)}")
        }
        Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onConfirm, Modifier.testTag("transfer_confirm")) { Text("È un giroconto") }
            TextButton(onClick = onDismiss) { Text("Non lo è") }
        }
    }
}

@Composable
private fun Leg(label: String, t: Transaction, data: AppData) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(t.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(Dates.fmt(t.bookingDate) + " · " + data.accountName(t.accountId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Money.eur(t.amount), style = MaterialTheme.typography.titleSmall)
    }
}
