package it.spese.domain.ocr

import kotlin.math.abs

/**
 * Lettura a strisce: ML Kit riduce le immagini grandi, quindi su uno scontrino lungo e stretto i caratteri piccoli si perdono.
 * Si divide la foto in fasce sovrapposte, ognuna letta a piena risoluzione, e si riuniscono le righe senza doppioni.
 */
object OcrTiles {
    /** Fasce verticali (top until bottom) alte circa 1,3 volte la larghezza, con il 25% di sovrapposizione. Un'immagine non alta resta intera. */
    fun plan(width: Int, height: Int): List<IntRange> {
        if (width <= 0 || height <= 0) return emptyList()
        val tile = maxOf((width * 1.3f).toInt(), 900)
        if (height <= (tile * 1.25f).toInt()) return listOf(0 until height)
        val step = (tile * 0.75f).toInt()
        val out = ArrayList<IntRange>()
        var top = 0
        while (top < height) {
            val bottom = minOf(height, top + tile)
            // l'ultima fascia non deve restare troppo corta: si prolunga all'indietro
            out.add((if (bottom == height) maxOf(0, height - tile) else top) until bottom)
            if (bottom == height) break
            top += step
        }
        return out.distinct()
    }

    /**
     * Riunisce le righe delle fasce (coordinate già riferite all'immagine intera). Nelle zone di sovrapposizione la stessa riga compare
     * due volte: si tiene quella più sicura (a parità, quella più lontana dal bordo della sua fascia, dove l'OCR taglia i caratteri).
     */
    fun mergeStrips(strips: List<Pair<IntRange, List<OcrLine>>>): List<OcrLine> {
        class Entry(val line: OcrLine, val edge: Float)   // distanza dal bordo della fascia: più grande = più centrale
        val all = strips.flatMap { (range, lines) -> lines.map { Entry(it, minOf(it.cy - range.first, range.last - it.cy)) } }
        val kept = ArrayList<Entry>()
        for (e in all.sortedByDescending { it.line.confidence * 1000f + it.edge }) {
            val twin = kept.any { k ->
                abs(k.line.cy - e.line.cy) <= maxOf(k.line.height, e.line.height) * 0.7f && abs(k.line.cx - e.line.cx) <= maxOf(k.line.height, e.line.height) * 3f &&
                    ReceiptStitcher.key(k.line.text).let { a -> ReceiptStitcher.key(e.line.text).let { b -> a == b || (a.length > 3 && b.length > 3 && (a.contains(b) || b.contains(a))) } }
            }
            // righe senza lettere (solo numeri) si confrontano per posizione e testo esatto
            val twinNumeric = ReceiptStitcher.key(e.line.text).isEmpty() && kept.any { k ->
                k.line.text.trim() == e.line.text.trim() && abs(k.line.cy - e.line.cy) <= maxOf(k.line.height, e.line.height) * 0.7f && abs(k.line.cx - e.line.cx) <= e.line.height * 3f
            }
            if (!twin && !twinNumeric) kept.add(e)
        }
        return kept.map { it.line }
    }

    /** Riquadro che contiene tutto il testo, con un margine: toglie tavolo e sfondo, concentrando la lettura (e il contrasto) sulla carta. */
    fun textBounds(lines: List<OcrLine>, width: Int, height: Int, marginFraction: Float = 0.04f): IntArray? {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.size < 3) return null
        val h = usable.map { it.height }.sorted()[usable.size / 2]
        val minX = usable.minOf { it.cx } ; val maxX = usable.maxOf { it.cx }
        val minY = usable.minOf { it.cy }; val maxY = usable.maxOf { it.cy }
        val mx = maxOf(h * 2, width * marginFraction); val my = maxOf(h * 2, height * marginFraction)
        val l = (minX - mx).toInt().coerceAtLeast(0); val t = (minY - my).toInt().coerceAtLeast(0)
        val r = (maxX + mx).toInt().coerceAtMost(width); val b = (maxY + my).toInt().coerceAtMost(height)
        return if (r - l < 50 || b - t < 50) null else intArrayOf(l, t, r, b)
    }
}
