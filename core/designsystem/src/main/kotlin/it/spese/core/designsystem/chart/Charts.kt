package it.spese.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import it.spese.core.common.Money
import java.math.BigDecimal
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Serie da disegnare. Vico lavora in virgola mobile *solo per il disegno*: i valori monetari restano BigDecimal
 * in tutto il resto dell'applicazione e vengono convertiti qui, al confine con la libreria grafica.
 */
data class ChartSeries(val name: String, val color: Color, val values: List<BigDecimal>, val xStart: Int = 0)

private fun fmtAxis(v: Double): String {
    val a = kotlin.math.abs(v)
    return if (a >= 1000) (if (a >= 10000) "%.0fk" else "%.1fk").format(java.util.Locale.ITALY, v / 1000) else "%.0f".format(java.util.Locale.ITALY, v)
}

/** Etichette dell'asse X: usa l'indice come posizione. */
private fun labelFormatter(labels: List<String>) = CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } }

/**
 * Grafico a barre (raggruppate o impilate) con linee opzionali sovrapposte (es. saldo, medie).
 * Interattivo: tocco = valori nel marcatore, trascinamento = scorrimento, pizzico = zoom.
 */
@Composable
fun BarLineChart(
    labels: List<String>, bars: List<ChartSeries>, modifier: Modifier = Modifier, lines: List<ChartSeries> = emptyList(),
    stacked: Boolean = false, height: Dp = 220.dp,
    /** false = grafico "statico": niente scorrimento/zoom/animazioni/marcatore. Nelle liste verticali evita conflitti di gesti e rallentamenti. */
    interactive: Boolean = true,
) {
    if (labels.isEmpty() || (bars.isEmpty() && lines.isEmpty())) { Box(modifier.height(height), contentAlignment = Alignment.Center) { Text("Nessun dato", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(labels, bars, lines) {
        producer.runTransaction {
            if (bars.isNotEmpty()) columnSeries { bars.forEach { s -> series(s.values.map { it.toDouble() }) } }
            if (lines.isNotEmpty()) lineSeries { lines.forEach { s -> series(x = s.values.indices.map { s.xStart + it }, y = s.values.map { it.toDouble() }) } }
        }
    }
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val label = rememberTextComponent(color = onSurface)
    val layers = ArrayList<com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer<*>>()
    if (bars.isNotEmpty()) layers.add(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(bars.map { rememberLineComponent(fill = fill(it.color), thickness = if (stacked) 14.dp else 8.dp) }),
            mergeMode = { if (stacked) ColumnCartesianLayer.MergeMode.stacked() else ColumnCartesianLayer.MergeMode.Grouped() },
        ),
    )
    if (lines.isNotEmpty()) layers.add(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(lines.map { LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(it.color))) }),
        ),
    )
    val chart = rememberCartesianChart(
        *layers.toTypedArray(),
        startAxis = VerticalAxis.rememberStart(label = label, valueFormatter = CartesianValueFormatter { _, v, _ -> fmtAxis(v) }),
        bottomAxis = HorizontalAxis.rememberBottom(label = label, valueFormatter = labelFormatter(labels), itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { if (labels.size > 12) 2 else 1 })),
        marker = if (!interactive) null else rememberDefaultCartesianMarker(
            label = rememberTextComponent(color = MaterialTheme.colorScheme.onSurface, padding = com.patrykandpatrick.vico.core.common.Insets(8f, 4f)),
            valueFormatter = { _, targets ->
                val x = targets.firstOrNull()?.x?.toInt() ?: 0
                buildString {
                    append(labels.getOrElse(x) { "" }).append(": ")
                    val vals = ArrayList<String>()
                    targets.forEach { t ->
                        when (t) {
                            is com.patrykandpatrick.vico.core.cartesian.marker.ColumnCartesianLayerMarkerTarget -> t.columns.forEach { c -> vals.add(Money.eur0(BigDecimal.valueOf(c.entry.y))) }
                            is com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget -> t.points.forEach { p -> vals.add(Money.eur0(BigDecimal.valueOf(p.entry.y))) }
                        }
                    }
                    append(vals.joinToString(" · "))
                }
            },
        ),
    )
    Column(modifier) {
        CartesianChartHost(
            chart = chart, modelProducer = producer, modifier = Modifier.fillMaxWidth().height(height),
            scrollState = rememberVicoScrollState(scrollEnabled = interactive),
            zoomState = if (interactive) rememberVicoZoomState() else rememberVicoZoomState(zoomEnabled = false, initialZoom = com.patrykandpatrick.vico.core.cartesian.Zoom.Content),
            animationSpec = if (interactive) androidx.compose.animation.core.tween(250) else null,
            animateIn = interactive,
        )
        Legend((bars + lines).map { it.name to it.color })
    }
}

