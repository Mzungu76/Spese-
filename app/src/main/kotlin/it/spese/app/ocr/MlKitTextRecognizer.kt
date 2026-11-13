package it.spese.app.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.spese.domain.repository.TextRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * OCR on-device con ML Kit (modello latino incluso nell'APK: funziona senza connessione e senza servizi Google).
 * L'interfaccia [TextRecognizer] permette di sostituire il motore o aggiungere analisi multipagina in futuro.
 */
@Singleton
class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imagePath: String): String {
        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) } ?: error("Immagine non leggibile")
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r ->
                    // ricostruisce le righe nell'ordine di lettura, una per riga visiva
                    val text = r.textBlocks.flatMap { it.lines }.sortedWith(compareBy({ it.boundingBox?.top ?: 0 / 20 }, { it.boundingBox?.left ?: 0 }))
                        .let { lines -> groupByRow(lines.map { Triple(it.boundingBox?.centerY() ?: 0, it.boundingBox?.left ?: 0, it.text) }) }
                    cont.resume(text)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** Unisce i frammenti che stanno sulla stessa riga (descrizione a sinistra, importo a destra). */
    private fun groupByRow(items: List<Triple<Int, Int, String>>): String {
        if (items.isEmpty()) return ""
        val sorted = items.sortedBy { it.first }
        val rows = ArrayList<MutableList<Triple<Int, Int, String>>>()
        for (it in sorted) {
            val last = rows.lastOrNull()
            if (last != null && kotlin.math.abs(last.last().first - it.first) <= 18) last.add(it) else rows.add(mutableListOf(it))
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.second }.joinToString("  ") { it.third } }
    }
}
