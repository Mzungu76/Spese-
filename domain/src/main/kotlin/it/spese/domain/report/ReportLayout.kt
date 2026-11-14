package it.spese.domain.report

import kotlin.math.max
import kotlin.math.min

/** Operazioni di disegno (coordinate in punti, origine in alto a sinistra): le eseguono il PDF su Android e, nei test, un'immagine. */
sealed interface Op
data class TextOp(val x: Float, val y: Float, val text: String, val size: Float, val bold: Boolean, val color: Int, val align: Align = Align.LEFT) : Op
data class RectOp(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val radius: Float = 0f) : Op
data class LineOp(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val width: Float = 0.6f) : Op

class PageContent(val width: Float, val height: Float, val ops: MutableList<Op> = ArrayList())

/** Misura del testo: su Android con `Paint`, nei test con una stima. */
fun interface TextMetrics { fun width(text: String, size: Float, bold: Boolean): Float }

/** Impagina un [ReportDocument] in pagine A4 (verticali o orizzontali), con intestazione, piè di pagina e righe di tabella ripetute. */
object ReportLayout {
    const val INK = 0xFF1B1F2A.toInt(); const val MUTED = 0xFF6B7280.toInt(); const val LINE = 0xFFE3E6EB.toInt(); const val ZEBRA = 0xFFF6F7F9.toInt()
    const val ACCENT = 0xFF2358D8.toInt(); const val POS = 0xFF0E8A6A.toInt(); const val NEG = 0xFFC93B3B.toInt(); const val WARN = 0xFFB7791F.toInt(); const val GROUP = 0xFFE8EDFB.toInt()
    private const val A4_W = 595.28f; private const val A4_H = 841.89f
    private const val MX = 36f; private const val TOP = 44f; private const val BOTTOM = 42f

    fun toneColor(t: Tone) = when (t) { Tone.NORMAL -> INK; Tone.POSITIVE -> POS; Tone.NEGATIVE -> NEG; Tone.MUTED -> MUTED; Tone.WARNING -> WARN }

    fun layout(doc: ReportDocument, tm: TextMetrics): List<PageContent> = Layouter(doc, tm).run()

    private class Layouter(val doc: ReportDocument, val tm: TextMetrics) {
        val w = if (doc.landscape) A4_H else A4_W
        val h = if (doc.landscape) A4_W else A4_H
        val cw = w - 2 * MX
        val pages = ArrayList<PageContent>()
        lateinit var page: PageContent
        var y = 0f

        fun newPage(first: Boolean = false) {
            page = PageContent(w, h).also { pages.add(it) }
            y = TOP
            if (!first) {
                text(MX, y + 8, doc.title, 9f, true, MUTED); text(w - MX, y + 8, doc.subtitle.take(90), 8f, false, MUTED, Align.RIGHT)
                line(MX, y + 14, w - MX, y + 14); y += 28
            }
        }
        fun text(x: Float, base: Float, s: String, size: Float, bold: Boolean = false, color: Int = INK, align: Align = Align.LEFT) { if (s.isNotEmpty()) page.ops.add(TextOp(x, base, s, size, bold, color, align)) }
        fun rect(x: Float, yy: Float, ww: Float, hh: Float, color: Int, r: Float = 0f) { page.ops.add(RectOp(x, yy, ww, hh, color, r)) }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = LINE, wd: Float = 0.6f) { page.ops.add(LineOp(x1, y1, x2, y2, color, wd)) }
        fun need(hh: Float) { if (y + hh > h - BOTTOM) newPage() }
        fun width(s: String, size: Float, bold: Boolean) = tm.width(s, size, bold)

        fun fit(s: String, maxW: Float, size: Float, bold: Boolean): String {
            if (width(s, size, bold) <= maxW) return s
            var t = s
            while (t.isNotEmpty() && width("$t…", size, bold) > maxW) t = t.dropLast(1)
            return "$t…"
        }

        fun wrap(s: String, maxW: Float, size: Float, bold: Boolean, maxLines: Int = Int.MAX_VALUE): List<String> {
            val lines = ArrayList<String>()
            for (para in s.split('\n')) {
                var cur = ""
                for (word in para.split(' ').filter { it.isNotEmpty() }) {
                    var wd = word
                    while (width(wd, size, bold) > maxW && wd.length > 1) {   // parola più larga della colonna: spezzata
                        var n = wd.length
                        while (n > 1 && width(wd.take(n), size, bold) > maxW) n--
                        if (cur.isNotEmpty()) { lines.add(cur); cur = "" }
                        lines.add(wd.take(n)); wd = wd.drop(n)
                    }
                    val cand = if (cur.isEmpty()) wd else "$cur $wd"
                    if (width(cand, size, bold) <= maxW) cur = cand else { lines.add(cur); cur = wd }
                }
                lines.add(cur)
            }
            val out = lines.filterIndexed { i, l -> l.isNotEmpty() || i < lines.lastIndex }.ifEmpty { listOf("") }
            return if (out.size <= maxLines) out else out.take(maxLines - 1) + fit(out.drop(maxLines - 1).joinToString(" "), maxW, size, bold)
        }