/** Grafico lineare/area (andamento cumulato, previsioni). Le serie tratteggiate non sono supportate da Vico: si usa un colore attenuato. */
@Composable
fun LineChart(labels: List<String>, series: List<ChartSeries>, modifier: Modifier = Modifier, height: Dp = 220.dp, interactive: Boolean = true) =
    BarLineChart(labels, emptyList(), modifier, lines = series, height = height, interactive = interactive)

@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (n, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(c, CircleShape))
                Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class Slice(val label: String, val value: BigDecimal, val color: Color)

/** Grafico a ciambella interattivo: tocca una fetta per vedere categoria, importo e percentuale. */
@Composable
fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier, size: Dp = 200.dp, centerTitle: String = "Totale") {
    val total = slices.fold(BigDecimal.ZERO) { a, s -> a + s.value }
    var selected by remember(slices) { mutableIntStateOf(-1) }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    if (total.signum() <= 0) { Box(modifier.height(size), contentAlignment = Alignment.Center) { Text("Nessuna spesa nel periodo", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    val sweeps = slices.map { (it.value.toDouble() / total.toDouble() * 360.0).toFloat() }
    val stroke = with(LocalDensity.current) { 34.dp.toPx() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().pointerInput(slices) {
                detectTapGestures { p ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val d = hypot(p.x - c.x, p.y - c.y); val r = minOf(this.size.width, this.size.height) / 2f
                    if (d < r - stroke - 8 || d > r + 8) { selected = -1; return@detectTapGestures }
                    var ang = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f
                    if (ang < 0) ang += 360f
                    var acc = 0f; var hit = -1
                    sweeps.forEachIndexed { i, s -> if (hit < 0 && ang >= acc && ang < acc + s) hit = i; acc += s }
                    selected = if (hit == selected) -1 else hit
                }
            }) {
                val half = stroke / 2f
                val tl = Offset(half, half); val sz = Size(this.size.width - stroke, this.size.height - stroke)
                var start = -90f
                slices.forEachIndexed { i, s ->
                    val w = if (i == selected) stroke + 10f else stroke
                    drawArc(s.color, start, (sweeps[i] - 1f).coerceAtLeast(0.5f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Butt))
                    start += sweeps[i]
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
                if (selected in slices.indices) {
                    val s = slices[selected]
                    Text(s.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                    Text(Money.eur0(s.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(Money.percent(s.value, total).toPlainString().replace('.', ',') + "%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(centerTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.eur0(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Legend(slices.take(10).map { it.label to it.color })
    }
}

data class BarItem(val label: String, val value: BigDecimal, val color: Color, val detail: String? = null)

/** Barre orizzontali proporzionali (principali esercenti, confronti tra categorie). */
@Composable
fun HorizontalBars(items: List<BarItem>, modifier: Modifier = Modifier) {
    val max = items.maxOfOrNull { it.value.abs() }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { b ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(b.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(Money.eur0(b.value) + (b.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Box(Modifier.fillMaxWidth((b.value.abs().toDouble() / max.toDouble()).toFloat().coerceIn(0.01f, 1f)).height(8.dp).background(b.color, CircleShape))
                }
            }
        }
    }
}

/** Anello di avanzamento (obiettivo di risparmio): progresso 0..1, oltre 1 resta pieno. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 72.dp, color: Color = MaterialTheme.colorScheme.primary, label: String = "") {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
