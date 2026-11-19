package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.Eyebrow
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.SafeStatus
import it.spese.domain.finance.SafeToSpendReport
import it.spese.domain.finance.SafeToSpendText
import java.math.BigDecimal

/** Il numero da guardare ogni giorno: quanto puoi ancora spendere al giorno fino a fine mese. Un tocco apre il calcolo voce per voce. */
@Composable
fun SafeToSpendCard(r: SafeToSpendReport, modifier: Modifier = Modifier) {
    val fin = SpeseTheme.finance
    var details by remember { mutableStateOf(false) }
    val color = when (r.status) { SafeStatus.OK -> fin.positive; SafeStatus.BEHIND -> fin.warning; SafeStatus.OVER -> fin.negative; SafeStatus.NOT_ENOUGH -> MaterialTheme.colorScheme.onSurfaceVariant }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { details = true }.padding(20.dp).testTag("safe_to_spend"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(if (r.stale) "Disponibile (dati al ${Dates.fmt(r.today)})" else "Disponibile oggi")
        Text(SafeToSpendText.perDay(r), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
        Text(SafeToSpendText.comment(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.status != SafeStatus.NOT_ENOUGH && r.daysLeft > 0) {
            val used = (r.variableSpent.toFloat() / (r.variableSpent + r.available.max(BigDecimal.ZERO)).max(BigDecimal.ONE).toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { used }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            Text("Tocca per vedere come si calcola", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (details) SafeToSpendDetails(r) { details = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafeToSpendDetails(r: SafeToSpendReport, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Come si calcola", style = MaterialTheme.typography.titleLarge)
            Text("Periodo ${Dates.fmt(r.periodStart)} – ${Dates.fmt(r.periodEnd)} · ${r.daysLeft} giorni compreso oggi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (r.cashSpendable != null) {
                // calcolo dai saldi: soldi sui conti (meno i bloccati) + entrate ancora attese, meno ciò che è già deciso
                Line("Soldi sui conti, non bloccati", r.cashSpendable!!, "saldo meno i soldi negli obiettivi")
                Line("Entrate ancora attese", r.incomeRemaining, "fino al ${Dates.fmt(r.periodEnd)}; già incassate ${Money.eur0(r.incomeToDate)}")
                Line("Risparmio ancora da mettere da parte", r.savingsDue.negate(), if (r.savingsFromGoals) "quote degli obiettivi, meno i versamenti del periodo" else "obiettivo di risparmio mensile del Piano")
                Line("Uscite già note in arrivo", r.upcomingTotal.negate(), if (r.upcoming.isEmpty()) "nessuna" else "${r.upcoming.size} voci, qui sotto")
                Line("Margine di sicurezza", r.safetyReserve.negate(), "quota non spendibile, impostabile")
            } else {
                Line("Entrate attese${if (r.incomeEstimated) "" else " (stima dei mesi passati)"}", r.expectedIncome, "già incassate ${Money.eur0(r.incomeToDate)}")
                Line("Da mettere da parte", r.savingsTarget.negate(), if (r.savingsFromGoals) "somma delle quote dei tuoi obiettivi" else "obiettivo di risparmio mensile del Piano")
                Line("Margine di sicurezza", r.safetyReserve.negate(), "quota non spendibile, impostabile")
                Line("Già speso nel periodo", r.spentToDate.negate(), "di cui spese fisse ${Money.eur0(r.fixedPaid)}")
                Line("Uscite già note in arrivo", r.upcomingTotal.negate(), if (r.upcoming.isEmpty()) "nessuna" else "${r.upcoming.size} voci, qui sotto")
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (r.delayDays > 0 && r.delayUntil != null) {
                Text("Paracadute: considero le entrate fisse in ritardo di ${r.delayDays} giorni, quindi non conto su di esse prima del ${Dates.fmt(r.delayUntil!!)}." + (r.perDayOnTime?.let { v -> " Con entrate puntuali sarebbero ${Money.eur0(v)} al giorno." } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Line("Resta da spendere", r.available, if (r.perDay != null) "${Money.eur0(r.perDay!!)} al giorno" else "", bold = true)
            if (r.upcoming.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("In arrivo", style = MaterialTheme.typography.titleSmall)
                r.upcoming.forEach { u -> Line(u.name, u.amount.negate(), "${Dates.fmt(u.date)} · ${u.source.label}") }
            }
            Spacer(Modifier.height(10.dp))
            Text("È una stima: usa le tue medie, le ricorrenze e gli abbonamenti riconosciuti. Se manca un'uscita (o una è sbagliata) correggila in Ricorrenti o in Abbonamenti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Line(label: String, value: BigDecimal, hint: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Money.eur(value), style = MaterialTheme.typography.titleMedium, fontWeight = if (bold) FontWeight.Bold else null)
    }
}
