package it.spese.feature.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import it.spese.app.common.RoundIconButton
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.Goal
import it.spese.domain.finance.BudgetPlan
import it.spese.domain.goals.GoalEngine
import it.spese.domain.goals.GoalProgress
import it.spese.domain.goals.GoalStatus
import java.math.BigDecimal
import java.time.LocalDate

/** Obiettivi di risparmio: ognuno con la sua quota mensile; la somma decide quanto «Disponibile oggi» tiene da parte. */
@Composable
fun GoalsScreen(onBack: () -> Unit, vm: GoalsViewModel = hiltViewModel()) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val progress = remember(goals) { GoalEngine.all(goals, today) }
    var edit by remember { mutableStateOf<Goal?>(null) }
    var deposit by remember { mutableStateOf<Goal?>(null) }
    var budgets by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Obiettivi", onBack, actions = {
            RoundIconButton(Icons.Default.Add, "Nuovo obiettivo", { edit = Goal(0, "", BigDecimal.ZERO) }, Modifier.testTag("goal_add"), primary = true)
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("Prima metti da parte, poi spendi: ogni obiettivo ha una quota mensile (scelta da te, o calcolata dalla scadenza). La somma delle quote è ciò che «Disponibile oggi» riserva ogni mese.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress.isNotEmpty()) item {
                SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Eyebrow("Da mettere da parte ogni mese")
                    MoneyText(GoalEngine.monthlyTotal(goals, today), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, colored = false)
                    plan?.let { p -> Text(if (p.income.signum() > 0) "Sono il ${Money.percent(p.savings, p.income, 0).toInt()}% delle tue entrate medie (${Money.eur0(p.income)})." else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (plan != null) OutlinedButton(onClick = { budgets = true }, Modifier.testTag("goal_budgets")) { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Genera i budget") }
                }
            }
            if (progress.isEmpty()) item { EmptyState(Icons.Default.Savings, "Nessun obiettivo", "Tocca + per aggiungerne uno: fondo di emergenza, vacanza, investimento… Senza obiettivi si usa quello unico del Piano.") }
            items(progress, key = { it.goal.id }) { p -> GoalRow(p, { edit = p.goal }, { deposit = p.goal }) }
        }
    }
    edit?.let { g -> GoalDialog(g, { edit = null }, vm) }
    deposit?.let { g -> DepositDialog(g, { deposit = null }) { amount -> vm.deposit(g, amount) } }
    if (budgets) plan?.let { p -> BudgetDialog(p, { budgets = false }) { vm.applyBudgets(p); budgets = false } }
}

@Composable
private fun GoalRow(p: GoalProgress, onEdit: () -> Unit, onDeposit: () -> Unit) {
    val fin = SpeseTheme.finance
    val color = when (p.status) { GoalStatus.COMPLETED, GoalStatus.ON_TRACK -> fin.positive; GoalStatus.LATE -> fin.warning; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.goal.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (p.monthlyQuota.signum() > 0) Text(Money.eur0(p.monthlyQuota) + "/mese", style = MaterialTheme.typography.titleSmall)
        }
        LinearProgressIndicator(progress = { p.percent / 100f }, Modifier.fillMaxWidth().height(8.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text("${Money.eur0(p.goal.saved)} di ${Money.eur0(p.goal.target)} · ${p.percent}%", style = MaterialTheme.typography.bodyMedium)
        Text(GoalEngine.describe(p), style = MaterialTheme.typography.bodySmall, color = if (p.status == GoalStatus.LATE) fin.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        if (p.status != GoalStatus.COMPLETED) TextButton(onClick = onDeposit, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.AddCircleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Registra un versamento") }
    }
}

@Composable
private fun GoalDialog(g: Goal, onDismiss: () -> Unit, vm: GoalsViewModel) {
    var name by remember { mutableStateOf(g.name) }
    var target by remember { mutableStateOf(if (g.target.signum() > 0) g.target.stripTrailingZeros().toPlainString().replace('.', ',') else "") }
    var saved by remember { mutableStateOf(if (g.saved.signum() > 0) g.saved.stripTrailingZeros().toPlainString().replace('.', ',') else "") }
    var monthly by remember { mutableStateOf(g.monthly?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "") }
    var deadline by remember { mutableStateOf(g.deadline) }
    var enabled by remember { mutableStateOf(g.enabled) }
    val t = parseAmountOrNull(target)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (g.id == 0L) "Nuovo obiettivo" else "Modifica obiettivo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome (es. Fondo di emergenza)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(target, { target = it }, "Importo da raggiungere", Modifier.fillMaxWidth())
                AmountField(saved, { saved = it }, "Già messo da parte", Modifier.fillMaxWidth())
                DateField(deadline, { deadline = it }, "Scadenza (facoltativa)", Modifier.fillMaxWidth(), clearable = true, onClear = { deadline = null })
                AmountField(monthly, { monthly = it }, "Quota mensile (vuoto = dalla scadenza)", Modifier.fillMaxWidth(), supporting = "Con una scadenza, la quota è quella che serve per arrivarci in tempo")
                LabeledSwitch("Attivo", enabled, { enabled = it }, supporting = "Se spento, non accantona nulla")
                if (g.id != 0L) TextButton(onClick = { vm.delete(g.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Elimina") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && (t?.signum() ?: 0) > 0, onClick = {
            vm.save(g.copy(name = name, target = t ?: BigDecimal.ZERO, saved = parseAmountOrNull(saved) ?: BigDecimal.ZERO, deadline = deadline, monthly = parseAmountOrNull(monthly)?.takeIf { it.signum() > 0 }, enabled = enabled)); onDismiss() }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun DepositDialog(g: Goal, onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Versamento per «${g.name}»") },
        text = { AmountField(amount, { amount = it }, "Importo", Modifier.fillMaxWidth(), supporting = "Un importo negativo toglie dal totale messo da parte") },
        confirmButton = { TextButton(enabled = (parseAmountOrNull(amount)?.signum() ?: 0) != 0, onClick = { onConfirm(parseAmountOrNull(amount) ?: BigDecimal.ZERO); onDismiss() }) { Text("Registra") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun BudgetDialog(p: BudgetPlan, onDismiss: () -> Unit, onApply: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Budget mensili proposti") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Calcolati su ${p.months} ${if (p.months == 1) "mese" else "mesi"} di storico: entrate ${Money.eur0(p.income)}, da mettere da parte ${Money.eur0(p.savings)}.", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    p.shortfall.signum() > 0 -> "Attenzione: anche azzerando le spese discrezionali mancano ${Money.eur0(p.shortfall)} al mese. Conviene abbassare le quote degli obiettivi."
                    p.cutPercent > 0 -> "Per rispettare gli obiettivi le spese discrezionali scendono del ${p.cutPercent}%; le altre restano alla tua media."
                    else -> "Le tue spese di sempre stanno già dentro gli obiettivi: i budget sono la tua media."
                }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                p.proposals.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.category.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text("media ${Money.eur0(b.average)}" + (b.current?.let { " · ora ${Money.eur0(it)}" } ?: "") + if (b.cutPercent > 0) " · −${b.cutPercent}%" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Money.eur0(b.proposed), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text("Sostituiscono i budget già impostati sulle categorie elencate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Imposta i budget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
