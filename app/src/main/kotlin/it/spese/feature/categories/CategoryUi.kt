package it.spese.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.core.model.SpendNature
import it.spese.core.model.TxKind
import it.spese.domain.finance.BudgetUsage
import it.spese.domain.finance.CategoryRow
import it.spese.domain.finance.Period
import java.math.BigDecimal

/** Variazione rispetto al periodo precedente: freccia e percentuale; "nuova" se prima era a zero. Gli aumenti significativi di spesa sono evidenziati. */
@Composable
fun ChangeChip(row: CategoryRow, kind: TxKind, modifier: Modifier = Modifier) {
    val prev = row.previousTotal ?: return
    val fin = SpeseTheme.finance
    val pct = row.changePercent
    if (pct == null) {
        if (prev.signum() == 0 && row.total.signum() > 0) Text("nuova", modifier, style = MaterialTheme.typography.labelMedium, color = if (kind == TxKind.ENTRATA) fin.positive else fin.warning, fontWeight = FontWeight.SemiBold)
        return
    }
    if (kind == TxKind.SPESA && row.surging)
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(fin.negative.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)) { DeltaBadge(pct, upIsGood = false) }
    else DeltaBadge(pct, upIsGood = kind == TxKind.ENTRATA, modifier = modifier)
}

/**
 * Riga di classifica: icona, nome, barra proporzionale al massimo, quota sul totale, importo e variazione.
 * Con un budget impostato mostra anche quanto ne è stato usato nel mese di riferimento.
 */
@Composable
fun CategoryRankRow(row: CategoryRow, kind: TxKind, maxTotal: BigDecimal, onClick: () -> Unit, modifier: Modifier = Modifier, budget: BudgetUsage? = null, showNature: Boolean = false) {
    val c = row.category
    val color = c?.color?.toColor() ?: MaterialTheme.colorScheme.outline
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CategoryAvatar(c, size = 48)
        Column(Modifier.weight(1f)) {
            Text(c?.name ?: "Senza categoria", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((row.total.toFloat() / maxTotal.toFloat().coerceAtLeast(1f)).coerceIn(0.03f, 1f)).height(4.dp).background(color))
            }
            Text(buildString { append(row.percentOfExpense.toPlainString().replace('.', ',')).append("% · ").append(row.count).append(if (row.count == 1) " movimento" else " movimenti"); if (showNature) c?.nature?.let { append(" · ").append(it.label) } },
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (budget != null) {
                UsageBar(budget.spent.toFloat() / budget.budget.toFloat().coerceAtLeast(1f), Modifier.padding(top = 6.dp), over = budget.exceeded)
                Text("Budget ${Money.eur0(budget.spent)} su ${Money.eur0(budget.budget)}", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (budget.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MoneyText(row.total, decimals = false, colored = kind == TxKind.ENTRATA, style = MaterialTheme.typography.titleMedium)
            ChangeChip(row, kind)
        }
    }
}

/** Selettore del periodo condiviso (Mese / 3 mesi / Anno / Tutto): i periodi non previsti ricadono su "Tutto". */
@Composable
fun PeriodPills(period: Period, onSet: (Period) -> Unit, modifier: Modifier = Modifier) {
    val shown: Period = when (period) { Period.All, Period.YearToDate, Period.Last3Months, Period.CurrentMonth -> period; else -> Period.All }
    PillSegmented(listOf<Pair<Period, String>>(Period.CurrentMonth to "Mese", Period.Last3Months to "3 mesi", Period.YearToDate to "Anno", Period.All to "Tutto"), shown, onSet, modifier)
}

private fun natureColorOf(n: SpendNature?, scheme: androidx.compose.material3.ColorScheme, warn: androidx.compose.ui.graphics.Color) = when (n) {
    SpendNature.FIXED -> scheme.primary; SpendNature.ESSENTIAL -> scheme.tertiary; SpendNature.DISCRETIONARY -> warn; null -> scheme.outline
}

/** Spesa del periodo ripartita per natura: barra a segmenti e legenda. Con categorie da definire offre l'impostazione automatica. */
@Composable
fun NatureCard(totals: Map<SpendNature?, BigDecimal>, pendingCount: Int, onAuto: () -> Unit, modifier: Modifier = Modifier) {
    val sum = totals.values.fold(BigDecimal.ZERO) { a, b -> a + b }
    if (sum.signum() <= 0) return
    val warn = SpeseTheme.finance.warning
    val scheme = MaterialTheme.colorScheme
    val order = listOf<SpendNature?>(SpendNature.FIXED, SpendNature.ESSENTIAL, SpendNature.DISCRETIONARY, null)
    SectionCard(modifier, title = "Su cosa puoi incidere") {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceVariant)) {
            order.forEach { n -> val v = totals[n] ?: BigDecimal.ZERO; if (v.signum() > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(natureColorOf(n, scheme, warn))) }
        }
        order.forEach { n ->
            val v = totals[n] ?: BigDecimal.ZERO
            if (v.signum() > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(natureColorOf(n, scheme, warn)))
                Column(Modifier.weight(1f)) {
                    Text(n?.label ?: "Da definire", style = MaterialTheme.typography.titleSmall)
                    Text(when (n) { SpendNature.FIXED -> "Difficile da ridurre senza cambiare contratto o abitudine"; SpendNature.ESSENTIAL -> "Si può ottimizzare, non eliminare"; SpendNature.DISCRETIONARY -> "Il margine di risparmio più concreto"; null -> "Categorie senza natura impostata" },
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(v, decimals = false, colored = false, style = MaterialTheme.typography.titleSmall)
                    Text(Money.percent(v, sum, 0).toPlainString() + "%", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (pendingCount > 0) TextButton(onClick = onAuto, contentPadding = PaddingValues(0.dp)) { Text("Proponi la natura di $pendingCount categorie") }
    }
}

/** Categorie di spesa in aumento significativo rispetto al periodo precedente. */
@Composable
fun WatchCard(rows: List<CategoryRow>, previousLabel: String?, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    SectionCard(modifier, title = "Da tenere d'occhio") {
        Text("Spese salite " + (previousLabel ?: "rispetto al periodo precedente"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { r.category?.id?.let(onOpen) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryAvatar(r.category, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(r.category?.name ?: "Senza categoria", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("da ${Money.eur0(r.previousTotal ?: BigDecimal.ZERO)} a ${Money.eur0(r.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.eurSigned(r.delta ?: BigDecimal.ZERO).replace(",00", ""), color = SpeseTheme.finance.negative, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Dove si può risparmiare: le spese discrezionali più pesanti, con il risparmio di un taglio del 20%. */
@Composable
fun OpportunitiesCard(list: List<it.spese.domain.finance.Opportunity>, onOpen: (Long) -> Unit, modifier: Modifier = Modifier, percent: Int = 20) {
    if (list.isEmpty()) return
    val monthly = list.fold(BigDecimal.ZERO) { a, o -> a + o.monthlySaving }
    SectionCard(modifier, title = "Dove puoi risparmiare") {
        Text("Un taglio del $percent% su queste spese libererebbe circa ${Money.eur0(monthly)} al mese (${Money.eur0(monthly.multiply(BigDecimal(12)))} all'anno).", style = MaterialTheme.typography.bodyMedium)
        list.forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { o.row.category?.id?.let(onOpen) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryAvatar(o.row.category, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(o.row.category?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("spendi ${Money.eur0(o.row.monthlyAvg)} al mese", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("−" + Money.eur0(o.monthlySaving) + "/mese", color = SpeseTheme.finance.positive, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
