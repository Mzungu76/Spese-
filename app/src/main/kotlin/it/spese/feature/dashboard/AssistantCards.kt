package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.DailyBudget
import it.spese.domain.finance.DailyStatus
import it.spese.domain.finance.IncomeSource
import it.spese.domain.finance.Insight
import it.spese.domain.finance.Tone
import java.time.LocalDate

@Composable
fun toneColor(t: Tone): Color = when (t) { Tone.PRAISE -> SpeseTheme.finance.positive; Tone.TIP -> MaterialTheme.colorScheme.primary; Tone.WARNING -> SpeseTheme.finance.warning }

fun toneIcon(t: Tone) = when (t) { Tone.PRAISE -> Icons.Default.EmojiEvents; Tone.TIP -> Icons.Default.Lightbulb; Tone.WARNING -> Icons.Default.Warning }

/**
 * La risposta alla domanda di ogni giorno: "quanto posso spendere oggi?".
 * Entrate attese − obiettivo di risparmio − spese annuali spalmate − uscite fisse = budget per le spese variabili.
 */
@Composable
fun DailyBudgetCard(d: DailyBudget, lastTransaction: LocalDate?, onSetup: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val fin = SpeseTheme.finance
    val color = when (d.status) { DailyStatus.GOOD -> fin.positive; DailyStatus.CAUTION -> fin.warning; DailyStatus.OVER -> MaterialTheme.colorScheme.error; DailyStatus.SETUP -> MaterialTheme.colorScheme.outline }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("daily_card").clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.status == DailyStatus.SETUP) {
            Eyebrow("Quanto puoi spendere")
            Text("Dimmi quanto entra ogni mese e che obiettivo di risparmio hai: ti dirò ogni giorno quanto puoi spendere.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onSetup, Modifier.testTag("daily_setup")) { Text("Imposta il tuo piano") }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow(if (d.status == DailyStatus.OVER) "Budget per le spese variabili" else "Oggi puoi spendere", Modifier.weight(1f))
            Text(when (d.status) { DailyStatus.GOOD -> "In linea"; DailyStatus.CAUTION -> "Vai piano"; DailyStatus.OVER -> if (d.overcommitted) "Piano da rivedere" else "Esaurito"; DailyStatus.SETUP -> "" },
                Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
        }
        if (d.status == DailyStatus.OVER) {
            Text(if (d.available.signum() < 0) "−" + Money.eur0(d.available.abs()) else "0 €", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
            Text(if (d.overcommitted) "Obiettivo, spese annuali e uscite fisse superano da sole le entrate attese: rivedi il piano."
                else if (d.available.signum() < 0) "Hai superato il budget per le spese variabili di ${Money.eur0(d.available.abs())}. Per il resto del mese conviene limitarsi all'essenziale."
                else "Hai usato tutto il budget per le spese variabili: per il resto del mese evita spese extra.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyText(d.perDay, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, colored = false, decimals = false)
                Text("al giorno", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        UsageBar(d.usedFraction, Modifier.fillMaxWidth(), over = d.status == DailyStatus.OVER)
        Text("Speso ${Money.eur0(d.variableSpent)} su ${Money.eur0(d.variableBudget)} per le spese variabili · ${if (d.daysLeft == 1) "ultimo giorno" else "${d.daysLeft} giorni"} alla fine del mese" +
            (if (d.status != DailyStatus.OVER) " · restano ${Money.eur0(d.available)}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.fixedRemaining.signum() > 0) Text("Uscite fisse ancora da pagare: ${Money.eur0(d.fixedRemaining)} (" + d.fixedRemainingItems.take(3).joinToString(", ") { it.first } + ")", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lastTransaction != null && d.staleDays > 3) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dati fermi al ${Dates.fmt(lastTransaction)}: aggiornali per un numero affidabile.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = fin.warning)
            TextButton(onClick = onImport) { Text("Importa") }
        }
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Nascondi il calcolo" else "Come è calcolato") }
        if (open) {
            val src = when (d.incomeSource) { IncomeSource.GOAL -> "indicate nel piano"; IncomeSource.HISTORY -> "media degli ultimi mesi"; IncomeSource.THIS_MONTH -> "incassate questo mese"; IncomeSource.NONE -> "" }
            @Composable fun line(label: String, v: java.math.BigDecimal, plus: Boolean? = null) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium); Text((if (plus == false) "− " else "") + Money.eur0(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            line("Entrate attese ($src)", d.expectedIncome)
            line("Obiettivo di risparmio", d.savingsTarget, false)
            if (d.annualAccrual.signum() > 0) line("Spese annuali (quota mensile)", d.annualAccrual, false)
            line("Uscite fisse del mese", d.fixedTotal, false)
            HorizontalDivider()
            line("Per le spese variabili", d.variableBudget)
        }
    }
}

/** Invito a configurare il piano (entrate, obiettivo, budget) a partire dai movimenti importati. */
@Composable
fun SetupPromptCard(onSetup: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onSetup).testTag("setup_prompt").padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Column(Modifier.weight(1f)) {
            Text("Imposta il tuo piano", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Ti propongo entrate, obiettivo e budget partendo dai tuoi movimenti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun InsightRow(i: Insight, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = toneColor(i.tone)
    Row(modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(c.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(toneIcon(i.tone), null, Modifier.size(20.dp), tint = c) }
        Column(Modifier.weight(1f)) {
            Text(i.title, style = MaterialTheme.typography.titleSmall)
            Text(i.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Consigli e riconoscimenti sui dati dell'utente, con accesso a "Il tuo mese". */
@Composable
fun CoachCard(insights: List<Insight>, onOpenReport: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier.testTag("coach_card"), title = "Per te") {
        insights.forEach { InsightRow(it) }
        TextButton(onClick = onOpenReport, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("open_report")) { Text("Il tuo mese, fondo di emergenza e spese annuali") }
    }
}
