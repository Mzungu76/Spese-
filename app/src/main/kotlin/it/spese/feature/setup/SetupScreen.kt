package it.spese.feature.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.domain.finance.SetupProposal
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun SetupScreen(onBack: () -> Unit, vm: SetupViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Imposta il tuo piano", onBack)
        val p = st.proposal
        when {
            !st.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            p == null -> EmptyState(Icons.Default.AccountBalanceWallet, "Servono dei movimenti", "Importa un estratto conto o registra qualche spesa: poi ti propongo un piano costruito sui tuoi dati.")
            else -> SetupForm(p, onBack, vm)
        }
    }
}

@Composable
private fun SetupForm(p: SetupProposal, onBack: () -> Unit, vm: SetupViewModel) {
    var income by remember(p) { mutableStateOf(p.expectedIncome.toPlainString()) }
    var goal by remember(p) { mutableStateOf(p.recommendedGoal.toPlainString()) }
    var chosen by remember(p) { mutableStateOf(p.budgets.map { it.categoryId }.toSet()) }
    val incomeV = parseAmountOrNull(income) ?: BigDecimal.ZERO
    val goalV = parseAmountOrNull(goal) ?: BigDecimal.ZERO
    val free = incomeV - goalV - p.fixedMonthly
    val perDay = free.divide(BigDecimal("30.44"), 0, RoundingMode.HALF_UP)
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ho guardato i tuoi movimenti e ti propongo un piano. Controlla i numeri, correggili se serve e conferma: nulla cambia finché non salvi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard(title = "1 · Quanto entra ogni mese") {
                AmountField(income, { income = it }, "Entrate mensili", supporting = "Media degli ultimi mesi: puoi cambiarla se lo stipendio è variato", modifier = Modifier.testTag("setup_income"))
            }
        }
        item {
            SectionCard(title = "2 · Le tue spese fisse") {
                if (p.fixed.isEmpty()) Text("Non ho trovato addebiti ricorrenti. Potrai aggiungere le scadenze dal Piano.", style = MaterialTheme.typography.bodyMedium)
                else {
                    p.fixed.sortedByDescending { it.mean }.take(8).forEach { r -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(r.merchant, Modifier.weight(1f), maxLines = 1); Text(Money.eur0(r.mean)) } }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Totale al mese", fontWeight = FontWeight.Medium); Text(Money.eur0(p.fixedMonthly), fontWeight = FontWeight.Medium) }
                    Text("Rilevate dai tuoi movimenti (stesso esercente, ogni mese, importo simile).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionCard(title = "3 · Quanto vuoi mettere da parte") {
                ChipChoice(p.goalOptions.map { (pct, v) -> v.toPlainString() to "$pct% · ${Money.eur0(v)}" }, goal, { goal = it })
                AmountField(goal, { goal = it }, "Risparmio mensile", supporting = "Consigliato: ${Money.eur0(p.recommendedGoal)} (già alla tua portata)", modifier = Modifier.testTag("setup_goal"))
                Text(if (free.signum() >= 0) "Con questi numeri ti restano circa ${Money.eur0(free)} al mese per le spese variabili (≈ ${Money.eur0(perDay)} al giorno)."
                    else "Attenzione: fisse e obiettivo superano le entrate di ${Money.eur0(free.abs())} al mese. Abbassa l'obiettivo o rivedi le spese.",
                    style = MaterialTheme.typography.bodyMedium, color = if (free.signum() >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
        }
        if (p.budgets.isNotEmpty()) item {
            SectionCard(title = "4 · Budget mensili suggeriti") {
                Text("Partono dalla tua spesa media; sulle voci discrezionali ho tolto il 10%. Deseleziona quelli che non vuoi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.budgets.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(b.categoryId in chosen, { on -> chosen = if (on) chosen + b.categoryId else chosen - b.categoryId })
                        Column(Modifier.weight(1f)) { Text(b.name, maxLines = 1); Text("oggi in media ${Money.eur0(b.monthlyAvg)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(Money.eur0(b.suggested), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
    // i pulsanti restano sempre visibili sotto l'elenco
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { vm.apply(incomeV, goalV, p.budgets.filter { it.categoryId in chosen }, onBack) }, Modifier.fillMaxWidth().testTag("setup_save"), enabled = incomeV.signum() > 0 && goalV.signum() >= 0) { Text("Salva il piano") }
        TextButton(onClick = { vm.skip(onBack) }, Modifier.fillMaxWidth()) { Text("Non ora") }
    }
    }
}
