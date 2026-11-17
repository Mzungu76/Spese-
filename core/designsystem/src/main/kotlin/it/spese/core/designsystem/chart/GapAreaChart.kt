package it.spese.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.spese.core.common.Money
import it.spese.core.designsystem.theme.SpeseTheme
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un punto del grafico: etichetta sull'asse, entrate e spese del periodo, titolo per la nuvoletta. */
data class GapPoint(val label: String, val income: BigDecimal, val expense: BigDecimal, val title: String)

/**
 * Grafico riepilogativo "entrate contro spese": due curve e, tra l'una e l'altra, un'area **verde** dove si risparmia
 * e **rossa** dove si spende più di quanto si incassa. Si tocca o si trascina per scegliere il periodo: la nuvoletta
 * dice quanto è stato messo da parte (o speso in più). Disegnato a mano: la libreria dei grafici non ha aree tra curve.
 */
@Composable
fun GapAreaChart(points: List<GapPoint>, modifier: Modifier = Modifier, height: Dp = 280.dp, onSelect: (Int) -> Unit = {}) {
    if (points.isEmpty()) { Box(modifier.height(height), contentAlignment = Alignment.Center) { Text("Nessun dato", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    val fin = SpeseTheme.finance
    val green = fin.positive; val red = fin.negative
    val line = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.background
    val selColor = MaterialTheme.colorScheme.onSurface
    val tm = rememberTextMeasurer()
    var sel by remember(points) { mutableIntStateOf(points.lastIndex) }
    var widthPx by remember { mutableIntStateOf(0) }
    // all'apparizione e a ogni cambio di dati le curve si disegnano da sinistra a destra, e le aree si colorano mentre passano
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) { reveal.snapTo(0f); reveal.animateTo(1f, tween(950, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
    // il marcatore scivola da un punto all'altro invece di saltare
    val selPos by animateFloatAsState(sel.toFloat(), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "sel")
    val density = LocalDensity.current
    val maxV = (points.maxOf { maxOf(it.income, it.expense) }.toDouble() * 1.12).takeIf { it > 0 } ?: 1.0
    val labelH = with(density) { 24.dp.toPx() }
    val topPad = with(density) { 56.dp.toPx() }          // spazio per la nuvoletta
    val step = if (points.size > 12) (points.size + 8) / 9 else 1

    fun xOfF(i: Float, w: Float): Float = if (points.size == 1) w / 2f else i * (w / (points.size - 1))
    fun xOf(i: Int, w: Float): Float = xOfF(i.toFloat(), w)
    fun nearest(x: Float, w: Float) = (0 until points.size).minByOrNull { abs(xOf(it, w) - x) } ?: 0

    val p = points[sel]
    val net = p.income - p.expense
    val desc = "Entrate e spese: " + points.joinToString("; ") { "${it.title} ${Money.eur0(it.income - it.expense)}" }

    Box(modifier.fillMaxWidth().height(height).onSizeChanged { widthPx = it.width }.semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxSize().pointerInput(points) { detectTapGestures { sel = nearest(it.x, size.width.toFloat()); onSelect(sel) } }
            .pointerInput(points) { detectHorizontalDragGestures { c, _ -> sel = nearest(c.position.x, size.width.toFloat()); onSelect(sel) } }) {
            val w = size.width; val h = size.height - labelH - topPad
            fun y(v: BigDecimal) = topPad + h - (v.toDouble() / maxV * h).toFloat()
            // griglia
            for (i in 0..3) drawLine(line, Offset(0f, topPad + h * i / 3f), Offset(w, topPad + h * i / 3f), 1f)
            clipRect(left = 0f, top = 0f, right = w * reveal.value + 2f, bottom = size.height) {
            // aree tra le curve, segmento per segmento (con il punto d'incrocio quando le curve si scambiano)
            for (i in 0 until points.size - 1) {
                val x0 = xOf(i, w); val x1 = xOf(i + 1, w)
                val a0 = y(points[i].income); val a1 = y(points[i + 1].income)
                val b0 = y(points[i].expense); val b1 = y(points[i + 1].expense)
                val d0 = (points[i].income - points[i].expense).toDouble(); val d1 = (points[i + 1].income - points[i + 1].expense).toDouble()
                fun poly(pts: List<Offset>, c: Color) { val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }; drawPath(path, c.copy(alpha = 0.32f)) }
                if (d0 * d1 >= 0) poly(listOf(Offset(x0, a0), Offset(x1, a1), Offset(x1, b1), Offset(x0, b0)), c = if (d0 + d1 >= 0) green else red)
                else {
                    val t = (d0 / (d0 - d1)).toFloat(); val xc = x0 + (x1 - x0) * t; val yc = a0 + (a1 - a0) * t
                    poly(listOf(Offset(x0, a0), Offset(xc, yc), Offset(x0, b0)), c = if (d0 > 0) green else red)
                    poly(listOf(Offset(xc, yc), Offset(x1, a1), Offset(x1, b1)), c = if (d1 > 0) green else red)
                }
            }
            // curve
            fun curve(sel: (GapPoint) -> BigDecimal, c: Color) {
                val path = Path()
                points.forEachIndexed { i, pt -> val o = Offset(xOf(i, w), y(sel(pt))); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, c, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            curve({ it.income }, green); curve({ it.expense }, red)
            }
            // selezione
            val sx = xOfF(selPos, w)
            val lo = selPos.toInt().coerceIn(0, points.lastIndex); val hi = (lo + 1).coerceAtMost(points.lastIndex); val f = (selPos - lo).coerceIn(0f, 1f)
            fun lerp(a: BigDecimal, b: BigDecimal) = a.toDouble() + (b.toDouble() - a.toDouble()) * f
            fun yD(v: Double) = topPad + h - (v / maxV * h).toFloat()
            val markerAlpha = ((reveal.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
            drawLine(labelColor.copy(alpha = 0.6f * markerAlpha), Offset(sx, topPad - 6.dp.toPx()), Offset(sx, topPad + h), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            for ((v, c) in listOf(lerp(points[lo].income, points[hi].income) to green, lerp(points[lo].expense, points[hi].expense) to red)) {
                val cy = yD(v)
                drawCircle(bg.copy(alpha = markerAlpha), 7.dp.toPx(), Offset(sx, cy)); drawCircle(c.copy(alpha = markerAlpha), 7.dp.toPx(), Offset(sx, cy), style = Stroke(3.dp.toPx()))
            }
            // etichette asse X
            points.forEachIndexed { i, pt ->
                if (i % step == 0 || i == sel) {
                    val r = tm.measure(pt.label, TextStyle(fontSize = 11.sp, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Medium, color = if (i == sel) selColor else labelColor))
                    val x = (xOf(i, w) - r.size.width / 2f).coerceIn(0f, w - r.size.width)
                    drawText(r, topLeft = Offset(x, topPad + h + 8.dp.toPx()))
                }
            }
        }
        // nuvoletta
        val bubbleW = 196.dp; val bubbleWpx = with(density) { bubbleW.toPx() }
        val bx = (xOfF(selPos, widthPx.toFloat()) - bubbleWpx / 2f).coerceIn(0f, (widthPx - bubbleWpx).coerceAtLeast(0f))
        Column(Modifier.offset { IntOffset(bx.roundToInt(), 0) }.width(bubbleW).shadow(8.dp, RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Crossfade(sel, animationSpec = tween(180), label = "bubble") { i ->
                val q = points[i.coerceIn(0, points.lastIndex)]; val nn = q.income - q.expense
                Column {
                    Text(q.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), maxLines = 1)
            Text(if (nn.signum() >= 0) "+${Money.eur0(nn)} messi da parte" else "${Money.eur0(nn)} spesi in più", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.surface, maxLines = 1)
                }
            }
        }
    }
}
