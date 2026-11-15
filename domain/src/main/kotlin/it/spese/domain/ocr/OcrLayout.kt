package it.spese.domain.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Un frammento di testo riconosciuto con la sua posizione nella foto (centro in pixel, inclinazione e altezza della riga). */
data class OcrLine(val text: String, val cx: Float, val cy: Float, val angleRad: Float, val height: Float)

/**
 * Ricostruisce le righe dello scontrino dalla posizione dei frammenti: descrizione a sinistra, IVA e prezzo a destra tornano sulla
 * stessa riga anche se la foto è inclinata (si raddrizzano le coordinate con l'inclinazione mediana) e senza unire righe vicine
 * (la tolleranza è metà dell'altezza del testo, non un numero fisso di pixel).
 */
object OcrLayout {
    private val NUMERIC = Regex("^[\\s\\d.,%€*+-]*\\d[\\s\\d.,%€*+-]*$")
    private val ENDS_WITH_AMOUNT = Regex("-?\\d+[.,]\\d{2}\\s*[A-Za-z]{0,2}$")

    private class P(val x: Float, val y: Float, val text: String)
    private class Row(var y: Float, val frags: MutableList<P>) { fun text() = frags.sortedBy { it.x }.joinToString("  ") { it.text } }

    private fun cluster(pts: List<P>, tol: Float): List<Row> {
        val rows = ArrayList<Row>()
        for (p in pts.sortedBy { it.y }) {
            val r = rows.lastOrNull()
            if (r != null && abs(p.y - r.y) <= tol) { r.frags.add(p); r.y = r.frags.map { it.y }.average().toFloat() } else rows.add(Row(p.y, mutableListOf(p)))
        }
        return rows
    }

    fun rows(lines: List<OcrLine>): String {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return ""
        val theta = median(usable.map { it.angleRad })
        val h = median(usable.map { it.height }).coerceAtLeast(6f)
        val cosT = cos(theta); val sinT = sin(theta)
        val pts = usable.map { P(it.cx * cosT + it.cy * sinT, -it.cx * sinT + it.cy * cosT, it.text.trim()) }
        val numeric = pts.filter { NUMERIC.matches(it.text) }
        val words = pts.filter { !NUMERIC.matches(it.text) }
        if (numeric.isEmpty() || words.isEmpty()) return cluster(pts, h * 0.55f).joinToString("\n") { it.text() }

        // colonna delle descrizioni a sinistra e dei numeri (IVA, prezzo) a destra: prospettiva e curvatura della carta le fanno scivolare
        // piano piano una rispetto all'altra (anche di più righe verso il fondo), quindi si abbinano in ordine seguendo lo scarto che cresce
        val left = cluster(words, h * 0.45f)
        val right = cluster(numeric, h * 0.45f).toMutableList()
        val out = ArrayList<Pair<Float, String>>()
        var offset = 0f
        for (l in left) {
            val text = l.text()
            if (ENDS_WITH_AMOUNT.containsMatchIn(text)) { out.add(l.y to text); continue }     // già completa: prezzo sulla stessa riga
            val best = right.minByOrNull { abs(it.y - l.y - offset) }
            if (best != null && abs(best.y - l.y - offset) <= h * 0.8f) {
                out.add(l.y to text + "  " + best.text()); right.remove(best)
                offset = offset * 0.7f + (best.y - l.y) * 0.3f
            } else out.add(l.y to text)
        }
        right.forEach { out.add((it.y - offset) to it.text()) }                                    // numeri rimasti senza descrizione
        return out.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    /** Angolo (radianti) del bordo superiore di un riquadro dato dai primi due angoli. */
    fun angle(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2(y1 - y0, x1 - x0)

    private fun median(v: List<Float>): Float = v.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}
