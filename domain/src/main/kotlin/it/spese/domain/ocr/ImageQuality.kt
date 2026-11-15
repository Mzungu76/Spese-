package it.spese.domain.ocr

/** Controlli sull'immagine (su array di luminanza 0..255, indipendenti da Android): nitidezza e contrasto. */
object ImageQuality {
    /** Varianza del laplaciano: alta = bordi netti (testo nitido), bassa = foto mossa o sfocata. Si calcola su un'immagine larga ~640 px. */
    fun sharpness(lum: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var n = 0; var sum = 0.0; var sumSq = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = (4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]).toDouble()
            sum += v; sumSq += v * v; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    const val BLUR_THRESHOLD = 60.0
    fun isBlurry(sharpness: Double) = sharpness < BLUR_THRESHOLD

    /** Allarga il contrasto: i valori fra il 2º e il 98º percentile diventano 0..255 (la carta termica sbiadita guadagna molto). */
    fun stretch(lum: IntArray, lowPct: Double = 0.02, highPct: Double = 0.98): IntArray {
        if (lum.isEmpty()) return lum
        val hist = IntArray(256); lum.forEach { hist[it.coerceIn(0, 255)]++ }
        fun pct(p: Double): Int { var acc = 0; val target = (lum.size * p).toInt(); for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }; return 255 }
        val lo = pct(lowPct); val hi = pct(highPct)
        if (hi - lo < 20) return lum
        return IntArray(lum.size) { i -> ((lum[i] - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
    }
}