        fun run(): List<PageContent> {
            newPage(first = true)
            // frontespizio
            text(MX, y + 18, doc.title, 22f, true, INK); y += 28
            wrap(doc.subtitle, cw, 10.5f, false).forEach { text(MX, y + 12, it, 10.5f, false, MUTED); y += 14 }
            y += 4
            line(MX, y, w - MX, y, ACCENT, 1.4f); y += 12
            if (doc.note.isNotBlank()) { wrap(doc.note, cw, 10f, false).forEach { need(14f); text(MX, y + 10, it, 10f); y += 13.5f }; y += 8 }
            doc.blocks.forEach { block(it) }
            // piè di pagina con numerazione
            pages.forEachIndexed { i, p ->
                page = p
                line(MX, h - BOTTOM + 8, w - MX, h - BOTTOM + 8)
                text(MX, h - BOTTOM + 20, "Gestione Spese · ${doc.generatedAt}", 7.5f, false, MUTED)
                text(w - MX, h - BOTTOM + 20, "Pagina ${i + 1} di ${pages.size}", 7.5f, false, MUTED, Align.RIGHT)
            }
            return pages
        }

        fun block(b: Block) {
            when (b) {
                is Block.Heading -> { need(60f); y += 10; text(MX, y + 12, b.text, 14f, true, INK); y += 20 }
                is Block.Paragraph -> { y += 2; wrap(b.text, cw, 9.5f, false).forEach { need(13f); text(MX, y + 9.5f, it, 9.5f, false, toneColor(b.tone)); y += 12.5f }; y += 4 }
                is Block.Kpis -> kpis(b)
                is Block.Table -> table(b)
                is Block.Bars -> bars(b)
                is Block.Columns -> columns(b)
                Block.PageBreak -> newPage()
            }
        }

        fun kpis(b: Block.Kpis) {
            val n = b.items.size.coerceAtLeast(1); val gap = 8f; val bw = (cw - gap * (n - 1)) / n; val bh = 54f
            need(bh + 6)
            b.items.forEachIndexed { i, k ->
                val x = MX + i * (bw + gap)
                rect(x, y, bw, bh, ZEBRA, 6f)
                text(x + 8, y + 15, fit(k.label, bw - 16, 8.5f, false), 8.5f, false, MUTED)
                text(x + 8, y + 34, fit(k.value, bw - 16, 15f, true), 15f, true, toneColor(k.tone))
                k.sub?.let { text(x + 8, y + 47, fit(it, bw - 16, 7.5f, false), 7.5f, false, MUTED) }
            }
            y += bh + 8
        }

        fun table(b: Block.Table) {
            val cols = b.columns
            val size = if (cols.size > 7) 7.6f else 8.6f
            val pad = 4f; val lh = size + 2.6f
            val total = cols.sumOf { it.weight.toDouble() }.toFloat()
            val cwid = cols.map { cw * it.weight / total }
            b.title?.let { need(48f); y += 8; text(MX, y + 10, it, 10.5f, true); y += 16 }
            fun header() {
                need(lh + 2 * pad + 18)
                rect(MX, y, cw, lh + pad * 2, ZEBRA)
                var x = MX
                cols.forEachIndexed { i, c ->
                    val cell = fit(c.header, cwid[i] - 2 * pad, size, true)
                    when (c.align) { Align.LEFT -> text(x + pad, y + pad + size, cell, size, true, MUTED); Align.RIGHT -> text(x + cwid[i] - pad, y + pad + size, cell, size, true, MUTED, Align.RIGHT); Align.CENTER -> text(x + cwid[i] / 2, y + pad + size, cell, size, true, MUTED, Align.CENTER) }
                    x += cwid[i]
                }
                y += lh + pad * 2
                line(MX, y, w - MX, y, INK, 0.8f)
            }
            fun row(r: TRow, zebra: Boolean, last: Boolean = false) {
                if (r.group) {
                    need(2 * (lh + 2 * pad) + 6)
                    rect(MX, y, cw, lh + 2 * pad, GROUP); text(MX + pad, y + pad + size, fit(r.cells.firstOrNull().orEmpty(), cw - 2 * pad, size + 0.6f, true), size + 0.6f, true, ACCENT)
                    y += lh + 2 * pad; return
                }
                val cells = r.cells.mapIndexed { i, s ->
                    val maxW = cwid[i] - 2 * pad - (if (r.indent && i == 0) 10f else 0f)
                    if (cols[i].align == Align.LEFT) wrap(s, maxW, size, r.bold, 3) else listOf(fit(s, maxW, size, r.bold))
                }
                val rh = max(1, cells.maxOf { it.size }) * lh + 2 * pad
                if (y + rh > h - BOTTOM) { newPage(); header() }
                if (zebra) rect(MX, y, cw, rh, ZEBRA)
                var x = MX
                cells.forEachIndexed { i, ls ->
                    val color = toneColor(r.tones[i] ?: Tone.NORMAL)
                    ls.forEachIndexed { li, s ->
                        val by = y + pad + size + li * lh
                        when (cols[i].align) {
                            Align.LEFT -> text(x + pad + (if (r.indent && i == 0) 10f else 0f), by, s, size, r.bold, color)
                            Align.RIGHT -> text(x + cwid[i] - pad, by, s, size, r.bold, color, Align.RIGHT)
                            Align.CENTER -> text(x + cwid[i] / 2, by, s, size, r.bold, color, Align.CENTER)
                        }
                    }
                    x += cwid[i]
                }
                y += rh
                line(MX, y, w - MX, y, LINE, 0.4f)
            }
            header()
            var z = false
            b.rows.forEach { r -> if (r.group) z = false; row(r, zebra = z); if (!r.group) z = !z }
            b.footer?.let { f -> line(MX, y, w - MX, y, INK, 0.8f); row(f, zebra = false, last = true) }
            y += 8
        }

