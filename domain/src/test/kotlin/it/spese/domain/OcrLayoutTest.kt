package it.spese.domain

import it.spese.domain.ocr.OcrLayout
import it.spese.domain.ocr.OcrLine
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    /** Scontrino con descrizione a sinistra e prezzo a destra, fotografato inclinato di [deg] gradi con righe ogni [pitch] pixel. */
    private fun skewed(deg: Double, pitch: Float, rows: List<Pair<String, String>>): List<OcrLine> {
        val a = Math.toRadians(deg); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        fun at(x: Float, y: Float) = (x * c - y * s + 300f) to (x * s + y * c + 200f)
        return rows.flatMapIndexed { i, (d, p) ->
            val y = i * pitch
            val (lx, ly) = at(120f, y); val (rx, ry) = at(900f, y)
            listOf(OcrLine(d, lx, ly, a.toFloat(), pitch * 0.8f), OcrLine(p, rx, ry, a.toFloat(), pitch * 0.8f))
        }
    }

    @Test fun `descrizione e prezzo tornano sulla stessa riga anche con la foto inclinata`() {
        val rows = (1..40).map { "PRODOTTO ${"ABCDEFGHIJKLMNOPQRSTUVWXYZ"[it % 26]}$it" to "10% ${it}.49" }
        for (deg in listOf(0.0, 3.0, -4.0, 6.0)) {
            val out = OcrLayout.rows(skewed(deg, 24f, rows).shuffled(java.util.Random(7))).lines()
            assertEquals("righe a $deg°", 40, out.size)
            rows.forEachIndexed { i, (d, p) -> assertEquals("$d  $p", out[i]) }
        }
    }

    @Test fun `righe vicine non si fondono`() {
        val out = OcrLayout.rows(skewed(0.0, 20f, listOf("TOTALE COMPLESSIVO €" to "80.85", "DI CUI IVA" to "7.82"))).lines()
        assertEquals(listOf("TOTALE COMPLESSIVO €  80.85", "DI CUI IVA  7.82"), out)
    }
}
