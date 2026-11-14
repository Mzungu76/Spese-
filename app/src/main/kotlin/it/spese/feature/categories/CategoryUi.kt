package it.spese.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
fun CategoryRankRow(row: CategoryRow, kind: TxKind, maxTotal: BigDecimal, onClick: () -> Unit, modifier: Modifier = Modifier, budget: BudgetUsage? = null) {
    val c = row.category
    val color = c?.color?.toColor() ?: MaterialTheme.colorScheme.outline
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CategoryAvatar(c, size = 48)
        Column(Modifier.weight(1f)) {
            Text(c?.name ?: "Senza categoria", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((row.total.toFloat() / maxTotal.toFloat().coerceAtLeast(1f)).coerceIn(0.03f, 1f)).height(4.dp).background(color))
            }
            Text(buildString { append(row.percentOfExpense.toPlainString().replace('.', ',')).append("% · ").append(row.count).append(if (row.count == 1) " movimento" else " movimenti") },
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
