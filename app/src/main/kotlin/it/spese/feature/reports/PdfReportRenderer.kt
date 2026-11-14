package it.spese.feature.reports

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import it.spese.domain.report.*
import java.io.ByteArrayOutputStream

/** Disegna un [ReportDocument] in un PDF vero (testo selezionabile e vettoriale), con `PdfDocument` di Android: nessuna libreria esterna. */
object PdfReportRenderer {
    class Rendered(val bytes: ByteArray, val pageCount: Int)

    fun render(doc: ReportDocument): Rendered {
        val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val metrics = TextMetrics { text, size, isBold -> (if (isBold) bold else regular).also { it.textSize = size }.measureText(text) }
        val pages = ReportLayout.layout(doc, metrics)
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, p ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(p.width.toInt(), p.height.toInt(), i + 1).create())
                val c = page.canvas
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                p.ops.forEach { op ->
                    when (op) {
                        is RectOp -> { fill.color = op.color; if (op.radius > 0f) c.drawRoundRect(op.x, op.y, op.x + op.w, op.y + op.h, op.radius, op.radius, fill) else c.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, fill) }
                        is LineOp -> { stroke.color = op.color; stroke.strokeWidth = op.width; c.drawLine(op.x1, op.y1, op.x2, op.y2, stroke) }
                        is TextOp -> {
                            val paint = if (op.bold) bold else regular
                            paint.color = op.color; paint.textSize = op.size
                            paint.textAlign = when (op.align) { Align.LEFT -> Paint.Align.LEFT; Align.RIGHT -> Paint.Align.RIGHT; Align.CENTER -> Paint.Align.CENTER }
                            c.drawText(op.text, op.x, op.y, paint)
                        }
                    }
                }
                pdf.finishPage(page)
            }
            return Rendered(ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray(), pages.size)
        } finally { runCatching { pdf.close() } }
    }
}
