package it.spese.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.spese.domain.ocr.ImageQuality
import it.spese.domain.ocr.OcrLayout
import it.spese.domain.ocr.OcrLine
import it.spese.domain.ocr.OcrTiles
import it.spese.domain.repository.OcrPage
import it.spese.domain.repository.TextRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * OCR on-device con ML Kit (modello latino incluso nell'APK: funziona senza connessione e senza servizi Google).
 * Prima di leggere la foto la si prepara: grigio con contrasto allargato (carta termica sbiadita), raddrizzamento secondo l'inclinazione
 * del testo, ritaglio sul testo (via tavolo e sfondo) e, se è lunga, lettura a fasce sovrapposte a piena risoluzione.
 * Le righe si ricostruiscono dalla posizione e quelle lette con poca sicurezza vengono segnalate.
 */
@Singleton
class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imagePath: String): String = recognizePage(imagePath).text

    override suspend fun recognizePage(imagePath: String): OcrPage {
        val src = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) } ?: error("Immagine non leggibile")
        val bitmaps = ArrayList<Bitmap>()
        try {
            val gray = withContext(Dispatchers.Default) { enhance(src) }.also { bitmaps.add(it) }
            val first = ocr(gray)
            if (first.size < 3) return page(first)
            // raddrizzamento: se il testo è inclinato si ruota la foto e si rilegge
            val deg = Math.toDegrees(OcrLayout.skew(first).toDouble())
            val straight = if (abs(deg) in 1.5..25.0) withContext(Dispatchers.Default) { rotate(gray, -deg.toFloat()) }.also { bitmaps.add(it) } else gray
            val lines = if (straight === gray) first else ocr(straight)
            if (lines.size < 3) return page(first)
            // ritaglio sul testo
            val b = OcrTiles.textBounds(lines, straight.width, straight.height)
            val work = if (b != null) Bitmap.createBitmap(straight, b[0], b[1], b[2] - b[0], b[3] - b[1]).also { bitmaps.add(it) } else straight
            val dx = (b?.get(0) ?: 0).toFloat(); val dy = (b?.get(1) ?: 0).toFloat()
            val shifted = lines.map { it.copy(cx = it.cx - dx, cy = it.cy - dy) }
            // fasce sovrapposte se la foto è lunga e stretta; altrimenti basta la lettura già fatta
            val tiles = OcrTiles.plan(work.width, work.height)
            if (tiles.size <= 1) return page(shifted)
            val strips = tiles.map { r ->
                val tile = Bitmap.createBitmap(work, 0, r.first, work.width, r.last - r.first + 1)
                try { r to ocr(tile).map { it.copy(cy = it.cy + r.first) } } finally { tile.recycle() }
            }
            val merged = OcrTiles.mergeStrips(strips)
            return page(if (merged.size >= shifted.size / 2) merged else shifted)
        } finally { bitmaps.forEach { if (!it.isRecycled) it.recycle() }; src.recycle() }
    }

    private fun page(lines: List<OcrLine>): OcrPage {
        val rows = OcrLayout.rowsDetailed(lines)
        return OcrPage(rows.joinToString("\n") { it.text }, rows.filter { it.confidence < WEAK_BELOW && it.text.count { c -> c.isLetterOrDigit() } >= 4 }.map { it.text })
    }

    private suspend fun ocr(bmp: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                cont.resume(r.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    val c = l.cornerPoints?.takeIf { it.size == 4 } ?: return@mapNotNull null
                    OcrLine(l.text, c.map { it.x }.average().toFloat(), c.map { it.y }.average().toFloat(),
                        OcrLayout.angle(c[0].x.toFloat(), c[0].y.toFloat(), c[1].x.toFloat(), c[1].y.toFloat()),
                        kotlin.math.hypot((c[3].x - c[0].x).toFloat(), (c[3].y - c[0].y).toFloat()), l.confidence,
                        kotlin.math.hypot((c[1].x - c[0].x).toFloat(), (c[1].y - c[0].y).toFloat()))
                })
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Grigio con contrasto allargato (2º–98º percentile). */
    private fun enhance(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(px.size) { i -> val p = px[i]; (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt() }
        val s = ImageQuality.stretch(lum)
        for (i in px.indices) { val v = s[i]; px[i] = Color.rgb(v, v, v) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Ruota di [deg] gradi su fondo bianco, allargando la tela per non tagliare gli angoli. */
    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val rad = Math.toRadians(deg.toDouble())
        val w = src.width; val h = src.height
        val nw = (abs(w * cos(rad)) + abs(h * sin(rad))).toInt(); val nh = (abs(w * sin(rad)) + abs(h * cos(rad))).toInt()
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        val m = Matrix().apply { postTranslate(-w / 2f, -h / 2f); postRotate(deg); postTranslate(nw / 2f, nh / 2f) }
        c.drawBitmap(src, m, null)
        return out
    }

    private companion object { const val WEAK_BELOW = 0.75f }
}