        fun bars(b: Block.Bars) {
            if (b.items.isEmpty()) return
            val rowH = 15f
            b.title?.let { need(40f); text(MX, y + 10, it, 10.5f, true); y += 16 }
            val labelW = cw * 0.30f; val valueW = 70f; val barMax = cw - labelW - valueW - 8
            val mx = b.items.maxOf { it.value }.coerceAtLeast(0.0001)
            y += 2
            b.items.forEach { it0 ->
                need(rowH + 2)
                text(MX, y + 10.5f, fit(it0.label, labelW - 6, 8.6f, false), 8.6f)
                val bw = (barMax * (it0.value / mx).toFloat()).coerceAtLeast(1.5f)
                rect(MX + labelW, y + 2.5f, bw, rowH - 6, it0.color, 2f)
                text(MX + labelW + bw + 5, y + 10.5f, it0.text, 8.4f, true, INK)
                y += rowH
            }
            y += 8
        }

        fun columns(b: Block.Columns) {
            if (b.labels.isEmpty() || b.series.isEmpty()) return
            val chartH = 130f; val axisW = 44f
            need(chartH + 52f)
            b.title?.let { text(MX, y + 10, it, 10.5f, true); y += 16 }
            // legenda
            var lx = MX
            b.series.forEach { s -> rect(lx, y + 2, 8f, 8f, s.color, 2f); text(lx + 11, y + 9.5f, s.name, 8.4f, false, INK); lx += 22 + width(s.name, 8.4f, false) }
            y += 22
            val top = y; val maxV = niceMax(b.series.maxOf { it.values.maxOrNull() ?: 0.0 })
            val plotX = MX + axisW; val plotW = cw - axisW
            for (i in 0..4) {
                val gy = top + chartH - chartH * i / 4f
                line(plotX, gy, plotX + plotW, gy, LINE, 0.4f)
                text(plotX - 4, gy + 3, shortMoney(maxV * i / 4), 7.2f, false, MUTED, Align.RIGHT)
            }
            val groupW = plotW / b.labels.size; val inner = groupW * 0.78f; val barW = inner / b.series.size
            b.labels.forEachIndexed { gi, label ->
                val gx = plotX + gi * groupW + (groupW - inner) / 2
                b.series.forEachIndexed { si, s ->
                    val v = s.values.getOrElse(gi) { 0.0 }
                    val bh = (chartH * (v / maxV)).toFloat()
                    if (bh > 0.4f) rect(gx + si * barW, top + chartH - bh, max(barW - 1f, 1f), bh, s.color, 1.5f)
                }
                val step = max(1, ((b.labels.size * 22f) / plotW).toInt() + 1)
                if (gi % step == 0) text(plotX + gi * groupW + groupW / 2, top + chartH + 11, label, 7.2f, false, MUTED, Align.CENTER)
            }
            y = top + chartH + 22
        }

        fun niceMax(v: Double): Double {
            if (v <= 0) return 1.0
            val exp = Math.pow(10.0, Math.floor(Math.log10(v)))
            val f = v / exp
            return (if (f <= 1) 1.0 else if (f <= 2) 2.0 else if (f <= 2.5) 2.5 else if (f <= 5) 5.0 else 10.0) * exp
        }
        fun shortMoney(v: Double) = if (v >= 1000) (if (v % 1000.0 == 0.0) "${(v / 1000).toInt()}k" else "%.1fk".format(java.util.Locale.ITALY, v / 1000)) else v.toInt().toString()
    }
}
