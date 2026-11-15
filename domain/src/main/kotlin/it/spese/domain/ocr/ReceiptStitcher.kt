package it.spese.domain.ocr

/** Esito dell'unione di più foto: `overlaps[i]` = righe in comune trovate tra la foto i e la i+1 (0 = nessuna sovrapposizione riconosciuta). */
data class StitchResult(val text: String, val overlaps: List<Int>) {
    /** Giunzioni senza sovrapposizione: lo scontrino potrebbe avere righe mancanti tra le foto. */
    val gaps: Int get() = overlaps.count { it == 0 }
}

/**
 * Unisce il testo di più foto dello stesso scontrino (scattate dall'alto verso il basso) togliendo le righe che compaiono due volte
 * dove le foto si sovrappongono. Si confrontano le righe in modo approssimato (l'OCR legge la stessa riga in modo un po' diverso
 * da una foto all'altra, specie ai bordi tagliati) cercando la sequenza comune più lunga tra la coda della prima e la testa della seconda.
 * Nella zona in comune si tiene la prima foto fino alle prime righe della seconda (tagliate in cima) e poi la seconda (la prima è tagliata in fondo).
 */
object ReceiptStitcher {
    private const val MIN_MATCHES = 3
    private const val MAX_LINES = 160

    fun key(line: String): String = line.lowercase().filter { it.isLetterOrDigit() }

    private fun similar(a: String, b: String): Boolean {
        val x = key(a); val y = key(b)
        if (x.length < 3 || y.length < 3) return false
        if (x == y) return true
        val longer = maxOf(x.length, y.length)
        if (longer < 6) return false
        return distance(x, y) <= longer * 0.2
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun lines(t: String) = t.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }

    fun merge(pages: List<String>): StitchResult {
        if (pages.isEmpty()) return StitchResult("", emptyList())
        var acc = lines(pages[0])
        val overlaps = ArrayList<Int>()
        for (page in pages.drop(1)) {
            val b = lines(page)
            val join = findOverlap(acc, b)
            if (join == null) { acc = acc + b; overlaps.add(0) }
            else { acc = acc.take(join.aCut) + b.drop(join.bCut); overlaps.add(join.matches) }
        }
        return StitchResult(acc.joinToString("\n"), overlaps)
    }

    /** Dal punto (aCut, bCut) in poi vale la seconda foto; prima vale la prima. */
    private data class Join(val aCut: Int, val bCut: Int, val matches: Int)

    /** Sequenza comune più lunga (LCS) tra la coda di `a` e la testa di `b`; vale solo se arriva fino in fondo ad `a`. */
    private fun findOverlap(a: List<String>, b: List<String>): Join? {
        if (a.size < MIN_MATCHES || b.size < MIN_MATCHES) return null
        val aOff = maxOf(0, a.size - MAX_LINES)
        val ta = a.drop(aOff); val tb = b.take(MAX_LINES)
        val n = ta.size; val m = tb.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (similar(ta[i], tb[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        // ricostruzione dei punti in comune
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (similar(ta[i], tb[j]) && dp[i][j] == dp[i + 1][j + 1] + 1) { pairs.add(i to j); i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        if (pairs.size < MIN_MATCHES) return null
        val (la, lb) = pairs.last()
        // la zona in comune deve finire in fondo alla prima foto (al più 3 righe tagliate dopo) e coprire quasi tutta la testa della seconda
        if (n - 1 - la > 3) return null
        if (pairs.size < (lb + 1) * 0.6) return null
        // nella zona in comune la seconda foto è più affidabile in fondo (la prima è tagliata) e la prima in cima (la seconda è tagliata): si passa alla seconda dalla terza riga di questa
        val cut = pairs.firstOrNull { it.second >= 2 } ?: pairs.last()
        return Join(aOff + cut.first, cut.second, pairs.size)
    }
}
