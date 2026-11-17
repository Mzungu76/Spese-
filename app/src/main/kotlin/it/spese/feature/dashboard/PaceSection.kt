package it.spese.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.BarItem
import it.spese.core.designsystem.chart.BarLineChart
import it.spese.core.designsystem.chart.ChartSeries
import it.spese.core.designsystem.chart.HorizontalBars
import it.spese.core.designsystem.chart.LineChart
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.finance.PaceReport
import it.spese.domain.finance.PaceUnit
import it.spese.domain.finance.PaceVerdict
import java.math.BigDecimal

/**
 * «Sto spendendo in linea con il solito?»: avanzamento del periodo in corso (settimana, mese o trimestre, secondo il filtro) contro la
 * media di tutti i periodi passati, con tre grafici (andamento cumulato, periodi contro entrate, categorie che fanno la differenza).
 */
@Composable
fun PaceSection(r: PaceReport, modifier: Modifier = Modifier) {
    val fin = SpeseTheme.finance
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val unitName = r.unit.label
    val verdictColor = when (r.verdict) { PaceVerdict.ABOVE -> fin.negative; PaceVerdict.BELOW -> fin.positive; PaceVerdict.IN_LINE -> fin.positive; PaceVerdict.NOT_ENOUGH -> MaterialTheme.colorScheme.onSurfaceVariant }
    SectionCard(modifier, title = "Ritmo di spesa · ${unitName.replaceFirstChar { it.uppercase() }} in corso") {
        Text(r.verdict.label + (r.deltaPercent?.takeIf { r.verdict != PaceVerdict.NOT_ENOUGH }?.let { " (${if (it.signum() > 0) "+" else ""}$it%)" } ?: ""),
            style = MaterialTheme.typography.titleMedium, color = verdictColor)
        if (r.verdict == PaceVerdict.NOT_ENOUGH) {
            Text("Servono almeno 2 ${if (r.unit == PaceUnit.WEEK) "settimane" else if (r.unit == PaceUnit.MONTH) "mesi" else "trimestri"} completi di movimenti per confrontare l'andamento. Intanto: speso finora ${Money.eur0(r.spentToDate)}, al ritmo attuale ${Money.eur0(r.projectedAtCurrentPace)} a fine ${unitName}.",
                style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text("Al giorno ${r.dayIndex + 1} di ${r.days} hai speso ${Money.eur0(r.spentToDate)}; di solito a questo punto ${Money.eur0(r.averageToDate)} " +
            "(${if (r.deltaToDate.signum() >= 0) "+" else "−"}${Money.eur0(r.deltaToDate.abs())}), in media su ${r.pastWindows.size} ${if (r.unit == PaceUnit.WEEK) "settimane" else if (r.unit == PaceUnit.MONTH) "mesi" else "trimestri"} passati.",
            style = MaterialTheme.typography.bodyMedium)
        PillSegmented(listOf(0 to "Andamento", 1 to "Periodi", 2 to "Categorie"), tab, { tab = it }, Modifier.padding(vertical = 6.dp))
        when (tab) {
            0 -> {
                val labels = (0 until r.days).map { i -> if (r.unit == PaceUnit.WEEK) listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")[i % 7] else "${i + 1}" }
                LineChart(labels, listOf(
                    ChartSeries("Massimo storico", MaterialTheme.colorScheme.outlineVariant, r.high),
                    ChartSeries("Minimo storico", MaterialTheme.colorScheme.outlineVariant, r.low),
                    ChartSeries("Media", MaterialTheme.colorScheme.onSurfaceVariant, r.average),
                    ChartSeries("Questo $unitName", verdictColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary, r.current),
                ), height = 220.dp, interactive = false)
                Text("La linea colorata è la spesa cumulata di ora; la grigia è la media dei periodi passati; la fascia chiara va dal minimo al massimo storico.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> {
                val labels = r.pastWindows.takeLast(10).map { it.label } + "Ora"
                val past = r.pastWindows.takeLast(10)
                BarLineChart(labels,
                    bars = listOf(ChartSeries("Speso", MaterialTheme.colorScheme.primary, past.map { it.total } + r.projectedWithAverage)),
                    lines = listOf(ChartSeries("Spesa media", MaterialTheme.colorScheme.onSurfaceVariant, labels.map { r.averageTotal }),
                        ChartSeries("Entrate medie", fin.positive, labels.map { r.averageIncome })), height = 220.dp, interactive = false)
                Text("Ogni barra è un $unitName passato; «Ora» è la stima di chiusura se il resto va come al solito. Quando la barra supera la linea verde, spendi più di quanto entra.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                if (r.drivers.isEmpty()) Text("Nessuna categoria si discosta in modo rilevante dalla media.", style = MaterialTheme.typography.bodyMedium)
                else {
                    HorizontalBars(r.drivers.map { d ->
                        BarItem(d.category?.name ?: "Senza categoria", d.delta.abs(), if (d.delta.signum() > 0) fin.negative else fin.positive,
                            (if (d.delta.signum() > 0) "sopra" else "sotto") + " (${Money.eur0(d.current)} vs ${Money.eur0(d.average)})")
                    })
                    Text("Rosso: spendi più del solito in questa categoria; verde: meno. È dove conviene intervenire per rientrare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Metric("Chiusura stimata (resto come al solito)", Money.eur0(r.projectedWithAverage), "media storica ${Money.eur0(r.averageTotal)}")
        Metric("Chiusura al ritmo attuale", Money.eur0(r.projectedAtCurrentPace), if (r.projectedAtCurrentPace > r.averageTotal) "sopra la media" else "sotto la media")
        Metric("Entrate attese nel ${unitName}", Money.eur0(r.expectedIncome), "già incassato ${Money.eur0(r.incomeToDate)}")
        Metric("Margine atteso a fine ${unitName}", (if (r.expectedMargin.signum() < 0) "−" else "") + Money.eur0(r.expectedMargin.abs()), "margine medio ${Money.eur0(r.averageMargin)}",
            if (r.expectedMargin < r.averageMargin) fin.warning else fin.positive)
        r.dailyAllowance?.let { a ->
            Metric("Puoi spendere al giorno da qui a fine ${unitName}", (if (a.signum() < 0) "−" else "") + Money.eur0(a.abs()),
                "per mantenere il risparmio medio · di solito ${Money.eur0(r.averageDailyRemaining ?: BigDecimal.ZERO)} al giorno", if (a.signum() < 0) fin.negative else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, hint: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
