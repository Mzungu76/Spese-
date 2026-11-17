package it.spese.feature.advice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.advice.MonthReview
import it.spese.domain.advice.ReviewAction

/** Chiusura del mese: quanto hai risparmiato rispetto al piano, come hai diviso le entrate, dove sono andati i soldi e 1–3 azioni da applicare con un tocco. */
@Composable
fun MonthReviewScreen(onBack: () -> Unit, onOpenAdvice: () -> Unit, vm: AdviceViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val applied by vm.applied.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Chiusura del mese", onBack)
        val r = st.review
        if (r == null) {
            EmptyState(Icons.Default.EventAvailable, if (st.loaded) "Nessun mese da chiudere" else "Caricamento…", "Serve almeno un mese concluso con dei movimenti.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Hero(r) }
            item { SplitCard(r, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { CategoriesCard(r, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item {
                SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), title = "Cosa fare ora") {
                    if (r.actions.isEmpty()) Text("Nessuna azione da proporre: il mese è andato in linea con il piano.", style = MaterialTheme.typography.bodyMedium)
                    r.actions.forEach { a -> ActionRow(a, a.text in applied) { vm.apply(a) } }
                }
            }
            item { TextButton(onClick = onOpenAdvice, Modifier.padding(horizontal = 12.dp)) { Icon(Icons.Default.Lightbulb, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tutti i consigli e la guida") } }
        }
    }
}

@Composable
private fun Hero(r: MonthReview) {
    val fin = SpeseTheme.finance
    val ok = r.saved.signum() >= 0
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("Chiusura di ${Dates.monthLabelLong(r.month)}")
        Text(if (ok) "Hai messo da parte" else "Hai speso in più di quanto incassi", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(r.saved.abs(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, colored = false, decimals = false)
        r.savingsRatePercent?.let { Text("$it% delle entrate (${Money.eur0(r.income)})", style = MaterialTheme.typography.bodyMedium, color = if (it >= 0) fin.positive else fin.negative) }
        if (r.goalQuota.signum() > 0) Text(if (r.goalReached) "Quota degli obiettivi (${Money.eur0(r.goalQuota)}) raggiunta, con ${Money.eur0(r.vsGoal)} in più." else "Quota degli obiettivi (${Money.eur0(r.goalQuota)}) mancata di ${Money.eur0(r.vsGoal.abs())}.",
            style = MaterialTheme.typography.bodyMedium, color = if (r.goalReached) fin.positive else fin.warning)
        r.previousExpense?.let { p -> val d = r.expense - p; Text("Spese ${Money.eur0(r.expense)}: " + (if (d.signum() > 0) "+" else "−") + Money.eur0(d.abs()) + " rispetto al mese prima.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SplitCard(r: MonthReview, modifier: Modifier) {
    val s = r.split
    SectionCard(modifier, title = "Come hai diviso le entrate") {
        Bar("Bisogni (fisse e necessarie)", s.needsPercent, 50, "di norma fino al 50%")
        Bar("Desideri (discrezionali)", s.wantsPercent, 30, "di norma fino al 30%")
        Bar("Risparmio", s.savingsPercent, 20, "almeno il 20%", higherIsBetter = true)
        if (r.unclassifiedPercent >= 20) Text("${r.unclassifiedPercent}% delle spese non ha una natura (fissa, necessaria, discrezionale): assegnala alle categorie per una ripartizione più precisa.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bar(label: String, percent: Int, reference: Int, hint: String, higherIsBetter: Boolean = false) {
    val fin = SpeseTheme.finance
    val good = if (higherIsBetter) percent >= reference else percent <= reference
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("$percent%", style = MaterialTheme.typography.titleSmall, color = if (good) fin.positive else fin.warning) }
        LinearProgressIndicator(progress = { (percent / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp), color = if (good) fin.positive else fin.warning, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoriesCard(r: MonthReview, modifier: Modifier) {
    val fin = SpeseTheme.finance
    SectionCard(modifier, title = "Dove sono andati i soldi") {
        r.topCategories.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.category.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text("${c.sharePercent}% delle spese" + if (c.previous.signum() > 0) " · " + (if (c.delta.signum() >= 0) "+" else "−") + Money.eur0(c.delta.abs()) + " sul mese prima" else "", style = MaterialTheme.typography.bodySmall, color = if (c.delta.signum() > 0 && c.previous.signum() > 0) fin.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.eur0(c.amount), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ActionRow(a: ReviewAction, done: Boolean, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(a.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (done) Icon(Icons.Default.CheckCircle, "Fatto", tint = SpeseTheme.finance.positive)
        else FilledTonalButton(onClick = onApply, Modifier.testTag("review_apply")) { Text(if (a is ReviewAction.SetBudget) "Imposta" else "Sposta") }
    }
}
