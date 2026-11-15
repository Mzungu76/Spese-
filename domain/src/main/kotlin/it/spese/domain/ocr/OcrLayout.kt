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
    fun rows(lines: List<OcrLine>): String {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return ""
        val theta = median(usable.map { it.angleRad })
        val h = median(usable.map { it.height }).coerceAtLeast(6f)
        val cosT = cos(theta); val sinT = sin(theta)
        data class P(val x: Float, val y: Float, val text: String)
        val pts = usable.map { P(it.cx * cosT + it.cy * sinT, -it.cx * sinT + it.cy * cosT, it.text.trim()) }.sortedBy { it.y }
        val rows = ArrayList<MutableList<P>>()
        for (p in pts) {
            val row = rows.lastOrNull()
            val anchor = row?.let { r -> r.map { it.y }.average().toFloat() }
            if (row != null && anchor != null && abs(p.y - anchor) <= h * 0.55f) row.add(p) else rows.add(mutableListOf(p))
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.x }.joinToString("  ") { it.text } }
    }

    /** Angolo (radianti) del bordo superiore di un riquadro dato dai primi due angoli. */
    fun angle(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2(y1 - y0, x1 - x0)

    private fun median(v: List<Float>): Float = v.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}
